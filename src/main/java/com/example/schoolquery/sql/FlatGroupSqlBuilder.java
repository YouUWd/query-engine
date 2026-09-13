package com.example.schoolquery.sql;

import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.model.SysTableRelation;
import com.example.schoolquery.permission.PermissionContext;
import com.example.schoolquery.permission.PermissionRegistry;
import com.example.schoolquery.permission.SysDataScopeRule;
import com.example.schoolquery.querytree.FlatGroup;
import com.example.schoolquery.querytree.NestedGroup;
import com.example.schoolquery.relation.RelationResolver;
import org.jooq.*;
import org.jooq.Record;

import java.util.*;

import static org.jooq.impl.DSL.*;

/**
 * 把 QueryTreeBuilder 产出的 FlatGroup 树变成真正的 jOOQ 查询。
 *
 * 每个被选中的字段都别名成 {@code "f" + fieldId}（比如 fieldId=1012 就是 "f1012"），
 * 每个嵌套子级的 MULTISET 字段别名成 {@code "m" + childModuleId}（比如 "m103"）——
 * 用 id 而不是原始列名做别名，一是避免不同表里同名列（两张表都有 "id"）冲突，
 * 二是让上层的 RecordRenderer 能精确知道每一列该归到哪个 table/moduleId 桶里，
 * 不需要再去猜列名对应哪张表。
 *
 * 新规则保证了 FlatGroup 内部（同一模块或 SAME_ENTITY 合并进来的模块）的关联
 * 永远是安全的 LEFT JOIN；1:N 只出现在 NestedGroup 上，统一用 MULTISET 处理。
 *
 * 数据权限（表+字段 IN 过滤，行级）：
 *   字段级权限不在这里处理——调用方传进来的 requestedByModule / fieldIds 应该
 *   已经是权限过滤后的结果。这里只处理行级：某张表配置了 sys_data_scope_rule 时，
 *   给它的 primary_table 叠加一个 "column IN (调用方提供的允许值集合)" 条件。
 *   只作用于每个 FlatGroup 自己的 primary_table，不作用于被平铺 JOIN 进来的参照表。
 *
 * 额外过滤条件（extraConditionsByTable）：调用方（通常是 PagedFieldDrivenQueryService）
 * 把过滤条件解析好之后按表名传进来，这里负责在正确的层级把它们 AND 进去——只匹配
 * 某个 FlatGroup 自己的 primary_table，不处理该组内平铺 JOIN 进来的参照表上的过滤
 * （这是目前的一个已知简化，见 README）。
 */
public class FlatGroupSqlBuilder {

    private final RelationResolver resolver;
    private final PermissionRegistry permissionRegistry;

    public FlatGroupSqlBuilder(RelationResolver resolver) {
        this(resolver, new PermissionRegistry(List.of()));
    }

    public FlatGroupSqlBuilder(RelationResolver resolver, PermissionRegistry permissionRegistry) {
        this.resolver = resolver;
        this.permissionRegistry = permissionRegistry;
    }

    public static String fieldAlias(long fieldId) {
        return "f" + fieldId;
    }

    public static String nestedAlias(long childModuleId) {
        return "m" + childModuleId;
    }

    /** 不带权限上下文、不带额外过滤条件的老签名，保持向后兼容。 */
    public SelectConditionStep<Record> build(DSLContext dsl,
                                              FlatGroup group,
                                              Map<Long, List<SysModuleField>> requestedByModule,
                                              Condition condition) {
        return build(dsl, group, requestedByModule, condition, null, Map.of());
    }

    /** 带权限上下文、不带额外过滤条件。 */
    public SelectConditionStep<Record> build(DSLContext dsl,
                                              FlatGroup group,
                                              Map<Long, List<SysModuleField>> requestedByModule,
                                              Condition condition,
                                              PermissionContext permissionContext) {
        return build(dsl, group, requestedByModule, condition, permissionContext, Map.of());
    }

    /**
     * @param requestedByModule     调用方指定的字段，按 module_id 分组
     * @param condition             根节点上的过滤条件；递归到嵌套子级时，这里会换成"子表外键 = 父表主键"
     * @param permissionContext     调用方的行级权限上下文；传 null 表示不做任何行级过滤
     * @param extraConditionsByTable 按表名分组的额外过滤条件（来自请求里的 filters），
     *                               匹配到某个 FlatGroup 的 primary_table 时会 AND 进那一层
     */
    public SelectConditionStep<Record> build(DSLContext dsl,
                                              FlatGroup group,
                                              Map<Long, List<SysModuleField>> requestedByModule,
                                              Condition condition,
                                              PermissionContext permissionContext,
                                              Map<String, Condition> extraConditionsByTable) {
        Table<?> primary = table(name(group.primaryTable()));

        List<SelectFieldOrAsterisk> selectFields = new ArrayList<>();
        Map<String, Table<?>> joinedTables = new LinkedHashMap<>(); // 按表名去重，避免同一张表被 JOIN 两次
        Map<String, Condition> joinConditions = new LinkedHashMap<>();

        for (long moduleId : group.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                Table<?> owner;
                if (f.tableName().equals(group.primaryTable())) {
                    owner = primary;
                } else {
                    owner = joinedTables.computeIfAbsent(f.tableName(), t -> table(name(t)));
                    joinConditions.computeIfAbsent(f.tableName(),
                            t -> buildFlatJoinCondition(primary, group.primaryTable(), owner, t));
                }
                selectFields.add(DynamicFields.field(owner, f.columnName()).as(fieldAlias(f.id())));
            }
        }

        for (NestedGroup nested : group.nestedChildren()) {
            SysTableRelation rel = nested.relation();
            boolean primaryIsMain = rel.mainTable().equals(group.primaryTable());
            Field<Object> parentKey = DynamicFields.field(primary, primaryIsMain ? rel.mainField() : rel.joinField());

            Table<?> childPrimary = table(name(nested.group().primaryTable()));
            Field<Object> childFk = DynamicFields.field(childPrimary, primaryIsMain ? rel.joinField() : rel.mainField());

            SelectConditionStep<Record> childSelect = build(
                    dsl, nested.group(), requestedByModule, childFk.eq(parentKey),
                    permissionContext, extraConditionsByTable);

            selectFields.add(multiset(childSelect).as(nestedAlias(nested.childModuleId())));
        }

        if (selectFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "模块组 " + group.mergedModuleIds() + "（主表 " + group.primaryTable() + "）没有任何被请求的字段");
        }

        SelectJoinStep<Record> from = dsl.select(selectFields).from(primary);
        SelectOnConditionStep<Record> joined = null;
        for (Map.Entry<String, Table<?>> e : joinedTables.entrySet()) {
            SelectJoinStep<Record> base = (joined == null) ? from : joined;
            joined = base.leftJoin(e.getValue()).on(joinConditions.get(e.getKey()));
        }

        Condition scoped = applyDataScope(primary, group.primaryTable(), condition, permissionContext);
        Condition withExtra = applyExtraCondition(group.primaryTable(), scoped, extraConditionsByTable);
        return joined != null ? joined.where(withExtra) : from.where(withExtra);
    }

    /**
     * 【方案 B 核心】只针对当前单个 FlatGroup 构建 SQL（主表 + 组内平铺表），彻底不拼装任何 MULTISET。
     *
     * @param extraProjectedFields 额外必须投影的字段（如用于与父或子做外键关联的列），
     *                             如果用户未选，也会别名投影出来供内存关联使用
     */
    public SelectConditionStep<Record> buildSingleGroupQuery(
            DSLContext dsl,
            FlatGroup group,
            Map<Long, List<SysModuleField>> requestedByModule,
            Condition condition,
            PermissionContext permissionContext,
            Map<String, Condition> extraConditionsByTable,
            Map<String, Field<?>> extraProjectedFields) {

        Table<?> primary = table(name(group.primaryTable()));

        List<SelectFieldOrAsterisk> selectFields = new ArrayList<>();
        Map<String, Table<?>> joinedTables = new LinkedHashMap<>();
        Map<String, Condition> joinConditions = new LinkedHashMap<>();

        Set<String> projectedAliases = new HashSet<>();

        for (long moduleId : group.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                Table<?> owner;
                if (f.tableName().equals(group.primaryTable())) {
                    owner = primary;
                } else {
                    owner = joinedTables.computeIfAbsent(f.tableName(), t -> table(name(t)));
                    joinConditions.computeIfAbsent(f.tableName(),
                            t -> buildFlatJoinCondition(primary, group.primaryTable(), owner, t));
                }
                String alias = fieldAlias(f.id());
                selectFields.add(DynamicFields.field(owner, f.columnName()).as(alias));
                projectedAliases.add(alias);
            }
        }

        if (extraProjectedFields != null) {
            for (Map.Entry<String, Field<?>> entry : extraProjectedFields.entrySet()) {
                String alias = entry.getKey();
                if (!projectedAliases.contains(alias)) {
                    selectFields.add(entry.getValue().as(alias));
                    projectedAliases.add(alias);
                }
            }
        }

        if (selectFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "模块组 " + group.mergedModuleIds() + "（主表 " + group.primaryTable() + "）没有任何被请求的字段");
        }

        SelectJoinStep<Record> from = dsl.select(selectFields).from(primary);
        SelectOnConditionStep<Record> joined = null;
        for (Map.Entry<String, Table<?>> e : joinedTables.entrySet()) {
            SelectJoinStep<Record> base = (joined == null) ? from : joined;
            joined = base.leftJoin(e.getValue()).on(joinConditions.get(e.getKey()));
        }

        Condition scoped = applyDataScope(primary, group.primaryTable(), condition, permissionContext);
        Condition withExtra = applyExtraCondition(group.primaryTable(), scoped, extraConditionsByTable);
        return joined != null ? joined.where(withExtra) : from.where(withExtra);
    }

    /**
     * 【方案 B 核心】轻量 Count 查询：只查根主表及平铺关联表，完全不含 MULTISET 子查询与业务列投影。
     */
    public SelectConditionStep<Record1<Integer>> buildCountQuery(
            DSLContext dsl,
            FlatGroup rootGroup,
            Map<Long, List<SysModuleField>> requestedByModule,
            Condition rootCondition,
            PermissionContext permissionContext,
            Map<String, Condition> extraConditionsByTable) {

        Table<?> primary = table(name(rootGroup.primaryTable()));

        Map<String, Table<?>> joinedTables = new LinkedHashMap<>();
        Map<String, Condition> joinConditions = new LinkedHashMap<>();

        // 仅关联根组内引用的平铺表（保证平铺表上的过滤条件或关联一致）
        for (long moduleId : rootGroup.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                if (!f.tableName().equals(rootGroup.primaryTable())) {
                    Table<?> owner = joinedTables.computeIfAbsent(f.tableName(), t -> table(name(t)));
                    joinConditions.computeIfAbsent(f.tableName(),
                            t -> buildFlatJoinCondition(primary, rootGroup.primaryTable(), owner, t));
                }
            }
        }

        SelectJoinStep<Record1<Integer>> from = dsl.selectCount().from(primary);
        SelectOnConditionStep<Record1<Integer>> joined = null;
        for (Map.Entry<String, Table<?>> e : joinedTables.entrySet()) {
            SelectJoinStep<Record1<Integer>> base = (joined == null) ? from : joined;
            joined = base.leftJoin(e.getValue()).on(joinConditions.get(e.getKey()));
        }

        Condition scoped = applyDataScope(primary, rootGroup.primaryTable(), rootCondition, permissionContext);
        Condition withExtra = applyExtraCondition(rootGroup.primaryTable(), scoped, extraConditionsByTable);
        return joined != null ? joined.where(withExtra) : from.where(withExtra);
    }

    private Condition applyExtraCondition(String tableName, Condition base, Map<String, Condition> extraByTable) {
        Condition extra = extraByTable.get(tableName);
        return extra == null ? base : base.and(extra);
    }

    /**
     * 行级数据权限：如果这张表配置了 sys_data_scope_rule，把 "column IN (允许值)" AND 进去。
     * 只用于当前 FlatGroup 的 primary_table，不用于平铺 JOIN 进来的参照表。
     */
    private Condition applyDataScope(Table<?> primary, String tableName,
                                      Condition baseCondition, PermissionContext ctx) {
        if (ctx == null) return baseCondition;
        Optional<SysDataScopeRule> ruleOpt = permissionRegistry.scopeRuleFor(tableName);
        if (ruleOpt.isEmpty()) return baseCondition;

        SysDataScopeRule rule = ruleOpt.get();
        Field<Object> scopeField = DynamicFields.field(primary, rule.scopeColumn());
        return baseCondition.and(scopeField.in(ctx.scopeValues(rule.contextKey())));
    }

    private Condition buildFlatJoinCondition(Table<?> primary, String primaryTableName,
                                              Table<?> other, String otherTableName) {
        SysTableRelation rel = resolver.relationOf(primaryTableName, otherTableName);
        if (rel.mainTable().equals(primaryTableName)) {
            return DynamicFields.field(primary, rel.mainField()).eq(DynamicFields.field(other, rel.joinField()));
        } else {
            return DynamicFields.field(primary, rel.joinField()).eq(DynamicFields.field(other, rel.mainField()));
        }
    }
}
