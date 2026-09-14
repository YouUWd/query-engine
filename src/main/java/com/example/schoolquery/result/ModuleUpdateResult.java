package com.example.schoolquery.result;

import java.util.List;

public record ModuleUpdateResult(int affectedRows, List<Object> generatedKeys) {
    public ModuleUpdateResult {
        generatedKeys = generatedKeys == null ? List.of() : List.copyOf(generatedKeys);
    }
}
