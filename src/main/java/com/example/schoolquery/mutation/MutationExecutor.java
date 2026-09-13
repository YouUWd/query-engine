package com.example.schoolquery.mutation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.plan.LogicalFieldRef;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.*;

/** Executes scalar mutation plans. Aggregate/cascade mutations are deliberately separate. */
public final class MutationExecutor {
    private final MetadataRegistry registry;

    public MutationExecutor(MetadataRegistry registry) {
        this.registry = registry;
    }

    public int execute(DSLContext dsl, MutationPlan plan) {
        return switch (plan.operation()) {
            case INSERT -> insert(dsl, plan);
            case UPDATE -> update(dsl, plan);
            case DELETE -> delete(dsl, plan);
        };
    }

    private int insert(DSLContext dsl, MutationPlan plan) {
        Table<?> table = table(name(registry.module(plan.rootModuleId()).primaryTable()));
        var step = dsl.insertInto(table);
        for (MutationPlan.Assignment assignment : plan.assignments()) {
            SysModuleField meta = registry.field(assignment.field().fieldId());
            step = step.set(field(meta), assignment.value());
        }
        return step.execute();
    }

    private int update(DSLContext dsl, MutationPlan plan) {
        Table<?> table = table(name(registry.module(plan.rootModuleId()).primaryTable()));
        var step = dsl.update(table);
        for (MutationPlan.Assignment assignment : plan.assignments()) {
            SysModuleField meta = registry.field(assignment.field().fieldId());
            step = step.set(field(meta), assignment.value());
        }
        return step.where(condition(plan.where())).execute();
    }

    private int delete(DSLContext dsl, MutationPlan plan) {
        Table<?> table = table(name(registry.module(plan.rootModuleId()).primaryTable()));
        return dsl.deleteFrom(table).where(condition(plan.where())).execute();
    }

    private Condition condition(MutationPlan.Where where) {
        if (where == null || where.predicates().isEmpty()) return trueCondition();
        Condition result = trueCondition();
        for (MutationPlan.Predicate predicate : where.predicates()) {
            if (!"EQ".equalsIgnoreCase(predicate.operator())) {
                throw new IllegalArgumentException("Unsupported mutation operator: " + predicate.operator());
            }
            SysModuleField meta = registry.field(predicate.field().fieldId());
            result = result.and(field(meta).eq(predicate.value()));
        }
        return result;
    }

    private Field<Object> field(SysModuleField meta) {
        return field(name(meta.tableName(), meta.columnName()), Object.class);
    }
}
