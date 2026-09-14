package com.example.schoolquery.query.model;

import java.util.List;

/** Boolean filter tree; keeps AND/OR semantics until physical SQL compilation. */
public sealed interface FilterExpressionPlan permits FilterExpressionPlan.Predicate, FilterExpressionPlan.And, FilterExpressionPlan.Or {
    record Predicate(FilterPlan filter) implements FilterExpressionPlan {}
    record And(List<FilterExpressionPlan> children) implements FilterExpressionPlan {
        public And { children = List.copyOf(children); }
    }
    record Or(List<FilterExpressionPlan> children) implements FilterExpressionPlan {
        public Or { children = List.copyOf(children); }
    }
}
