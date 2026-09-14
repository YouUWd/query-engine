package com.example.schoolquery.mutation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregate save model for module trees. This is intentionally separate from standard SQL
 * so cascade, orphan removal and optimistic locking are explicit business semantics.
 *
 * <p>When FULL_SYNC + orphanRemoval is enabled, {@code fullSyncChildModules} declares
 * which child collections are authoritative. This keeps an omitted child collection
 * different from an explicitly empty collection.</p>
 */
public record AggregateMutation(
        Operation operation,
        long moduleId,
        Map<String, Object> values,
        List<AggregateMutation> children,
        SaveMode saveMode,
        boolean orphanRemoval,
        Set<Long> fullSyncChildModules) {

    public AggregateMutation {
        values = values == null ? Map.of() : Map.copyOf(values);
        children = children == null ? List.of() : List.copyOf(children);
        saveMode = saveMode == null ? SaveMode.PATCH : saveMode;
        fullSyncChildModules = fullSyncChildModules == null
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(fullSyncChildModules));
    }

    /** Backward-compatible constructor: child modules represented by mutations are authoritative. */
    public AggregateMutation(Operation operation, long moduleId, Map<String, Object> values,
                             List<AggregateMutation> children, SaveMode saveMode, boolean orphanRemoval) {
        this(operation, moduleId, values, children, saveMode, orphanRemoval,
                children == null ? Set.of() : children.stream().map(AggregateMutation::moduleId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }

    public enum Operation { INSERT, UPDATE, DELETE }
    public enum SaveMode { PATCH, FULL_SYNC }
}
