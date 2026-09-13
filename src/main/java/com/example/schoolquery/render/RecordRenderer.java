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

/** Renders physical jOOQ records into the logical module result shape. */
public class RecordRenderer {
    public Map<String, Object> renderRecord(long rootModuleId, FlatGroup rootGroup, Record record,
                                             Map<Long, List<SysModuleField>> requestedByModule) {
        return renderRecord(rootModuleId, rootGroup, record, requestedByModule, Map.of());
    }

    public Map<String, Object> renderRecord(long rootModuleId, FlatGroup rootGroup, Record record,
                                             Map<Long, List<SysModuleField>> requestedByModule,
                                             Map<Long, String> aliasesByFieldId) {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(String.valueOf(rootModuleId), renderGroupBody(rootGroup, record, requestedByModule, aliasesByFieldId));
        return wrapped;
    }

    public Map<String, Object> renderGroupBody(FlatGroup group, Record record,
                                                Map<Long, List<SysModuleField>> requestedByModule) {
        return renderGroupBody(group, record, requestedByModule, Map.of());
    }

    public Map<String, Object> renderGroupBody(FlatGroup group, Record record,
                                                Map<Long, List<SysModuleField>> requestedByModule,
                                                Map<Long, String> aliasesByFieldId) {
        Map<String, Object> tableBuckets = new LinkedHashMap<>();
        for (long moduleId : group.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                Object value = record.get(FlatGroupSqlBuilder.fieldAlias(f.id()));
                Map<String, Object> bucket = (Map<String, Object>) tableBuckets
                        .computeIfAbsent(f.tableName(), t -> new LinkedHashMap<String, Object>());
                bucket.put(aliasesByFieldId.getOrDefault(f.id(), f.columnName()), value);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>(tableBuckets);
        for (NestedGroup nested : group.nestedChildren()) {
            if (nested.group().isVirtual()) {
                body.put(String.valueOf(nested.childModuleId()),
                        renderGroupBody(nested.group(), record, requestedByModule, aliasesByFieldId));
            } else {
                Result<Record> childRows = (Result<Record>) record.get(FlatGroupSqlBuilder.nestedAlias(nested.childModuleId()));
                if (childRows != null) {
                    List<Map<String, Object>> renderedChildren = childRows.stream()
                            .map(child -> renderGroupBody(nested.group(), child, requestedByModule, aliasesByFieldId))
                            .toList();
                    body.put(String.valueOf(nested.childModuleId()), renderedChildren);
                }
            }
        }
        return body;
    }

    public Map<String, Object> renderGroupBodyWithChildren(
            FlatGroup group, Record record, Map<Long, List<SysModuleField>> requestedByModule,
            Map<Long, Object> assembledChildrenByModule) {
        return renderGroupBodyWithChildren(group, record, requestedByModule, assembledChildrenByModule, Map.of());
    }

    public Map<String, Object> renderGroupBodyWithChildren(
            FlatGroup group, Record record, Map<Long, List<SysModuleField>> requestedByModule,
            Map<Long, Object> assembledChildrenByModule, Map<Long, String> aliasesByFieldId) {
        Map<String, Object> tableBuckets = new LinkedHashMap<>();
        for (long moduleId : group.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                Object value = record.get(FlatGroupSqlBuilder.fieldAlias(f.id()));
                Map<String, Object> bucket = (Map<String, Object>) tableBuckets
                        .computeIfAbsent(f.tableName(), t -> new LinkedHashMap<String, Object>());
                bucket.put(aliasesByFieldId.getOrDefault(f.id(), f.columnName()), value);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>(tableBuckets);
        if (assembledChildrenByModule != null) {
            for (NestedGroup nested : group.nestedChildren()) {
                body.put(String.valueOf(nested.childModuleId()),
                        assembledChildrenByModule.getOrDefault(nested.childModuleId(), nested.group().isVirtual() ? Map.of() : List.of()));
            }
        }
        return body;
    }
}
