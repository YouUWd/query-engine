package com.example.schoolquery.plan;
import java.util.List;
public record QueryPlan(Long rootModuleId,List<LogicalFieldRef> projections,List<RelationPlan> relations,List<FilterPlan> filters,SortPlan sort,PaginationPlan pagination){public QueryPlan{projections=projections==null?List.of():List.copyOf(projections);relations=relations==null?List.of():List.copyOf(relations);filters=filters==null?List.of():List.copyOf(filters);}}
