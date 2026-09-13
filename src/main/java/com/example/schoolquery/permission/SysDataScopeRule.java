package com.example.schoolquery.permission;

/**
 * 行级数据权限配置——表+字段 IN 过滤。对应一张新的元数据表 sys_data_scope_rule：
 * 声明"某张表按某一列做行级范围限制"，实际允许的值由调用方通过 PermissionContext
 * 在运行时提供（比如当前用户自己的 id，或者他管辖的班级 id 列表——单个值就传一个
 * 只有一个元素的集合，语义上就是 IN 一个只有一项的集合，不需要单独区分"等于"）。
 *
 * 只作用于查询树里"作为驱动表"的那一张——也就是根节点、以及每个嵌套 FlatGroup
 * 自己的 primary_table；不作用于被平铺 JOIN 进来的参照表（比如 clazz、teacher）——
 * 能看到学生这一行，就应该能看到它平铺关联出来的班级名称，不需要班级表自己再有
 * 一套行级权限。
 *
 * 每张表最多一条生效规则。
 */
public record SysDataScopeRule(
        String tableName,
        String scopeColumn,
        String contextKey   // 去 PermissionContext 里取允许值集合用的 key
) {}
