package com.example.schoolquery.render;

import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.plan.LogicalFieldRef;
import com.example.schoolquery.querytree.FlatGroup;
import com.example.schoolquery.querytree.NestedGroup;
import com.example.schoolquery.sql.FlatGroupSqlBuilder;
import org.jooq.Record;
import org.jooq.Result;

import java.util.*;

/** Renders physical jOOQ records into the logical module result shape. */
public class RecordRenderer {
    public Map<String, Object> renderRecord(long rootModuleId, FlatGroup rootGroup, Record record,
                                             Map<Long, List<SysModuleField>> requestedByModule) {
        return renderRecord(rootModuleId, rootGroup, record, requestedByModule, Map.of());
    }

    public Map<String, Object> renderRecord(long rootModuleId, FlatGroup rootGroup, Record record,
                                             Map<Long, List<SysModuleField>> requestedByModule,
                                             Map<Long, String> aliasesByFieldId) {
        Map<LogicalFieldRef, List<String>> occurrences = new LinkedHashMap<>();
        for (var entry : requestedByModule.entrySet()) {
            for (SysModuleField field : entry.getValue()) {
                occurrences.computeIfAbsent(new LogicalFieldRef(field.moduleId(), field.id()), ignored -> new ArrayList<>())
                        .add(aliasesByFieldId.getOrDefault(field.id(), field.columnName()));
            }
        }
        return renderRecordByProjectionAliases(rootModuleId, rootGroup, record, requestedByModule, occurrences);
    }

    public Map<String, Object> renderRecordByProjectionAliases(long rootModuleId, FlatGroup rootGroup, Record record,
                                                                 Map<Long, List<SysModuleField>> requestedByModule,
                                                                 Map<LogicalFieldRef, List<String>> aliasesByField) {
        Map<LogicalFieldRef, Deque<String>> occurrences = new LinkedHashMap<>();
        aliasesByField.forEach((ref, aliases) -> occurrences.put(ref, new ArrayDeque<>(aliases)));
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(String.valueOf(rootModuleId), renderGroupBodyByProjectionAliases(rootGroup, record, requestedByModule, occurrences, true));
        return wrapped;
    }

    public Map<String, Object> renderGroupBody(FlatGroup group, Record record,
                                                Map<Long, List<SysModuleField>> requestedByModule) {
        return renderGroupBody(group, record, requestedByModule, Map.of());
    }

    public Map<String, Object> renderGroupBody(FlatGroup group, Record record,
                                                Map<Long, List<SysModuleField>> requestedByModule,
                                                Map<Long, String> aliasesByFieldId) {
        Map<LogicalFieldRef, List<String>> occurrences = new LinkedHashMap<>();
        for (var entry : requestedByModule.entrySet()) {
            for (SysModuleField field : entry.getValue()) {
                occurrences.computeIfAbsent(new LogicalFieldRef(field.moduleId(), field.id()), ignored -> new ArrayList<>())
                        .add(aliasesByFieldId.getOrDefault(field.id(), field.columnName()));
            }
        }
        Map<LogicalFieldRef, Deque<String>> queues = new LinkedHashMap<>();
        occurrences.forEach((ref, aliases) -> queues.put(ref, new ArrayDeque<>(aliases)));
        return renderGroupBodyByProjectionAliases(group, record, requestedByModule, queues, false);
    }

    private Map<String, Object> renderGroupBodyByProjectionAliases(FlatGroup group, Record record,
                                                                     Map<Long, List<SysModuleField>> requestedByModule,
                                                                     Map<LogicalFieldRef, Deque<String>> aliasesByField,
                                                                     boolean useProjectionAliases) {
        Map<String, Object> tableBuckets = new LinkedHashMap<>();
        for (long moduleId : group.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                Map<String, Object> bucket = (Map<String, Object>) tableBuckets
                        .computeIfAbsent(f.tableName(), t -> new LinkedHashMap<String, Object>());
                Deque<String> aliases = aliasesByField.get(new LogicalFieldRef(f.moduleId(), f.id()));
                String configuredAlias = aliases == null || aliases.isEmpty() ? f.columnName() : aliases.removeFirst();
                String recordAlias = useProjectionAliases ? configuredAlias : FlatGroupSqlBuilder.fieldAlias(f.id());
                String outputAlias = useProjectionAliases ? configuredAlias : configuredAlias;
                Object value = record.get(recordAlias);
                bucket.put(outputAlias, value);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>(tableBuckets);
        for (NestedGroup nested : group.nestedChildren()) {
            if (nested.group().isVirtual()) {
                body.put(String.valueOf(nested.childModuleId()),
                        renderGroupBodyByProjectionAliases(nested.group(), record, requestedByModule, aliasesByField, useProjectionAliases));
            } else {
                Result<Record> childRows = (Result<Record>) record.get(FlatGroupSqlBuilder.nestedAlias(nested.childModuleId()));
                if (childRows != null) {
                    List<Map<String, Object>> renderedChildren = childRows.stream()
                            .map(child -> renderGroupBodyByProjectionAliases(nested.group(), child, requestedByModule, copyQueues(aliasesByField), useProjectionAliases))
                            .toList();
                    body.put(String.valueOf(nested.childModuleId()), renderedChildren);
                }
            }
        }
        return body;
    }

    private Map<LogicalFieldRef, Deque<String>> copyQueues(Map<LogicalFieldRef, Deque<String>> source) {
        Map<LogicalFieldRef, Deque<String>> copy = new LinkedHashMap<>();
        source.forEach((ref, aliases) -> copy.put(ref, new ArrayDeque<>(aliases)));
        return copy;
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
