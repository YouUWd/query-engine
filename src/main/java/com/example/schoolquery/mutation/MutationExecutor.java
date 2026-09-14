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

    public int execute(DSLContext dsl, MutationPlan plan) { return executeWithResult(dsl, plan).affectedRows(); }

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
        for (MutationPlan.Assignment a : plan.assignments()) values.put(field(registry.field(a.field().fieldId())), a.value());
        SysModuleField primaryKey = primaryKeyField(plan.rootModuleId());
        if (primaryKey == null || plan.assignments().stream().anyMatch(a -> a.field().fieldId() == primaryKey.id()))
            return new MutationExecutionResult(dsl.insertInto(table).set(values).execute(), List.of());
        Field<Object> keyField = field(primaryKey);
        Object generatedKey = dsl.insertInto(table).set(values).returning(keyField).fetchOne(keyField);
        return new MutationExecutionResult(1, generatedKey == null ? List.of() : List.of(generatedKey));
    }

    private int update(DSLContext dsl, MutationPlan plan) {
        Table<?> table = table(name(registry.module(plan.rootModuleId()).primaryTable()));
        Map<Field<Object>, Object> values = new LinkedHashMap<>();
        for (MutationPlan.Assignment a : plan.assignments()) values.put(field(registry.field(a.field().fieldId())), a.value());
        if (values.isEmpty()) throw new IllegalArgumentException("UPDATE has no assignments");
        return dsl.update(table).set(values).where(condition(plan.where())).execute();
    }

    private int delete(DSLContext dsl, MutationPlan plan) {
        return dsl.deleteFrom(table(name(registry.module(plan.rootModuleId()).primaryTable())))
                .where(condition(plan.where())).execute();
    }

    private Condition condition(MutationPlan.Where where) {
        if (where == null || where.expression() == null) return trueCondition();
        return condition(where.expression());
    }

    private Condition condition(MutationPlan.Expression expression) {
        if (expression instanceof MutationPlan.PredicateExpression p) return predicate(p.predicate());
        if (expression instanceof MutationPlan.And a) return condition(a.left()).and(condition(a.right()));
        if (expression instanceof MutationPlan.Or o) return condition(o.left()).or(condition(o.right()));
        throw new IllegalArgumentException("Unsupported mutation expression: " + expression);
    }

    @SuppressWarnings("unchecked")
    private Condition predicate(MutationPlan.Predicate p) {
        Field<Object> f = field(registry.field(p.field().fieldId()));
        String op = p.operator().toUpperCase();
        return switch (op) {
            case "EQ" -> p.value() == null ? f.isNull() : f.eq(p.value());
            case "NE" -> p.value() == null ? f.isNotNull() : f.ne(p.value());
            case "GT" -> f.gt(p.value());
            case "GE" -> f.ge(p.value());
            case "LT" -> f.lt(p.value());
            case "LE" -> f.le(p.value());
            case "LIKE" -> f.like(String.valueOf(p.value()));
            case "IS_NULL" -> f.isNull();
            case "IS_NOT_NULL" -> f.isNotNull();
            case "IN" -> f.in((List<Object>) p.value());
            case "NOT_IN" -> f.notIn((List<Object>) p.value());
            case "BETWEEN" -> {
                List<Object> bounds = (List<Object>) p.value();
                if (bounds.size() != 2) throw new IllegalArgumentException("BETWEEN requires exactly two values");
                yield f.between(bounds.get(0), bounds.get(1));
            }
            default -> throw new IllegalArgumentException("Unsupported mutation operator: " + p.operator());
        };
    }

    private SysModuleField primaryKeyField(long moduleId) {
        for (var fs : registry.fieldsGroupedByTable(moduleId).values())
            for (SysModuleField f : fs) if ("id".equalsIgnoreCase(f.columnName())) return f;
        return null;
    }

    private Field<Object> field(SysModuleField meta) { return DSL.field(name(meta.tableName(), meta.columnName()), Object.class); }
}
