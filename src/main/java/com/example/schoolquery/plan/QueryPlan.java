package com.example.schoolquery.plan;

import java.util.List;

/**
 * 语义查询计划。
 *
 * filters 是 AND-only 的兼容平面表示；filterExpression 才是完整语义，
 * 因此 OR/嵌套 AND 不会在编译阶段丢失。
 */
public record QueryPlan(
        Long rootModuleId,
        List<LogicalFieldRef> projections,
        List<RelationPlan> relations,
        List<FilterPlan> filters,
        FilterExpressionPlan filterExpression,
        SortPlan sort,
        PaginationPlan pagination) {

    public QueryPlan {
        projections = projections == null ? List.of() : List.copyOf(projections);
        relations = relations == null ? List.of() : List.copyOf(relations);
        filters = filters == null ? List.of() : List.copyOf(filters);
        sort = sort == null ? new SortPlan(List.of()) : sort;
    }

    /** 向后兼容旧调用方：只有平面 filters 时视为 AND。 */
    public QueryPlan(Long rootModuleId,
                     List<LogicalFieldRef> projections,
                     List<RelationPlan> relations,
                     List<FilterPlan> filters,
                     SortPlan sort,
                     PaginationPlan pagination) {
        this(rootModuleId, projections, relations, filters,
                filters == null || filters.isEmpty()
                        ? null
                        : new FilterExpressionPlan.And(
                                filters.stream().map(FilterExpressionPlan.Predicate::new).toList()),
                sort, pagination);
    }
}
