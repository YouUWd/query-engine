package com.example.schoolquery.querytree;

/**
 * A physical-table JOIN resolved in the context of the module tree.
 *
 * <p>The module ids are part of the identity on purpose. A table pair alone is
 * not sufficient to explain why two tables are joined: the same physical
 * tables can participate in different module paths with different business
 * meanings. The SQL renderer only consumes this already-resolved plan.</p>
 */
public record ResolvedTableJoinPlan(
        long sourceModuleId,
        long targetModuleId,
        String primaryTable,
        String otherTable,
        String primaryColumn,
        String otherColumn
) {
    /** Compatibility constructor for callers that already have a resolved table join. */
    public ResolvedTableJoinPlan(String primaryTable, String otherTable,
                                 String primaryColumn, String otherColumn) {
        this(0L, 0L, primaryTable, otherTable, primaryColumn, otherColumn);
    }

    public ResolvedTableJoinPlan {
        if (primaryTable == null || primaryTable.isBlank()) throw new IllegalArgumentException("primaryTable 不能为空");
        if (otherTable == null || otherTable.isBlank()) throw new IllegalArgumentException("otherTable 不能为空");
        if (primaryColumn == null || primaryColumn.isBlank()) throw new IllegalArgumentException("primaryColumn 不能为空");
        if (otherColumn == null || otherColumn.isBlank()) throw new IllegalArgumentException("otherColumn 不能为空");
    }
}
