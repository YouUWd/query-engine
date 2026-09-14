package com.example.schoolquery.query.renderer;

/** table+column -> 过滤时应该转换成的数据类型。 */
public record SysFieldType(String tableName, String columnName, FieldDataType dataType) {}
