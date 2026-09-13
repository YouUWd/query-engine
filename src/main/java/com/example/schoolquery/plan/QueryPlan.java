package com.example.schoolquery.plan;

import java.util.List;

/** Immutable semantic query plan. */
public record QueryPlan(
        Long rootModuleId,
        List<LogicalFieldRef> projections,
        List<RelationPlan> relations,
        List<FilterPlan> filters,
        FilterExpressionPlan filterExpression,
        SortPlan sort,
        PaginationPlan pagination) {

    public QueryPlan {
        if (rootModuleId == null) throw new IllegalArgumentException("rootModuleId must not be null");
        projections = projections == null ? List.of() : List.copyOf(projections);
        relations = relations == null ? List.of() : List.copyOf(relations);
        filters = filters == null ? List.of() : List.copyOf(filters);
        sort = sort == null ? new SortPlan(List.of()) : sort;
    }

    /** Compatibility constructor: a flat filter list has AND semantics. */
    public QueryPlan(Long rootModuleId,
                     List<LogicalFieldRef> projections,
                     List<RelationPlan> relations,
                     List<FilterPlan> filters,
                     SortPlan sort,
                     PaginationPlan pagination) {
        this(rootModuleId, projections, relations, filters,
                filters == null || filters.isEmpty() ? null :
                        new FilterExpressionPlan.And(filters.stream()
                                .map(FilterExpressionPlan.Predicate::new).toList()),
                sort, pagination);
    }
}
