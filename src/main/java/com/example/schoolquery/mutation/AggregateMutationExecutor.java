package com.example.schoolquery.mutation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.model.SysTableRelation;
import com.example.schoolquery.relation.RelationResolver;
import org.jooq.*;
import org.jooq.impl.DSL;
import java.util.*;
import static org.jooq.impl.DSL.*;

/** Transactional aggregate executor for one datasource. */
public final class AggregateMutationExecutor {
    private final MetadataRegistry registry;
    private final RelationResolver relations;

    public AggregateMutationExecutor(MetadataRegistry registry, RelationResolver relations) {
        this.registry = Objects.requireNonNull(registry);
        this.relations = Objects.requireNonNull(relations);
    }

    /** Backward-compatible count-only API. */
    public int execute(DSLContext dsl, AggregateMutation mutation) {
        return executeWithResult(dsl, mutation).affectedRows();
    }

    /** Execute one aggregate atomically and retain generated-key correlation metadata. */
    public AggregateMutationResult executeWithResult(DSLContext dsl, AggregateMutation mutation) {
        return dsl.transactionResult(c -> {
            Collector collector = new Collector();
            int affected = apply(c.dsl(), mutation, null, null, collector);
            return new AggregateMutationResult(affected, collector.generatedKeys,
                    collector.generatedKeysByClientKey);
        });
    }

    private int apply(DSLContext dsl, AggregateMutation m, SysModule parent, Object parentKey, Collector collector) {
        SysModule module = registry.module(m.moduleId());
        if (module.isVirtual()) throw new IllegalArgumentException("Cannot mutate virtual module: " + module.id());
        validateFullSyncScope(m, module);
        return switch (m.operation()) {
            case INSERT -> insert(dsl, m, module, parent, parentKey, collector);
            case UPDATE -> update(dsl, m, module, parent, parentKey, collector);
            case DELETE -> delete(dsl, m, module, parent, parentKey);
        };
    }

    private void validateFullSyncScope(AggregateMutation m, SysModule module) {
        if (m.saveMode() != AggregateMutation.SaveMode.FULL_SYNC || !m.orphanRemoval()) return;
        for (long childModuleId : m.fullSyncChildModules()) {
            SysModule child = registry.module(childModuleId);
            if (!registry.children(module.id()).stream().anyMatch(x -> x.id() == child.id()))
                throw new IllegalArgumentException("FULL_SYNC child module " + childModuleId + " is not a direct child of module " + module.id());
        }
        for (AggregateMutation child : m.children()) {
            if (child.moduleId() == module.id())
                throw new IllegalArgumentException("Aggregate mutation cannot contain itself as a child: " + module.id());
        }
    }

    private int insert(DSLContext dsl, AggregateMutation m, SysModule module, SysModule parent, Object parentKey, Collector collector) {
        Map<SysModuleField, Object> values = values(module, m.values());
        if (parent != null && parentKey != null) applyParentForeignKey(module, parent, parentKey, values);
        Table<?> table = table(name(module.primaryTable()));
        Map<Field<Object>, Object> map = new LinkedHashMap<>();
        for (var e : values.entrySet()) map.put(field(e.getKey()), e.getValue());
        if (map.isEmpty()) throw new IllegalArgumentException("Aggregate INSERT has no values for module " + module.id());
        Object key = generatedKey(dsl, table, map, module);
        collector.record(m.clientKey(), key);
        int count = 1;
        for (AggregateMutation child : m.children()) count += apply(dsl, child, module, key, collector);
        return count;
    }

    private int update(DSLContext dsl, AggregateMutation m, SysModule module, SysModule parent, Object parentKey, Collector collector) {
        Map<SysModuleField, Object> values = values(module, m.values());
        SysModuleField pk = primaryKeyField(module);
        if (pk == null) throw new IllegalArgumentException("Aggregate UPDATE requires a primary key field for module " + module.id());
        Object key = values.get(pk);
        if (key == null) key = parentKey;
        if (key == null) throw new IllegalArgumentException("Aggregate UPDATE requires primary key for module " + module.id());
        values.remove(pk);
        Map<Field<Object>, Object> map = new LinkedHashMap<>();
        for (var e : values.entrySet()) map.put(field(e.getKey()), e.getValue());
        Table<?> table = table(name(module.primaryTable()));
        int count = map.isEmpty() ? 0 : dsl.update(table).set(map).where(field(pk).eq(key)).execute();
        collector.record(m.clientKey(), key);
        for (AggregateMutation child : m.children()) count += apply(dsl, child, module, key, collector);
        if (m.saveMode() == AggregateMutation.SaveMode.FULL_SYNC && m.orphanRemoval()) count += removeOrphans(dsl, module, key, m);
        return count;
    }

    private int delete(DSLContext dsl, AggregateMutation m, SysModule module, SysModule parent, Object parentKey) {
        SysModuleField pk = primaryKeyField(module);
        if (pk == null) throw new IllegalArgumentException("Aggregate DELETE requires a primary key field for module " + module.id());
        Map<SysModuleField, Object> values = values(module, m.values());
        Object key = values.get(pk);
        if (key == null) key = parentKey;
        if (key == null) throw new IllegalArgumentException("Aggregate DELETE requires primary key for module " + module.id());
        return deleteByKey(dsl, module, key);
    }

    private int deleteByKey(DSLContext dsl, SysModule module, Object key) {
        int count = 0;
        for (SysModule child : registry.children(module.id())) {
            if (child.isVirtual() || child.primaryTable().equalsIgnoreCase(module.primaryTable())) continue;
            SysTableRelation rel = relations.parentChildRelation(module, child);
            if (rel == null) throw new IllegalArgumentException("No physical parent-child relation for modules " + module.id() + " -> " + child.id());
            SysModuleField childPk = primaryKeyField(child);
            SysModuleField childFk = findColumn(child, rel.joinField());
            if (childPk == null) throw new IllegalArgumentException("Aggregate child module " + child.id() + " has no primary key field");
            List<Object> childKeys = dsl.select(field(childPk)).from(table(name(child.primaryTable())))
                    .where(field(childFk).eq(key)).fetch(field(childPk));
            for (Object childKey : childKeys) count += deleteByKey(dsl, child, childKey);
        }
        SysModuleField pk = primaryKeyField(module);
        count += dsl.deleteFrom(table(name(module.primaryTable()))).where(field(pk).eq(key)).execute();
        return count;
    }

    private void applyParentForeignKey(SysModule child, SysModule parent, Object parentKey, Map<SysModuleField, Object> values) {
        if (child.primaryTable().equals(parent.primaryTable())) return;
        SysTableRelation rel = relations.parentChildRelation(parent, child);
        if (rel == null) throw new IllegalArgumentException("No physical parent-child relation for modules " + parent.id() + " -> " + child.id());
        values.putIfAbsent(findColumn(child, rel.joinField()), parentKey);
    }

    private int removeOrphans(DSLContext dsl, SysModule parent, Object parentKey, AggregateMutation m) {
        Map<Long, List<AggregateMutation>> byModule = new LinkedHashMap<>();
        for (AggregateMutation child : m.children()) byModule.computeIfAbsent(child.moduleId(), ignored -> new ArrayList<>()).add(child);
        Set<Long> scopes = m.fullSyncChildModules().isEmpty() ? byModule.keySet() : m.fullSyncChildModules();
        int count = 0;
        for (long childModuleId : scopes) {
            List<AggregateMutation> mutations = byModule.getOrDefault(childModuleId, List.of());
            SysModule child = registry.module(childModuleId);
            if (child.primaryTable().equalsIgnoreCase(parent.primaryTable())) continue;
            SysTableRelation rel = relations.parentChildRelation(parent, child);
            if (rel == null) throw new IllegalArgumentException("No physical parent-child relation for modules " + parent.id() + " -> " + child.id());
            SysModuleField fk = findColumn(child, rel.joinField());
            SysModuleField pk = primaryKeyField(child);
            if (pk == null) throw new IllegalArgumentException("Aggregate child module " + child.id() + " has no primary key field");
            Set<Object> keep = new HashSet<>();
            for (AggregateMutation mutation : mutations) {
                if (mutation.operation() == AggregateMutation.Operation.DELETE) continue;
                Object childKey = values(child, mutation.values()).get(pk);
                if (childKey != null) keep.add(childKey);
            }
            List<Object> orphanKeys = dsl.select(field(pk)).from(table(name(child.primaryTable())))
                    .where(field(fk).eq(parentKey).and(field(pk).notIn(keep))).fetch(field(pk));
            for (Object orphanKey : orphanKeys) count += deleteByKey(dsl, child, orphanKey);
        }
        return count;
    }

    private Object generatedKey(DSLContext dsl, Table<?> table, Map<Field<Object>, Object> values, SysModule module) {
        SysModuleField pk = primaryKeyField(module);
        if (pk == null) { dsl.insertInto(table).set(values).execute(); return null; }
        boolean explicitPrimaryKey = values.keySet().stream().anyMatch(f -> pk.columnName().equalsIgnoreCase(f.getName()));
        Field<Object> keyField = field(pk);
        if (explicitPrimaryKey) {
            dsl.insertInto(table).set(values).execute();
            return values.entrySet().stream().filter(e -> pk.columnName().equalsIgnoreCase(e.getKey().getName()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        return dsl.insertInto(table).set(values).returning(keyField).fetchOne(keyField);
    }

    private SysModuleField primaryKeyField(SysModule module) {
        for (var fs : registry.fieldsGroupedByTable(module.id()).values()) for (SysModuleField f : fs)
            if ("id".equalsIgnoreCase(f.columnName())) return f;
        return null;
    }

    private Map<SysModuleField, Object> values(SysModule module, Map<String, Object> input) {
        Map<SysModuleField, Object> r = new LinkedHashMap<>();
        for (var e : input.entrySet()) {
            SysModuleField field = resolve(module, e.getKey());
            if (!field.tableName().equalsIgnoreCase(module.primaryTable()))
                throw new IllegalArgumentException("Aggregate mutation field must belong to module primary table: " + e.getKey());
            r.put(field, e.getValue());
        }
        return r;
    }

    private SysModuleField resolve(SysModule module, String key) {
        String x = key.replace("`", "").trim();
        if (x.matches("f\\d+")) {
            SysModuleField f = registry.field(Long.parseLong(x.substring(1)));
            if (!registry.ancestorChain(f.moduleId()).contains(module.id())) throw new IllegalArgumentException("Field " + key + " is outside module " + module.id());
            return f;
        }
        return findColumn(module, x.contains(".") ? x.substring(x.lastIndexOf('.') + 1) : x);
    }

    private SysModuleField findColumn(SysModule module, String column) {
        List<SysModuleField> r = new ArrayList<>();
        for (var fs : registry.fieldsGroupedByTable(module.id()).values()) for (SysModuleField f : fs)
            if (f.columnName().equalsIgnoreCase(column)) r.add(f);
        if (r.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous column '" + column + "' in module " + module.id());
        return r.get(0);
    }

    private Field<Object> field(SysModuleField meta) { return DSL.field(name(meta.tableName(), meta.columnName()), Object.class); }

    private static final class Collector {
        private final List<Object> generatedKeys = new ArrayList<>();
        private final Map<String, Object> generatedKeysByClientKey = new LinkedHashMap<>();
        void record(String clientKey, Object key) {
            if (key != null) generatedKeys.add(key);
            if (clientKey != null) generatedKeysByClientKey.put(clientKey, key);
        }
    }
}
