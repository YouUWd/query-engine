package com.example.schoolquery.query.resolver;

import java.util.List;

/**
 * 合并后的“一行” —— 可能由多个共享同一张 primary_table 的模块合并而成
 * （沿 SAME_ENTITY 边收拢的结果）。
 *
 * @param primaryTable    这一行对应的物理主表
 * @param mergedModuleIds 合并进这一行的模块 id（用于取各自请求的字段列表）
 * @param nestedChildren  1:N 子级，渲染成 SQL 时会变成 MULTISET 嵌套字段
 * @param tableJoins      已由元数据解析完成的平铺表 JOIN，不允许 SQL renderer 再按表名推断
 */
public record FlatGroup(
        String primaryTable,
        List<Long> mergedModuleIds,
        List<NestedGroup> nestedChildren,
        List<ResolvedTableJoinPlan> tableJoins
) {
    public FlatGroup(String primaryTable, List<Long> mergedModuleIds, List<NestedGroup> nestedChildren) {
        this(primaryTable, mergedModuleIds, nestedChildren, List.of());
    }

    public FlatGroup {
        mergedModuleIds = List.copyOf(mergedModuleIds == null ? List.of() : mergedModuleIds);
        nestedChildren = List.copyOf(nestedChildren == null ? List.of() : nestedChildren);
        tableJoins = List.copyOf(tableJoins == null ? List.of() : tableJoins);
    }

    public boolean isVirtual() {
        return primaryTable == null || primaryTable.isBlank();
    }
}
