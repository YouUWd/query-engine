package com.example.schoolquery.query.model;

import java.util.List;

/** Immutable semantic query plan. */
public record QueryPlan(
        Long rootModuleId,
        List<LogicalFieldRef> projections,
        List<RelationPlan> relations,
        List<FilterPlan> filters,
        FilterExpressionPlan filterExpression,
        SortPlan sort,
        PaginationPlan pagination,
        List<String> projectionAliases) {

    public QueryPlan {
        if (rootModuleId == null) throw new IllegalArgumentException("rootModuleId must not be null");
        projections = projections == null ? List.of() : List.copyOf(projections);
        relations = relations == null ? List.of() : List.copyOf(relations);
        filters = filters == null ? List.of() : List.copyOf(filters);
        sort = sort == null ? new SortPlan(List.of()) : sort;
        projectionAliases = projectionAliases == null
                ? defaultAliases(projections)
                : List.copyOf(projectionAliases);
        if (projectionAliases.size() != projections.size()) {
            throw new IllegalArgumentException("projectionAliases size must equal projections size");
        }
        for (String alias : projectionAliases) {
            if (alias == null || alias.isBlank()) throw new IllegalArgumentException("projection alias must not be blank");
        }
    }

    /** Compatibility constructor: a flat filter list has AND semantics. */
    public QueryPlan(Long rootModuleId, List<LogicalFieldRef> projections, List<RelationPlan> relations,
                     List<FilterPlan> filters, SortPlan sort, PaginationPlan pagination) {
        this(rootModuleId, projections, relations, filters,
                filters == null || filters.isEmpty() ? null : new FilterExpressionPlan.And(
                        filters.stream().map(f -> (FilterExpressionPlan) new FilterExpressionPlan.Predicate(f)).toList()),
                sort, pagination, defaultAliases(projections));
    }

    /** Compatibility constructor retaining the Boolean filter tree. */
    public QueryPlan(Long rootModuleId, List<LogicalFieldRef> projections, List<RelationPlan> relations,
                     List<FilterPlan> filters, FilterExpressionPlan filterExpression,
                     SortPlan sort, PaginationPlan pagination) {
        this(rootModuleId, projections, relations, filters, filterExpression, sort, pagination,
                defaultAliases(projections));
    }

    private static List<String> defaultAliases(List<LogicalFieldRef> projections) {
        if (projections == null) return List.of();
        return projections.stream().map(ref -> "f" + ref.fieldId()).toList();
    }
}
