package com.example.schoolquery.plan;

import org.jooq.Condition;

/** Shared root filter artifact used by both data and count execution. */
public record RootFilterPlan(long rootModuleId, FilterExpressionPlan expression, Condition condition) {
    public RootFilterPlan {
        if (rootModuleId <= 0) throw new IllegalArgumentException("rootModuleId must be positive");
        if (condition == null) throw new IllegalArgumentException("condition must not be null");
    }
}
