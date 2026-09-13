package com.example.schoolquery.render;

import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.querytree.FlatGroup;
import com.example.schoolquery.querytree.NestedGroup;
import com.example.schoolquery.sql.FlatGroupSqlBuilder;
import org.jooq.Record;
import org.jooq.Result;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 FlatGroupSqlBuilder 产出、已经 fetch() 完的 jOOQ Record，按 FlatGroup 树的形状
 * 渲染成嵌套 Map：
 *   - 同一个 FlatGroup 自己（含 SAME_ENTITY 合并进来的模块）请求的字段，按 table_name
 *     分桶，每张表一个子 Map（对应输出里的 "student"、"clazz" 这种 key）
 *   - 每个 NestedGroup 渲染成一个以 childModuleId（字符串形式）为 key 的数组
 *
 * 最外层由调用方（PagedFieldDrivenQueryService）负责用根 moduleId 包一层，
 * 这个类只管一个 FlatGroup 对应的“组体”怎么渲染。
 */
public class RecordRenderer {

    /** 渲染成 {@code {String(rootModuleId): 组体}}，对应一条 records 数组元素。 */
    public Map<String, Object> renderRecord(long rootModuleId, FlatGroup rootGroup, Record record,
                                             Map<Long, List<SysModuleField>> requestedByModule) {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(String.valueOf(rootModuleId), renderGroupBody(rootGroup, record, requestedByModule));
        return wrapped;
    }

    /**
     * 渲染一个 FlatGroup 自己的“组体”：table 桶 + 嵌套子级数组，不包 moduleId 这一层
     * （那一层由 renderRecord 在最外层加，嵌套数组的 key 则由父级用 childModuleId 直接加）。
     *
     * requestedByModule 是本次查询实际选中的字段（可能是模块全部字段的子集），
     * 只渲染这些字段，而不是模块配置的全部字段。
     */
    public Map<String, Object> renderGroupBody(FlatGroup group, Record record,
                                                Map<Long, List<SysModuleField>> requestedByModule) {
        Map<String, Object> tableBuckets = new LinkedHashMap<>();

        for (long moduleId : group.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                Object value = record.get(FlatGroupSqlBuilder.fieldAlias(f.id()));
                @SuppressWarnings("unchecked")
                Map<String, Object> bucket = (Map<String, Object>) tableBuckets
                        .computeIfAbsent(f.tableName(), t -> new LinkedHashMap<String, Object>());
                bucket.put(f.columnName(), value);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>(tableBuckets);

        for (NestedGroup nested : group.nestedChildren()) {
            if (nested.group().isVirtual()) {
                // 虚拟模块自己没有独立的行/Record，直接渲染其虚拟组体
                Map<String, Object> virtualBody = renderGroupBody(nested.group(), record, requestedByModule);
                body.put(String.valueOf(nested.childModuleId()), virtualBody);
            } else {
                @SuppressWarnings("unchecked")
                Result<Record> childRows = (Result<Record>) record.get(FlatGroupSqlBuilder.nestedAlias(nested.childModuleId()));
                if (childRows != null) {
                    List<Map<String, Object>> renderedChildren = childRows.stream()
                            .map(childRecord -> renderGroupBody(nested.group(), childRecord, requestedByModule))
                            .toList();
                    body.put(String.valueOf(nested.childModuleId()), renderedChildren);
                }
            }
        }

        return body;
    }

    /**
     * 【方案 B 专用】渲染单个 FlatGroup 记录，并将预先批量查询并装配好的子级数据挂载进来。
     *
     * @param assembledChildrenByModule 嵌套子级的数据，key 是 childModuleId，value 是已经渲染好的该子级列表或虚拟组对象
     */
    public Map<String, Object> renderGroupBodyWithChildren(
            FlatGroup group,
            Record record,
            Map<Long, List<SysModuleField>> requestedByModule,
            Map<Long, Object> assembledChildrenByModule) {

        Map<String, Object> tableBuckets = new LinkedHashMap<>();

        for (long moduleId : group.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                Object value = record.get(FlatGroupSqlBuilder.fieldAlias(f.id()));
                @SuppressWarnings("unchecked")
                Map<String, Object> bucket = (Map<String, Object>) tableBuckets
                        .computeIfAbsent(f.tableName(), t -> new LinkedHashMap<String, Object>());
                bucket.put(f.columnName(), value);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>(tableBuckets);

        if (assembledChildrenByModule != null) {
            for (NestedGroup nested : group.nestedChildren()) {
                if (nested.group().isVirtual()) {
                    Object vData = assembledChildrenByModule.getOrDefault(nested.childModuleId(), Map.of());
                    body.put(String.valueOf(nested.childModuleId()), vData);
                } else {
                    Object children = assembledChildrenByModule.getOrDefault(
                            nested.childModuleId(), List.of());
                    body.put(String.valueOf(nested.childModuleId()), children);
                }
            }
        }

        return body;
    }
}
