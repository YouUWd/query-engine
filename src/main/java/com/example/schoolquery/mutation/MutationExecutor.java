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

/** Executes scalar Module SQL mutations. */
public final class MutationExecutor {
    private final MetadataRegistry registry;
    private final RelationResolver relations;

    public MutationExecutor(MetadataRegistry registry) { this(registry, new RelationResolver(registry)); }
    public MutationExecutor(MetadataRegistry registry, RelationResolver relations) {
        this.registry = Objects.requireNonNull(registry);
        this.relations = Objects.requireNonNull(relations);
    }

    public int execute(DSLContext dsl, MutationPlan plan) { return executeWithResult(dsl, plan).affectedRows(); }

    public MutationExecutionResult executeWithResult(DSLContext dsl, MutationPlan plan) {
        return dsl.transactionResult(c -> switch (plan.operation()) {
            case INSERT -> insert(c.dsl(), plan);
            case UPDATE -> new MutationExecutionResult(update(c.dsl(), plan), List.of());
            case DELETE -> new MutationExecutionResult(delete(c.dsl(), plan), List.of());
        });
    }

    private MutationExecutionResult insert(DSLContext dsl, MutationPlan plan) {
        Map<String, List<MutationPlan.Assignment>> groups = assignmentsByTable(plan);
        SysModule module = registry.module(plan.rootModuleId());
        if (groups.size() > 1 || (groups.size() == 1 && !groups.containsKey(key(module.primaryTable()))))
            throw new IllegalArgumentException("Scalar cross-table INSERT is not supported yet; use aggregate mutation for multi-table INSERT");
        Table<?> table = table(name(module.primaryTable()));
        Map<Field<Object>, Object> values = assignmentMap(plan.assignments());
        SysModuleField primaryKey = primaryKeyField(module.id());
        if (primaryKey == null || plan.assignments().stream().anyMatch(a -> a.field().fieldId() == primaryKey.id()))
            return new MutationExecutionResult(dsl.insertInto(table).set(values).execute(), List.of());
        Field<Object> keyField = field(primaryKey);
        Object generatedKey = dsl.insertInto(table).set(values).returning(keyField).fetchOne(keyField);
        return new MutationExecutionResult(1, generatedKey == null ? List.of() : List.of(generatedKey));
    }

    private int update(DSLContext dsl, MutationPlan plan) {
        SysModule module = registry.module(plan.rootModuleId());
        Map<String, List<MutationPlan.Assignment>> groups = assignmentsByTable(plan);
        if (groups.isEmpty()) throw new IllegalArgumentException("UPDATE has no assignments");
        List<MutationPlan.Assignment> root = groups.remove(key(module.primaryTable()));
        if (!groups.isEmpty() && whereTouchesNonPrimaryTable(plan.where(), module))
            throw new IllegalArgumentException("Scalar cross-table UPDATE WHERE must reference only module primary-table fields; use aggregate mutation for secondary-table predicates");

        int logicalRows = dsl.fetchCount(table(name(module.primaryTable())), condition(plan.where()));
        if (root != null && !root.isEmpty()) updateTable(dsl, module.primaryTable(), root, plan.where());

        for (List<MutationPlan.Assignment> secondary : groups.values()) {
            String targetTable = tableOf(secondary);
            SysTableRelation relation = oneToOneDirectRelation(module, targetTable);
            Field<Object> targetJoin = field(name(targetTable, relation.joinField()), Object.class);
            Field<Object> rootJoin = field(name(module.primaryTable(), relation.mainField()), Object.class);
            Select<Record1<Object>> matching = dsl.select(rootJoin)
                    .from(table(name(module.primaryTable())))
                    .where(condition(plan.where()));
            dsl.update(table(name(targetTable)))
                    .set(assignmentMap(secondary))
                    .where(targetJoin.in(matching))
                    .execute();
        }
        return logicalRows;
    }

    private int updateTable(DSLContext dsl, String targetTable, List<MutationPlan.Assignment> assignments, MutationPlan.Where where) {
        return dsl.update(table(name(targetTable))).set(assignmentMap(assignments)).where(condition(where)).execute();
    }

    private int delete(DSLContext dsl, MutationPlan plan) {
        SysModule module = registry.module(plan.rootModuleId());
        if (hasSecondaryTables(module))
            throw new IllegalArgumentException("Scalar cross-table DELETE is not supported yet; use aggregate mutation for multi-table DELETE");
        return dsl.deleteFrom(table(name(module.primaryTable()))).where(condition(plan.where())).execute();
    }

    private SysTableRelation oneToOneDirectRelation(SysModule module, String targetTable) {
        SysTableRelation relation = relations.relationOfModule(module.id(), targetTable);
        if (relation.type() != com.example.schoolquery.model.RelationType.ONE_TO_ONE)
            throw new IllegalArgumentException("Scalar Module SQL cannot mutate 1:N table " + targetTable + "; use aggregate mutation");
        return relation;
    }

    private boolean hasSecondaryTables(SysModule module) {
        for (List<SysModuleField> fs : registry.fieldsGroupedByTable(module.id()).values())
            for (SysModuleField f : fs) if (!f.tableName().equalsIgnoreCase(module.primaryTable())) return true;
        return false;
    }

    private Map<String, List<MutationPlan.Assignment>> assignmentsByTable(MutationPlan plan) {
        Map<String, List<MutationPlan.Assignment>> result = new LinkedHashMap<>();
        for (MutationPlan.Assignment a : plan.assignments()) {
            SysModuleField f = registry.field(a.field().fieldId());
            result.computeIfAbsent(key(f.tableName()), ignored -> new ArrayList<>()).add(a);
        }
        return result;
    }

    private boolean whereTouchesNonPrimaryTable(MutationPlan.Where where, SysModule module) {
        if (where == null || where.expression() == null) return false;
        return whereTouchesNonPrimaryTable(where.expression(), module);
    }

    private boolean whereTouchesNonPrimaryTable(MutationPlan.Expression expression, SysModule module) {
        if (expression instanceof MutationPlan.PredicateExpression p)
            return !registry.field(p.predicate().field().fieldId()).tableName().equalsIgnoreCase(module.primaryTable());
        if (expression instanceof MutationPlan.And a) return whereTouchesNonPrimaryTable(a.left(), module) || whereTouchesNonPrimaryTable(a.right(), module);
        if (expression instanceof MutationPlan.Or o) return whereTouchesNonPrimaryTable(o.left(), module) || whereTouchesNonPrimaryTable(o.right(), module);
        return false;
    }

    private Map<Field<Object>, Object> assignmentMap(List<MutationPlan.Assignment> assignments) {
        Map<Field<Object>, Object> values = new LinkedHashMap<>();
        for (MutationPlan.Assignment a : assignments) values.put(field(registry.field(a.field().fieldId())), a.value());
        return values;
    }

    private String tableOf(List<MutationPlan.Assignment> assignments) {
        if (assignments.isEmpty()) throw new IllegalArgumentException("Mutation assignment group cannot be empty");
        return registry.field(assignments.get(0).field().fieldId()).tableName();
    }

    private String key(String table) { return table.toLowerCase(Locale.ROOT); }

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
