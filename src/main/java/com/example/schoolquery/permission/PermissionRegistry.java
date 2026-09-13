package com.example.schoolquery.permission;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * sys_data_scope_rule 的内存索引——行级数据权限（表+字段 IN 过滤）。
 * 字段级权限不在这里：调用方组装 fieldIds 之前应该已经按权限过滤过了。
 */
public class PermissionRegistry {

    private final Map<String, SysDataScopeRule> scopeRuleByTable = new HashMap<>();

    public PermissionRegistry(List<SysDataScopeRule> scopeRules) {
        for (SysDataScopeRule r : scopeRules) {
            if (scopeRuleByTable.putIfAbsent(r.tableName(), r) != null) {
                throw new IllegalArgumentException("表 " + r.tableName() + " 配置了多条数据范围规则，每张表最多一条");
            }
        }
    }

    public Optional<SysDataScopeRule> scopeRuleFor(String table) {
        return Optional.ofNullable(scopeRuleByTable.get(table));
    }
}
