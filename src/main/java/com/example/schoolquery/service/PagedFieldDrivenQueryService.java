package com.example.schoolquery.service;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.header.HeaderTreeBuilder;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.model.SysTableRelation;
import com.example.schoolquery.permission.PermissionContext;
import com.example.schoolquery.permission.PermissionRegistry;
import com.example.schoolquery.query.*;
import com.example.schoolquery.querytree.FlatGroup;
import com.example.schoolquery.querytree.NestedGroup;
import com.example.schoolquery.querytree.QueryTreeBuilder;
import com.example.schoolquery.relation.RelationResolver;
import com.example.schoolquery.render.FieldTypeRegistry;
import com.example.schoolquery.render.FilterConditionBuilder;
import com.example.schoolquery.render.RecordRenderer;
import com.example.schoolquery.sql.DynamicFields;
import com.example.schoolquery.sql.FlatGroupSqlBuilder;
import org.jooq.*;
import org.jooq.Record;

import java.util.*;

import static org.jooq.impl.DSL.*;

/**
 * 请求 -> 响应的完整编排：
 *
 *   QueryRequest --(QueryTreeBuilder)--> FlatGroup 树
 *                --(filters/sorts 解析)--> 额外过滤条件 + 排序 + 根节点 EXISTS 链
 *                --(FlatGroupSqlBuilder)--> jOOQ 查询（+ 分页 + 总数）
 *                --(RecordRenderer)--> 按 table/moduleId 分层的嵌套 Map
 *                --(HeaderTreeBuilder，withHeader=true 时)--> 表头树
 *
 * 已知限制（都在数据结构或语义上有明确取舍，不是遗漏）：
 *   - 排序字段必须落在查询根节点自己的组内（根节点自己的表 + 平铺 JOIN 进来的参照表），
 *     不支持对嵌套数组内部的字段排序——对嵌套 1:N 数据排序，"父行按什么顺序排"本身就
 *     是没有唯一答案的问题，交给前端在拿到数组后自己处理。
 *   - 过滤条件如果落在嵌套子级自己的表上，效果是"只保留有匹配子行的父行" + "嵌套数组
 *     本身也只留下匹配的子行"（用 EXISTS 语义实现），但如果过滤字段是嵌套子级内部
 *     平铺 JOIN 进来的参照表（不是子级自己的 primary_table），暂不支持，会报错——
 *     这种情况目前还没有实际场景覆盖到，需要的话可以再扩展。
 *   - filters 里的值默认按原样传给 jOOQ；如果传了 {@link FieldTypeRegistry}，会先按
 *     配置的类型转换（数字/日期字符串 -> 对应 Java 类型）再比较，避免被当成字符串处理。
 */
public class PagedFieldDrivenQueryService {

    private final MetadataRegistry registry;
    private final QueryTreeBuilder treeBuilder;
    private final FlatGroupSqlBuilder sqlBuilder;
    private final HeaderTreeBuilder headerBuilder;
    private final RecordRenderer renderer = new RecordRenderer();
    private final FieldTypeRegistry fieldTypeRegistry;

    public PagedFieldDrivenQueryService(MetadataRegistry registry, RelationResolver resolver) {
        this(registry, resolver, new PermissionRegistry(List.of()), new FieldTypeRegistry(List.of()));
    }

    public PagedFieldDrivenQueryService(MetadataRegistry registry, RelationResolver resolver,
                                         PermissionRegistry permissionRegistry) {
        this(registry, resolver, permissionRegistry, new FieldTypeRegistry(List.of()));
    }

    public PagedFieldDrivenQueryService(MetadataRegistry registry, RelationResolver resolver,
                                         PermissionRegistry permissionRegistry, FieldTypeRegistry fieldTypeRegistry) {
        this.registry = registry;
        this.treeBuilder = new QueryTreeBuilder(registry, resolver);
        this.sqlBuilder = new FlatGroupSqlBuilder(resolver, permissionRegistry);
        this.headerBuilder = new HeaderTreeBuilder(registry, resolver);
        this.fieldTypeRegistry = fieldTypeRegistry;
    }

    public PagedResult execute(DSLContext dsl, QueryRequest request) {
        return execute(dsl, request, null);
    }

    public PagedResult execute(DSLContext dsl, QueryRequest request, PermissionContext permissionContext) {
        Map<Long, List<SysModuleField>> requestedByModule = resolveByModule(request.fields());
        FlatGroup tree = treeBuilder.buildFromRoot(request.moduleId(), requestedByModule.keySet());

        // --- filters: 按表分组成额外条件；落在嵌套子级的，再叠加一条 EXISTS 链到根节点 ---
        Map<String, Condition> extraConditionsByTable = new LinkedHashMap<>();
        Condition rootExtra = trueCondition();

        for (FilterCriterion filter : request.filters()) {
            SysModuleField field = registry.field(filter.fieldId());
            Field<Object> column = DynamicFields.field(table(name(field.tableName())), field.columnName());
            var dataType = fieldTypeRegistry.typeOf(field.tableName(), field.columnName()).orElse(null);
            Condition leaf = FilterConditionBuilder.toCondition(column, filter.operator(), filter.value(), dataType);

            extraConditionsByTable.merge(field.tableName(), leaf, Condition::and);

            if (!isOwnedByRootGroup(tree, field.tableName(), requestedByModule)) {
                Optional<Condition> exists = buildNestedExists(tree, field.tableName(), leaf);
                if (exists.isEmpty()) {
                    throw new IllegalArgumentException(
                            "过滤字段 fieldId=" + filter.fieldId() + "（表 " + field.tableName() +
                            "）不在这次查询涉及的任何一张表里");
                }
                rootExtra = rootExtra.and(exists.get());
            }
        }

        // --- sorts: 目前只支持根节点自己的组内字段 ---
        List<SortField<?>> orderBy = new ArrayList<>();
        for (SortCriterion sort : request.sorts()) {
            SysModuleField field = registry.field(sort.fieldId());
            if (!isOwnedByRootGroup(tree, field.tableName(), requestedByModule)) {
                throw new IllegalArgumentException(
                        "排序字段 fieldId=" + sort.fieldId() + "（表 " + field.tableName() +
                        "）不在查询根节点自己的组内，暂不支持对嵌套数据排序");
            }
            Field<Object> column = DynamicFields.field(table(name(field.tableName())), field.columnName());
            orderBy.add(sort.direction() == SortDirection.DESC ? column.desc() : column.asc());
        }

        // --- 方案 B：轻量级 Count，彻底脱离 MULTISET ---
        long total = sqlBuilder.buildCountQuery(dsl, tree, requestedByModule, rootExtra, permissionContext, extraConditionsByTable)
                .fetchOne(0, long.class);

        // 如果 total 为 0，直接返回空结果，避免多余查询
        if (total == 0) {
            HeaderNode header = request.withHeader()
                    ? headerBuilder.build(request.moduleId(), requestedByModule)
                    : null;
            return new PagedResult(request.pageNo(), request.pageSize(), 0, List.of(), header);
        }

        // --- 方案 B：分页取根层数据（无 MULTISET）---
        // 收集根层为了关联其各个 nestedChildren（可能穿越虚拟模块）所需的所有 parentKey
        Map<String, Field<?>> rootExtraCols = new LinkedHashMap<>();
        Table<?> rootPrimaryTable = table(name(tree.primaryTable()));
        collectParentKeyColumns(tree, rootPrimaryTable, rootExtraCols);

        SelectConditionStep<Record> forFetch = sqlBuilder.buildSingleGroupQuery(
                dsl, tree, requestedByModule, rootExtra, permissionContext, extraConditionsByTable, rootExtraCols);

        int offset = (request.pageNo() - 1) * request.pageSize();
        Result<Record> rootRows = (orderBy.isEmpty() ? forFetch : forFetch.orderBy(orderBy))
                .limit(request.pageSize())
                .offset(offset)
                .fetch();

        // 递归批量加载所有 1:N nestedChildren 并在内存组装
        List<Map<String, Object>> records = loadAndAssemble(dsl, tree, rootRows, requestedByModule, permissionContext, extraConditionsByTable);

        // 包装外层 requested.moduleId()
        List<Map<String, Object>> wrappedRecords = records.stream()
                .map(body -> {
                    Map<String, Object> wrapped = new LinkedHashMap<>();
                    wrapped.put(String.valueOf(request.moduleId()), body);
                    return wrapped;
                })
                .toList();

        HeaderNode header = request.withHeader()
                ? headerBuilder.build(request.moduleId(), requestedByModule)
                : null;

        return new PagedResult(request.pageNo(), request.pageSize(), total, wrappedRecords, header);
    }

    /**
     * 递归收集一个真实物理组关联其子级或虚拟子级下的真实子级所需要的所有父键列
     */
    private void collectParentKeyColumns(FlatGroup group, Table<?> realTable, Map<String, Field<?>> extraCols) {
        for (NestedGroup nested : group.nestedChildren()) {
            if (nested.group().isVirtual()) {
                collectParentKeyColumns(nested.group(), realTable, extraCols);
            } else if (nested.relation() != null) {
                SysTableRelation rel = nested.relation();
                boolean primaryIsMain = rel.mainTable().equals(realTable.getName());
                String parentKeyCol = primaryIsMain ? rel.mainField() : rel.joinField();
                String alias = "_pk_" + parentKeyCol;
                extraCols.put(alias, DynamicFields.field(realTable, parentKeyCol));
            }
        }
    }

    /**
     * 【方案 B 核心】递归批量加载某一层的所有子数据并在内存中组装挂载。
     */
    private List<Map<String, Object>> loadAndAssemble(
            DSLContext dsl,
            FlatGroup currentGroup,
            Result<Record> currentRows,
            Map<Long, List<SysModuleField>> requestedByModule,
            PermissionContext permissionContext,
            Map<String, Condition> extraConditionsByTable) {

        if (currentRows == null || currentRows.isEmpty()) {
            return List.of();
        }

        // 为当前组内每条 row 准备挂载子级：Map<rowIndex, Map<childModuleId, Object>>
        // Object 对于普通 1:N 子级是 List<Map<String, Object>>，对于虚拟模块是 Map<String, Object>
        Map<Integer, Map<Long, Object>> childrenByRowIndex = new HashMap<>();
        for (int i = 0; i < currentRows.size(); i++) {
            childrenByRowIndex.put(i, new LinkedHashMap<>());
        }

        Table<?> parentTable = table(name(currentGroup.primaryTable()));

        for (NestedGroup nested : currentGroup.nestedChildren()) {
            if (nested.group().isVirtual()) {
                // 虚拟模块处理：它没有物理表，直接以当前行集合为依据，为其内部真实子模块加载数据
                loadVirtualGroupChildren(dsl, currentGroup, nested, currentRows, childrenByRowIndex,
                        requestedByModule, permissionContext, extraConditionsByTable);
                continue;
            }

            SysTableRelation rel = nested.relation();
            boolean primaryIsMain = rel.mainTable().equals(currentGroup.primaryTable());
            String parentCol = primaryIsMain ? rel.mainField() : rel.joinField();
            String childFkCol = primaryIsMain ? rel.joinField() : rel.mainField();

            String parentColAlias = "_pk_" + parentCol;

            // 1. 收集父级这一列的所有非空值
            List<Object> parentKeyValues = new ArrayList<>();
            Map<Object, List<Integer>> rowIndicesByParentKey = new LinkedHashMap<>();

            for (int i = 0; i < currentRows.size(); i++) {
                Record r = currentRows.get(i);
                Object val = r.get(parentColAlias);
                if (val == null) {
                    // 尝试通过别名或原始名 fallback
                    val = r.get(parentCol);
                }
                if (val != null) {
                    parentKeyValues.add(val);
                    rowIndicesByParentKey.computeIfAbsent(val, k -> new ArrayList<>()).add(i);
                }
            }

            if (parentKeyValues.isEmpty()) {
                continue;
            }

            // 2. 为子级准备查询：必须包含 childFk 作为 _fk_childFkCol 供分组，同时如果有更深层 nestedChildren，也要带上它的 parentKey
            Map<String, Field<?>> childExtraCols = new LinkedHashMap<>();
            Table<?> childPrimaryTable = table(name(nested.group().primaryTable()));
            String childFkAlias = "_fk_" + childFkCol;
            childExtraCols.put(childFkAlias, DynamicFields.field(childPrimaryTable, childFkCol));

            collectParentKeyColumns(nested.group(), childPrimaryTable, childExtraCols);

            // 3. 执行批量查询：WHERE childFk IN (:parentKeyValues)
            Condition batchCondition = DynamicFields.field(childPrimaryTable, childFkCol).in(parentKeyValues);
            SelectConditionStep<Record> childSelect = sqlBuilder.buildSingleGroupQuery(
                    dsl, nested.group(), requestedByModule, batchCondition,
                    permissionContext, extraConditionsByTable, childExtraCols);

            Result<Record> childRows = childSelect.fetch();

            // 4. 递归加载更深层的子级
            List<Map<String, Object>> renderedChildBodies = loadAndAssemble(
                    dsl, nested.group(), childRows, requestedByModule, permissionContext, extraConditionsByTable);

            // 5. 按外键值分发回父级对应行
            for (int ci = 0; ci < childRows.size(); ci++) {
                Record cr = childRows.get(ci);
                Object fkVal = cr.get(childFkAlias);
                Map<String, Object> childBody = renderedChildBodies.get(ci);

                List<Integer> parentIndices = rowIndicesByParentKey.get(fkVal);
                if (parentIndices != null) {
                    for (Integer pIdx : parentIndices) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> list = (List<Map<String, Object>>) childrenByRowIndex.get(pIdx)
                                .computeIfAbsent(nested.childModuleId(), k -> new ArrayList<Map<String, Object>>());
                        list.add(childBody);
                    }
                }
            }
        }

        // 6. 用 RecordRenderer 组装当前层所有行
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < currentRows.size(); i++) {
            Record r = currentRows.get(i);
            Map<Long, Object> assembledChildren = childrenByRowIndex.get(i);
            Map<String, Object> groupBody = renderer.renderGroupBodyWithChildren(
                    currentGroup, r, requestedByModule, assembledChildren);
            result.add(groupBody);
        }

        return result;
    }

    /**
     * 加载并挂载虚拟模块下的子级数据（虚拟模块本身体现为单对象 Map）
     */
    private void loadVirtualGroupChildren(
            DSLContext dsl,
            FlatGroup anchorGroup,
            NestedGroup virtualNested,
            Result<Record> parentRows,
            Map<Integer, Map<Long, Object>> parentChildrenByRowIndex,
            Map<Long, List<SysModuleField>> requestedByModule,
            PermissionContext permissionContext,
            Map<String, Condition> extraConditionsByTable) {

        FlatGroup virtualGroup = virtualNested.group();

        // 递归为虚拟组下面的每一个子级准备数据
        // 每行父记录对应一个虚拟模块 Map: parentRowIndex -> Map<virtualChildModuleId, Object>
        Map<Integer, Map<String, Object>> virtualMapsByRowIndex = new HashMap<>();
        for (int i = 0; i < parentRows.size(); i++) {
            virtualMapsByRowIndex.put(i, new LinkedHashMap<>());
        }

        for (NestedGroup childNested : virtualGroup.nestedChildren()) {
            if (childNested.group().isVirtual()) {
                // 虚拟嵌套虚拟：继续向下穿透
                Map<Integer, Map<Long, Object>> subChildrenByRowIndex = new HashMap<>();
                for (int i = 0; i < parentRows.size(); i++) {
                    subChildrenByRowIndex.put(i, new LinkedHashMap<>());
                }
                loadVirtualGroupChildren(dsl, anchorGroup, childNested, parentRows, subChildrenByRowIndex,
                        requestedByModule, permissionContext, extraConditionsByTable);
                for (int i = 0; i < parentRows.size(); i++) {
                    Object subObj = subChildrenByRowIndex.get(i).get(childNested.childModuleId());
                    virtualMapsByRowIndex.get(i).put(String.valueOf(childNested.childModuleId()), subObj);
                }
                continue;
            }

            // 真实子模块：其 relation 是相对 anchorGroup 的
            SysTableRelation rel = childNested.relation();
            boolean primaryIsMain = rel.mainTable().equals(anchorGroup.primaryTable());
            String parentCol = primaryIsMain ? rel.mainField() : rel.joinField();
            String childFkCol = primaryIsMain ? rel.joinField() : rel.mainField();

            String parentColAlias = "_pk_" + parentCol;

            List<Object> parentKeyValues = new ArrayList<>();
            Map<Object, List<Integer>> rowIndicesByParentKey = new LinkedHashMap<>();

            for (int i = 0; i < parentRows.size(); i++) {
                Record r = parentRows.get(i);
                Object val = r.get(parentColAlias);
                if (val == null) {
                    val = r.get(parentCol);
                }
                if (val != null) {
                    parentKeyValues.add(val);
                    rowIndicesByParentKey.computeIfAbsent(val, k -> new ArrayList<>()).add(i);
                }
            }

            if (parentKeyValues.isEmpty()) {
                continue;
            }

            Map<String, Field<?>> childExtraCols = new LinkedHashMap<>();
            Table<?> childPrimaryTable = table(name(childNested.group().primaryTable()));
            String childFkAlias = "_fk_" + childFkCol;
            childExtraCols.put(childFkAlias, DynamicFields.field(childPrimaryTable, childFkCol));

            collectParentKeyColumns(childNested.group(), childPrimaryTable, childExtraCols);

            Condition batchCondition = DynamicFields.field(childPrimaryTable, childFkCol).in(parentKeyValues);
            SelectConditionStep<Record> childSelect = sqlBuilder.buildSingleGroupQuery(
                    dsl, childNested.group(), requestedByModule, batchCondition,
                    permissionContext, extraConditionsByTable, childExtraCols);

            Result<Record> childRows = childSelect.fetch();

            List<Map<String, Object>> renderedChildBodies = loadAndAssemble(
                    dsl, childNested.group(), childRows, requestedByModule, permissionContext, extraConditionsByTable);

            // 分发到各行的虚拟模块对象中，形成 "virtualChildModuleId": [ ... ]
            Map<Integer, List<Map<String, Object>>> rowToChildList = new HashMap<>();
            for (int i = 0; i < parentRows.size(); i++) {
                rowToChildList.put(i, new ArrayList<>());
            }

            for (int ci = 0; ci < childRows.size(); ci++) {
                Record cr = childRows.get(ci);
                Object fkVal = cr.get(childFkAlias);
                Map<String, Object> childBody = renderedChildBodies.get(ci);

                List<Integer> parentIndices = rowIndicesByParentKey.get(fkVal);
                if (parentIndices != null) {
                    for (Integer pIdx : parentIndices) {
                        rowToChildList.get(pIdx).add(childBody);
                    }
                }
            }

            for (int i = 0; i < parentRows.size(); i++) {
                virtualMapsByRowIndex.get(i).put(
                        String.valueOf(childNested.childModuleId()), rowToChildList.get(i));
            }
        }

        // 把组装好的虚拟模块 Map 挂载到父级行的结构中
        for (int i = 0; i < parentRows.size(); i++) {
            parentChildrenByRowIndex.get(i).put(
                    virtualNested.childModuleId(), virtualMapsByRowIndex.get(i));
        }
    }

    /** 这张表是不是查询根节点自己组里的表（根节点自己的主表，或者组内平铺 JOIN 进来的参照表）。 */
    private boolean isOwnedByRootGroup(FlatGroup root, String table,
                                        Map<Long, List<SysModuleField>> requestedByModule) {
        if (table.equals(root.primaryTable())) return true;
        for (long moduleId : root.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                if (f.tableName().equals(table)) return true;
            }
        }
        return false;
    }

    /**
     * 在 group 的嵌套子级里递归找 targetTable，找到就用 EXISTS 相关子查询把 leaf 条件
     * 一路包到 group 这一层，返回给调用方 AND 进根节点的条件里。找不到返回 empty。
     */
    private Optional<Condition> buildNestedExists(FlatGroup group, String targetTable, Condition leaf) {
        Table<?> parentTable = group.primaryTable() != null ? table(name(group.primaryTable())) : null;

        for (NestedGroup nested : group.nestedChildren()) {
            Condition inner;
            if (!nested.group().isVirtual() && nested.group().primaryTable().equals(targetTable)) {
                inner = leaf;
            } else {
                Optional<Condition> deeper = buildNestedExists(nested.group(), targetTable, leaf);
                if (deeper.isEmpty()) continue;
                inner = deeper.get();
            }

            if (nested.group().isVirtual()) {
                // 穿透虚拟模块直接返回内部找到的条件
                return Optional.of(inner);
            }

            Table<?> childTable = table(name(nested.group().primaryTable()));
            SysTableRelation rel = nested.relation();
            boolean primaryIsMain = rel.mainTable().equals(group.primaryTable());
            Field<Object> parentKey = DynamicFields.field(parentTable, primaryIsMain ? rel.mainField() : rel.joinField());
            Field<Object> childFk = DynamicFields.field(childTable, primaryIsMain ? rel.joinField() : rel.mainField());

            return Optional.of(exists(selectOne().from(childTable).where(childFk.eq(parentKey).and(inner))));
        }
        return Optional.empty();
    }

    private Map<Long, List<SysModuleField>> resolveByModule(List<Long> fieldIds) {
        Map<Long, List<SysModuleField>> result = new LinkedHashMap<>();
        for (long fid : fieldIds) {
            SysModuleField f = registry.field(fid);
            result.computeIfAbsent(f.moduleId(), k -> new ArrayList<>()).add(f);
        }
        return result;
    }
}
