package com.example.schoolquery.plan;

import java.util.Objects;

/**
 * A relation after semantic resolution. SQL builders must consume this object
 * and must never rediscover a relation from physical table names.
 */
public record ResolvedRelationPlan(
        long parentModuleId,
        long childModuleId,
        RelationPlan.RelationType type,
        String parentTable,
        String parentColumn,
        String childTable,
        String childColumn) {
    public ResolvedRelationPlan {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(parentTable, "parentTable");
        Objects.requireNonNull(parentColumn, "parentColumn");
        Objects.requireNonNull(childTable, "childTable");
        Objects.requireNonNull(childColumn, "childColumn");
    }

    public RelationPlan asRelationPlan() {
        return new RelationPlan(parentModuleId, childModuleId, type,
                parentTable, parentColumn, childTable, childColumn);
    }
}
