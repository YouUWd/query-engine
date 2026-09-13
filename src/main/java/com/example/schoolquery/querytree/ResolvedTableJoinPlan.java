package com.example.schoolquery.querytree;

/**
 * A physical-table JOIN that has already been resolved from metadata.
 * SQL rendering must only render this plan and must not infer relationships by table names.
 */
public record ResolvedTableJoinPlan(
        String primaryTable,
        String otherTable,
        String primaryColumn,
        String otherColumn
) {
    public ResolvedTableJoinPlan {
        if (primaryTable == null || primaryTable.isBlank()) throw new IllegalArgumentException("primaryTable 不能为空");
        if (otherTable == null || otherTable.isBlank()) throw new IllegalArgumentException("otherTable 不能为空");
        if (primaryColumn == null || primaryColumn.isBlank()) throw new IllegalArgumentException("primaryColumn 不能为空");
        if (otherColumn == null || otherColumn.isBlank()) throw new IllegalArgumentException("otherColumn 不能为空");
    }
}
