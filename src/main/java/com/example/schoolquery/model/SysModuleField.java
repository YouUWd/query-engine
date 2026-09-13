package com.example.schoolquery.model;

/** 对应 sys_module_field 表的一行配置——一个可供查询选择的字段。 */
public record SysModuleField(
        long id,
        long moduleId,
        String tableName,
        String columnName,
        String displayName,
        int sortOrder
) {}
