package com.example.schoolquery.metadata;

/**
 * 父子模块（sys_module.parent_id）之间的关系分类。
 *
 * 新规则落地后，跨模块关系只剩两种合法可能——不再有 “同模块 1:N 聚合” 这种
 * 需要特殊处理的第三态：
 *   SAME_ENTITY : 父子模块的 primary_table 相同，是同一实体的另一套字段视图，
 *                 直接合并成同一行，不需要 JOIN。
 *   CHILD       : 真正的 1:N 关系，子模块在结果里表现为父行上的一个嵌套数组
 *                 （用 SQL 的 MULTISET 实现）。
 *
 * 模块内部（同一模块自己配置的字段所在的表）的连接永远是安全的 FLAT JOIN，
 * 由新规则保证，不需要在这里单独建模。
 */
public enum ModuleRelationKind {
    SAME_ENTITY,
    CHILD
}
