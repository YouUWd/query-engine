package com.example.schoolquery.result;

import com.example.schoolquery.metadata.SysFieldType;

/** Standard result-column metadata; intentionally contains no UI concerns. */
public record ColumnMeta(long fieldId, long moduleId, String moduleName,
                         String tableName, String columnName, String columnAlias,
                         SysFieldType dataType) {}
