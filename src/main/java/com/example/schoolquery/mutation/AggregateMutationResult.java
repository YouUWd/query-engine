package com.example.schoolquery.mutation;

import java.util.List;
import java.util.Map;

/** Public result of an aggregate module mutation. */
public record AggregateMutationResult(
        int affectedRows,
        List<Object> generatedKeys,
        Map<String, Object> generatedKeysByClientKey) {
    public AggregateMutationResult {
        generatedKeys = generatedKeys == null ? List.of() : List.copyOf(generatedKeys);
        generatedKeysByClientKey = generatedKeysByClientKey == null ? Map.of() : Map.copyOf(generatedKeysByClientKey);
    }
}
