package com.example.schoolquery.engine;

import java.util.List;

public record ModuleUpdateResult(int affectedRows, List<Object> generatedKeys) {
    public ModuleUpdateResult {
        generatedKeys = generatedKeys == null ? List.of() : List.copyOf(generatedKeys);
    }
}
