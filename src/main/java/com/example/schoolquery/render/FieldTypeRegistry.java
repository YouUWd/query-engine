package com.example.schoolquery.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SysFieldType 的内存索引。没有配置类型的字段查询时返回 empty——
 * FilterConditionBuilder 遇到 empty 就按原始值直接传给 jOOQ，不做任何转换
 * （保持向后兼容：不配置类型提示，行为和以前完全一样）。
 */
public class FieldTypeRegistry {

    private final Map<String, FieldDataType> byTableColumn = new HashMap<>();

    public FieldTypeRegistry(List<SysFieldType> types) {
        for (SysFieldType t : types) {
            String key = key(t.tableName(), t.columnName());
            if (byTableColumn.putIfAbsent(key, t.dataType()) != null) {
                throw new IllegalArgumentException("重复的字段类型配置: " + key);
            }
        }
    }

    public Optional<FieldDataType> typeOf(String table, String column) {
        return Optional.ofNullable(byTableColumn.get(key(table, column)));
    }

    private static String key(String table, String column) {
        return table + "." + column;
    }
}
