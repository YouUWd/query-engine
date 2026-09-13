package com.example.schoolquery.plan;

/** Exact SQL pagination semantics. pageNo is retained for compatibility; offset is authoritative. */
public record PaginationPlan(int pageNo, int pageSize, int offset) {
    public PaginationPlan {
        if (pageNo < 1 || pageSize < 1 || offset < 0) throw new IllegalArgumentException("invalid pagination");
    }

    /** Compatibility constructor for page-based callers. */
    public PaginationPlan(int pageNo, int pageSize) {
        this(pageNo, pageSize, Math.multiplyExact(pageNo - 1, pageSize));
    }
}
