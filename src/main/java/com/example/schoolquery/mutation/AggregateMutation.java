package com.example.schoolquery.mutation;

import java.util.List;
import java.util.Map;

/**
 * Aggregate save model for module trees. This is intentionally separate from standard SQL
 * so cascade, orphan removal and optimistic locking are explicit business semantics.
 */
public record AggregateMutation(
        Operation operation,
        long moduleId,
        Map<String, Object> values,
        List<AggregateMutation> children,
        SaveMode saveMode,
        boolean orphanRemoval) {

    public AggregateMutation {
        values = values == null ? Map.of() : Map.copyOf(values);
        children = children == null ? List.of() : List.copyOf(children);
        saveMode = saveMode == null ? SaveMode.PATCH : saveMode;
    }

    public enum Operation { INSERT, UPDATE, DELETE }
    public enum SaveMode { PATCH, FULL_SYNC }
}
