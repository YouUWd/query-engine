package com.example.schoolquery.querytree;

import java.util.List;

/**
 * A physical-table JOIN resolved in the context of a logical module-tree path.
 *
 * <p>The physical table pair is only the final edge; it is not the semantic
 * identity of the join. The owning module and its module-tree path explain why
 * this physical edge is applicable. SQL rendering must consume this resolved
 * object and must never rediscover a relation from the table pair.</p>
 */
public record ResolvedTableJoinPlan(
        long sourceModuleId,
        long targetModuleId,
        String primaryTable,
        String otherTable,
        String primaryColumn,
        String otherColumn,
        List<Long> modulePath
) {
    /** Compatibility constructor for callers that already have a resolved table join. */
    public ResolvedTableJoinPlan(String primaryTable, String otherTable,
                                 String primaryColumn, String otherColumn) {
        this(0L, 0L, primaryTable, otherTable, primaryColumn, otherColumn, List.of());
    }

    /** Compatibility constructor retained for the previous module-id based model. */
    public ResolvedTableJoinPlan(long sourceModuleId, long targetModuleId,
                                 String primaryTable, String otherTable,
                                 String primaryColumn, String otherColumn) {
        this(sourceModuleId, targetModuleId, primaryTable, otherTable,
                primaryColumn, otherColumn, List.of());
    }

    /** Preferred constructor for joins resolved from the module tree. */
    public ResolvedTableJoinPlan(long ownerModuleId, List<Long> modulePath,
                                 String primaryTable, String otherTable,
                                 String primaryColumn, String otherColumn) {
        this(ownerModuleId, ownerModuleId, primaryTable, otherTable,
                primaryColumn, otherColumn, modulePath);
    }

    public ResolvedTableJoinPlan {
        if (primaryTable == null || primaryTable.isBlank()) throw new IllegalArgumentException("primaryTable 不能为空");
        if (otherTable == null || otherTable.isBlank()) throw new IllegalArgumentException("otherTable 不能为空");
        if (primaryColumn == null || primaryColumn.isBlank()) throw new IllegalArgumentException("primaryColumn 不能为空");
        if (otherColumn == null || otherColumn.isBlank()) throw new IllegalArgumentException("otherColumn 不能为空");
        modulePath = modulePath == null ? List.of() : List.copyOf(modulePath);
        if (!modulePath.isEmpty() && !modulePath.contains(sourceModuleId)) {
            throw new IllegalArgumentException("modulePath 必须包含 JOIN 所属模块 " + sourceModuleId);
        }
    }

    /** Semantic owner of this module-internal physical join. */
    public long ownerModuleId() {
        return sourceModuleId;
    }

    /** Root-to-owner module path used to resolve this physical join. */
    public List<Long> resolvedModulePath() {
        return modulePath;
    }
}
