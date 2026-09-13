package com.example.schoolquery.mutation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModuleField;
import org.jooq.*;
import org.jooq.impl.DSL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.jooq.impl.DSL.*;

/** Executes scalar mutation plans. Aggregate/cascade mutations are deliberately separate. */
public final class MutationExecutor {
    private final MetadataRegistry registry;

    public MutationExecutor(MetadataRegistry registry) { this.registry = registry; }

    /** Compatibility API returning only the affected row count. */
    public int execute(DSLContext dsl, MutationPlan plan) {
        return executeWithResult(dsl, plan).affectedRows();
    }

    /** Executes a scalar mutation and returns generated primary keys for INSERT. */
    public MutationExecutionResult executeWithResult(DSLContext dsl, MutationPlan plan) {
        return switch (plan.operation()) {
            case INSERT -> insert(dsl, plan);
            case UPDATE -> new MutationExecutionResult(update(dsl, plan), List.of());
            case DELETE -> new MutationExecutionResult(delete(dsl, plan), List.of());
        };
    }

    private MutationExecutionResult insert(DSLContext dsl, MutationPlan plan) {
        Table<?> table = table(name(registry.module(plan.rootModuleId()).primaryTable()));
        Map<Field<Object>, Object> values = new LinkedHashMap<>();
        for (MutationPlan.Assignment a : plan.assignments()) {
            values.put(field(registry.field(a.field().fieldId())), a.value());
        }

        SysModuleField primaryKey = primaryKeyField(plan.rootModuleId());
        if (primaryKey == null) {
            return new MutationExecutionResult(dsl.insertInto(table).set(values).execute(), List.of());
        }

        Field<Object> keyField = field(primaryKey);
        if (values.containsKey(keyField)) {
            return new MutationExecutionResult(dsl.insertInto(table).set(values).execute(), List.of());
        }

        Object generatedKey = dsl.insertInto(table).set(values).returning(keyField).fetchOne(keyField);
        return new MutationExecutionResult(1, generatedKey == null ? List.of() : List.of(generatedKey));
    }

    private int update(DSLContext dsl, MutationPlan plan) {
        Table<?> table = table(name(registry.module(plan.rootModuleId()).primaryTable()));
        Map<Field<Object>, Object> values = new LinkedHashMap<>();
        for (MutationPlan.Assignment a : plan.assignments()) {
            values.put(field(registry.field(a.field().fieldId())), a.value());
        }
        return dsl.update(table).set(values).where(condition(plan.where())).execute();
    }

    private int delete(DSLContext dsl, MutationPlan plan) {
        return dsl.deleteFrom(table(name(registry.module(plan.rootModuleId()).primaryTable())))
                .where(condition(plan.where())).execute();
    }

    private Condition condition(MutationPlan.Where where) {
        if (where == null || where.predicates().isEmpty()) return trueCondition();
        Condition result = trueCondition();
        for (MutationPlan.Predicate p : where.predicates()) {
            if (!"EQ".equalsIgnoreCase(p.operator()))
                throw new IllegalArgumentException("Unsupported mutation operator: " + p.operator());
            result = result.and(field(registry.field(p.field().fieldId())).eq(p.value()));
        }
        return result;
    }

    private SysModuleField primaryKeyField(long moduleId) {
        for (var fs : registry.fieldsGroupedByTable(moduleId).values()) {
            for (SysModuleField f : fs) {
                if ("id".equalsIgnoreCase(f.columnName())) return f;
            }
        }
        return null;
    }

    private Field<Object> field(SysModuleField meta) {
        return DSL.field(name(meta.tableName(), meta.columnName()), Object.class);
    }
}
