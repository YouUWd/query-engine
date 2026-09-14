package com.example.schoolquery.result;

import java.util.List;
import java.util.Map;

/** ResultSet-like result. Count is deliberately not part of the contract. */
public record ModuleQueryResult(List<ColumnMeta> columns, List<Map<String, Object>> rows) {
    public ModuleQueryResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
