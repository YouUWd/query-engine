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

    public int execute(DSLContext dsl, AggregateMutation mutation) {
        return dsl.transactionResult(c -> apply(c.dsl(), mutation, null, null));
    }

    private int apply(DSLContext dsl, AggregateMutation m, SysModule parent, Object parentKey) {
        SysModule module = registry.module(m.moduleId());
        return switch (m.operation()) {
            case INSERT -> insert(dsl, m, module, parent, parentKey);
            case UPDATE -> update(dsl, m, module, parent, parentKey);
            case DELETE -> delete(dsl, m, module, parent, parentKey);
        };
    }

    private int insert(DSLContext dsl, AggregateMutation m, SysModule module, SysModule parent, Object parentKey) {
        Map<SysModuleField, Object> values = values(module, m.values());
        if (parent != null && parentKey != null) applyParentForeignKey(module, parent, parentKey, values);
        Table<?> table = table(name(module.primaryTable()));
        Map<Field<Object>, Object> map = new LinkedHashMap<>();
        for (var e : values.entrySet()) map.put(field(e.getKey()), e.getValue());
        if (map.isEmpty()) throw new IllegalArgumentException("Aggregate INSERT has no values for module " + module.id());
        Object key = generatedKey(dsl, table, map, module);
        int count = 1;
        for (AggregateMutation child : m.children()) count += apply(dsl, child, module, key);
        return count;
    }

    private int update(DSLContext dsl, AggregateMutation m, SysModule module, SysModule parent, Object parentKey) {
        Map<SysModuleField, Object> values = values(module, m.values());
        SysModuleField pk = primaryKeyField(module);
        Object key = pk == null ? null : values.get(pk);
        if (key == null) key = parentKey;
        if (key == null) throw new IllegalArgumentException("Aggregate UPDATE requires primary key");
        if (pk != null) values.remove(pk);

        Map<Field<Object>, Object> map = new LinkedHashMap<>();
        for (var e : values.entrySet()) map.put(field(e.getKey()), e.getValue());
        Table<?> table = table(name(module.primaryTable()));
        int count = map.isEmpty() ? 0 : dsl.update(table).set(map).where(field(pk).eq(key)).execute();
        for (AggregateMutation child : m.children()) count += apply(dsl, child, module, key);
        if (m.saveMode() == AggregateMutation.SaveMode.FULL_SYNC && m.orphanRemoval()) {
            count += removeOrphans(dsl, module, key, m.children());
        }
        return count;
    }

    private int delete(DSLContext dsl, AggregateMutation m, SysModule module, SysModule parent, Object parentKey) {
        SysModuleField pk = primaryKeyField(module);
        Map<SysModuleField, Object> values = values(module, m.values());
        Object key = pk == null ? parentKey : values.get(pk);
        if (key == null) throw new IllegalArgumentException("Aggregate DELETE requires primary key");
        int count = 0;
        for (AggregateMutation child : m.children()) count += apply(dsl, child, module, key);
        count += dsl.deleteFrom(table(name(module.primaryTable()))).where(field(pk).eq(key)).execute();
        return count;
    }

    private void applyParentForeignKey(SysModule child, SysModule parent, Object parentKey,
                                       Map<SysModuleField, Object> values) {
        if (child.primaryTable().equals(parent.primaryTable())) return;
        SysTableRelation rel = relations.relationOf(parent.primaryTable(), child.primaryTable());
        boolean parentIsMain = rel.mainTable().equals(parent.primaryTable());
        values.putIfAbsent(findColumn(child, parentIsMain ? rel.joinField() : rel.mainField()), parentKey);
    }

    /**
     * FULL_SYNC means the supplied child mutation list is the complete desired child set.
     * Each child mutation represents one row, so its own values carry the keep-set primary key.
     */
    private int removeOrphans(DSLContext dsl, SysModule parent, Object parentKey,
                              List<AggregateMutation> children) {
        int count = 0;
        for (AggregateMutation childMutation : children) {
            SysModule child = registry.module(childMutation.moduleId());
            if (child.primaryTable().equals(parent.primaryTable())) continue;
            SysTableRelation rel = relations.relationOf(parent.primaryTable(), child.primaryTable());
            boolean parentIsMain = rel.mainTable().equals(parent.primaryTable());
            SysModuleField fk = findColumn(child, parentIsMain ? rel.joinField() : rel.mainField());
            SysModuleField pk = primaryKeyField(child);
            if (pk == null) continue;

            Set<Object> keep = new HashSet<>();
            Object childKey = values(child, childMutation.values()).get(pk);
            if (childKey != null) keep.add(childKey);

            Condition condition = field(fk).eq(parentKey);
            if (!keep.isEmpty()) condition = condition.and(field(pk).notIn(keep));
            else condition = condition;
            count += dsl.deleteFrom(table(name(child.primaryTable()))).where(condition).execute();
        }
        return count;
    }

    private Object generatedKey(DSLContext dsl, Table<?> table, Map<Field<Object>, Object> values, SysModule module) {
        SysModuleField pk = primaryKeyField(module);
        if (pk == null || values.containsKey(field(pk))) {
            dsl.insertInto(table).set(values).execute();
            return pk == null ? null : values.get(field(pk));
        }
        Field<Object> keyField = field(pk);
        return dsl.insertInto(table).set(values).returning(keyField).fetchOne(keyField);
    }

    private SysModuleField primaryKeyField(SysModule module) {
        for (var fs : registry.fieldsGroupedByTable(module.id()).values())
            for (SysModuleField f : fs)
                if ("id".equalsIgnoreCase(f.columnName())) return f;
        return null;
    }

    private Map<SysModuleField, Object> values(SysModule module, Map<String, Object> input) {
        Map<SysModuleField, Object> r = new LinkedHashMap<>();
        for (var e : input.entrySet()) r.put(resolve(module, e.getKey()), e.getValue());
        return r;
    }

    private SysModuleField resolve(SysModule module, String key) {
        String x = key.replace("`", "").trim();
        if (x.matches("f\\d+")) {
            SysModuleField f = registry.field(Long.parseLong(x.substring(1)));
            if (!registry.ancestorChain(f.moduleId()).contains(module.id()))
                throw new IllegalArgumentException("Field " + key + " is outside module " + module.id());
            return f;
        }
        return findColumn(module, x.contains(".") ? x.substring(x.lastIndexOf('.') + 1) : x);
    }

    private SysModuleField findColumn(SysModule module, String column) {
        List<SysModuleField> r = new ArrayList<>();
        for (var fs : registry.fieldsGroupedByTable(module.id()).values())
            for (SysModuleField f : fs)
                if (f.columnName().equalsIgnoreCase(column)) r.add(f);
        if (r.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous column '" + column + "' in module " + module.id());
        return r.get(0);
    }

    private Field<Object> field(SysModuleField meta) {
        return DSL.field(name(meta.tableName(), meta.columnName()), Object.class);
    }
}
