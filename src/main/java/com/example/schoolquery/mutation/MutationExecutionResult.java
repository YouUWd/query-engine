package com.example.schoolquery.mutation;

import java.util.List;

/** Internal scalar mutation execution result, including database-generated keys when available. */
public record MutationExecutionResult(int affectedRows, List<Object> generatedKeys) {
    public MutationExecutionResult {
        generatedKeys = generatedKeys == null ? List.of() : List.copyOf(generatedKeys);
    }
}
