package com.example.schoolquery.engine;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.plan.FilterExpressionPlan;
import com.example.schoolquery.plan.LogicalFieldRef;
import com.example.schoolquery.plan.PlanConditionCompiler;
import com.example.schoolquery.plan.QueryPlan;
import com.example.schoolquery.querytree.FlatGroup;
import com.example.schoolquery.querytree.QueryTreeBuilder;
import com.example.schoolquery.relation.RelationResolver;
import com.example.schoolquery.render.RecordRenderer;
import com.example.schoolquery.sql.DynamicFields;
import com.example.schoolquery.sql.FlatGroupSqlBuilder;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Result;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.Record;
import java.util.*;
import static org.jooq.impl.DSL.*;

/** Executes a semantic QueryPlan without exposing QueryTree/FlatGroup to callers. */
public final class QueryPlanExecutor {
    private final MetadataRegistry registry;
    private final QueryTreeBuilder treeBuilder;
    private final FlatGroupSqlBuilder sqlBuilder;
    private final PlanConditionCompiler conditionCompiler;
    private final RecordRenderer renderer = new RecordRenderer();

    public QueryPlanExecutor(MetadataRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        RelationResolver resolver = new RelationResolver(registry);
        this.treeBuilder = new QueryTreeBuilder(registry, resolver);
        this.sqlBuilder = new FlatGroupSqlBuilder(resolver);
        this.conditionCompiler = new PlanConditionCompiler(registry);
    }

    public ModuleQueryResult execute(DSLContext dsl, QueryPlan plan) {
        Objects.requireNonNull(dsl, "dsl");
        Objects.requireNonNull(plan, "plan");
        if (plan.projections().isEmpty()) throw new IllegalArgumentException("SELECT projection must not be empty");

        Map<Long, List<SysModuleField>> requested = resolveProjection(plan.projections());
        Map<LogicalFieldRef, List<String>> aliases = projectionAliases(plan);
        FlatGroup tree = treeBuilder.buildFromRoot(plan.rootModuleId(), requested.keySet());
        final FlatGroup resolvedTree = treeBuilder.resolveTableJoins(tree, requested);
        Condition rootCondition = conditionCompiler.compile(dsl, plan.rootModuleId(), plan.filterExpression());
        Map<Long, Condition> localConditions = buildLocalConditions(dsl, plan.rootModuleId(), plan.filterExpression());

        var select = sqlBuilder.build(dsl, resolvedTree, requested, rootCondition, null, Map.of(), localConditions);
        List<SortField<?>> orderBy = buildOrderBy(plan, resolvedTree);
        Result<Record> rows;
        if (orderBy.isEmpty()) {
            rows = plan.pagination() == null ? select.fetch()
                    : select.limit(plan.pagination().pageSize()).offset(plan.pagination().offset()).fetch();
        } else {
            var ordered = select.orderBy(orderBy);
            rows = plan.pagination() == null ? ordered.fetch()
                    : ordered.limit(plan.pagination().pageSize()).offset(plan.pagination().offset()).fetch();
        }

        List<Map<String, Object>> rendered = rows.stream()
                .map(r -> renderer.renderRecordByProjectionAliases(plan.rootModuleId(), resolvedTree, r, requested, aliases))
                .toList();
        return new ModuleQueryResult(metadata(plan), rendered);
    }

    private Map<Long, Condition> buildLocalConditions(DSLContext dsl, long root, FilterExpressionPlan expression) {
        if (expression == null) return Map.of();
        Map<Long, Condition> result = new LinkedHashMap<>();
        for (var module : registry.allModules()) {
            if (module.id() == root || !registry.ancestorChain(module.id()).contains(root) || module.isVirtual()) continue;
            result.put(module.id(), conditionCompiler.compileLocal(dsl, module.id(), expression));
        }
        return result;
    }

    private Map<Long, List<SysModuleField>> resolveProjection(List<LogicalFieldRef> projections) {
        Map<Long, List<SysModuleField>> result = new LinkedHashMap<>();
        for (LogicalFieldRef ref : projections) {
            SysModuleField field = registry.field(ref.fieldId());
            if (field.moduleId() != ref.moduleId())
                throw new IllegalArgumentException("fieldId=" + ref.fieldId() + " does not belong to moduleId=" + ref.moduleId());
            result.computeIfAbsent(ref.moduleId(), k -> new ArrayList<>()).add(field);
        }
        return result;
    }

    private Map<LogicalFieldRef, List<String>> projectionAliases(QueryPlan plan) {
        Map<LogicalFieldRef, List<String>> aliases = new LinkedHashMap<>();
        for (int i = 0; i < plan.projections().size(); i++) {
            LogicalFieldRef ref = plan.projections().get(i);
            aliases.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(plan.projectionAliases().get(i));
        }
        return aliases;
    }

    private List<SortField<?>> buildOrderBy(QueryPlan plan, FlatGroup tree) {
        List<SortField<?>> result = new ArrayList<>();
        for (var item : plan.sort().items()) {
            SysModuleField field = registry.field(item.field().fieldId());
            Table<?> owner = ownerTable(tree, field.tableName());
            Field<Object> column = DynamicFields.field(owner, field.columnName());
            result.add(item.direction() == com.example.schoolquery.plan.SortPlan.Direction.DESC ? column.desc() : column.asc());
        }
        return result;
    }

    private Table<?> ownerTable(FlatGroup tree, String tableName) {
        if (tree.primaryTable().equals(tableName) || containsTable(tree, tableName)) return table(name(tableName));
        throw new IllegalArgumentException("ORDER BY field table is not part of the query root: " + tableName);
    }

    private boolean containsTable(FlatGroup group, String tableName) {
        for (long moduleId : group.mergedModuleIds())
            for (SysModuleField field : registry.fieldsGroupedByTable(moduleId).getOrDefault(tableName, List.of())) return true;
        for (var nested : group.nestedChildren()) if (containsTable(nested.group(), tableName)) return true;
        return false;
    }

    private List<ColumnMeta> metadata(QueryPlan plan) {
        List<ColumnMeta> result = new ArrayList<>();
        for (int i = 0; i < plan.projections().size(); i++) {
            LogicalFieldRef ref = plan.projections().get(i);
            SysModuleField field = registry.field(ref.fieldId());
            var module = registry.module(ref.moduleId());
            result.add(new ColumnMeta(field.id(), module.id(), module.moduleName(), field.tableName(), field.columnName(),
                    plan.projectionAliases().get(i), com.example.schoolquery.model.SysFieldType.UNKNOWN));
        }
        return result;
    }
}
