package com.example.schoolquery.mutation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregate save model for module trees. This is intentionally separate from standard SQL
 * so cascade, orphan removal and optimistic locking are explicit business semantics.
 *
 * <p>{@code clientKey} is optional correlation metadata supplied by the caller. It is never
 * written to the database; when present it is echoed in the aggregate result together with
 * the generated database primary key. This lets clients correlate temporary child nodes with
 * their actual generated ids.</p>
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
        Set<Long> fullSyncChildModules,
        String clientKey) {

    public AggregateMutation {
        values = values == null ? Map.of() : Map.copyOf(values);
        children = children == null ? List.of() : List.copyOf(children);
        saveMode = saveMode == null ? SaveMode.PATCH : saveMode;
        fullSyncChildModules = fullSyncChildModules == null
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(fullSyncChildModules));
        clientKey = clientKey == null || clientKey.isBlank() ? null : clientKey;
    }

    public AggregateMutation(Operation operation, long moduleId, Map<String, Object> values,
                             List<AggregateMutation> children, SaveMode saveMode, boolean orphanRemoval,
                             Set<Long> fullSyncChildModules) {
        this(operation, moduleId, values, children, saveMode, orphanRemoval, fullSyncChildModules, null);
    }

    /** Backward-compatible constructor: child modules represented by mutations are authoritative. */
    public AggregateMutation(Operation operation, long moduleId, Map<String, Object> values,
                             List<AggregateMutation> children, SaveMode saveMode, boolean orphanRemoval) {
        this(operation, moduleId, values, children, saveMode, orphanRemoval,
                children == null ? Set.of() : children.stream().map(AggregateMutation::moduleId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)), null);
    }

    /** Convenience constructor for callers that need generated-key correlation. */
    public AggregateMutation(Operation operation, long moduleId, Map<String, Object> values,
                             List<AggregateMutation> children, SaveMode saveMode, boolean orphanRemoval,
                             String clientKey) {
        this(operation, moduleId, values, children, saveMode, orphanRemoval,
                children == null ? Set.of() : children.stream().map(AggregateMutation::moduleId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)), clientKey);
    }

    public enum Operation { INSERT, UPDATE, DELETE }
    public enum SaveMode { PATCH, FULL_SYNC }
}
