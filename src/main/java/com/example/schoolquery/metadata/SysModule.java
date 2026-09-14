package com.example.schoolquery.metadata;

/**
 * 对应 sys_module 表的一行配置。
 *
 * @param primaryTable 主表名；虚拟模块（纯分组用，不对应任何物理表）传 null 或空字符串。
 *                     虚拟模块自己不出现在查询结果结构里，它的子模块通过它的最近真实祖先
 *                     继续做表关联（见 MetadataRegistry.nearestRealAncestor）。
 * @param parentId 父模块 id；0 表示根模块（sys_module.csv 里用 0 表示无父级）
 */
public record SysModule(
        long id,
        String moduleCode,
        String moduleName,
        String primaryTable,
        long parentId
) {
    public boolean isRoot() {
        return parentId == 0L;
    }

    public boolean isVirtual() {
        return primaryTable == null || primaryTable.isBlank();
    }
}
