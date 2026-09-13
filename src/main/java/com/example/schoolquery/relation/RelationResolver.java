package com.example.schoolquery.relation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.metadata.MetadataValidationException;
import com.example.schoolquery.model.*;

import java.util.List;
import java.util.Optional;

/**
 * 新规则："同一模块内，主表与关联表只能是 1:1 / N:1；1:N 必须拆成子模块。"
 *
 * 这条规则落地后，两类关系的判断都变得非常简单：
 *   - 模块内部（primary_table 与该模块字段里出现的其它表）—— 永远应该是平铺 JOIN，
 *     如果不是，说明违反了新规则，直接在 validateModule() 里报错，而不是在查询期
 *     悄悄生成一个笛卡尔积。
 *   - 父子模块（sys_module.parent_id）—— 只能是 SAME_ENTITY（同一张 primary_table）
 *     或者 CHILD（真正的 1:N）。
 */
public class RelationResolver {

    private final MetadataRegistry registry;

    public RelationResolver(MetadataRegistry registry) {
        this.registry = registry;
    }

    /**
     * 校验单个模块是否遵守新规则：primary_table 与该模块字段里出现的每一张其它表，
     * 关系必须是 1:1 或 N:1（即 primaryTable 是 main_table 且 1:1，或 primaryTable
     * 是 join_table）。发现 1:N 就说明这张表配错了模块，应该拆成子模块。
     */
    public void validateModule(SysModule module) {
        List<String> tables = registry.fieldsGroupedByTable(module.id()).keySet().stream().toList();
        for (String table : tables) {
            if (table.equals(module.primaryTable())) continue;
            SysTableRelation rel = relationOf(module.primaryTable(), table);
            boolean primaryIsMain = rel.mainTable().equals(module.primaryTable());
            if (primaryIsMain && rel.type() == RelationType.ONE_TO_MANY) {
                throw new MetadataValidationException(
                        "模块 " + module.id() + "(" + module.moduleName() + ") 里的表 " + table +
                        " 与主表 " + module.primaryTable() + " 是 1:N 关系，按新规则应拆分为独立子模块");
            }
        }
    }

    /** 对 registry 里的每个模块跑一遍 validateModule，建议在应用启动时调用一次。 */
    public void validateAllModules() {
        for (SysModule m : registry.allModules()) {
            validateModule(m);
        }
    }

    /**
     * 推断父子模块之间的关系。按新规则，合法结果只有两种；FLAT(1:1) 理论上不应该
     * 出现在跨模块场景——如果出现了，说明有人把一个本该合并进同一模块的 1:1/N:1
     * 关联错误地拆成了独立子模块，直接报错提醒去修配置。
     */
    public ModuleRelationKind resolveParentChild(SysModule parent, SysModule child) {
        if (parent.primaryTable().equals(child.primaryTable())) {
            return ModuleRelationKind.SAME_ENTITY;
        }
        SysTableRelation rel = relationOf(parent.primaryTable(), child.primaryTable());
        if (rel.type() == RelationType.ONE_TO_ONE) {
            throw new MetadataValidationException(
                    "父模块 " + parent.id() + " 与子模块 " + child.id() + " 是 1:1 关系，" +
                    "按新规则这种关系应该合并进同一个模块，而不是拆成父子模块");
        }
        return ModuleRelationKind.CHILD;
    }

    /** 取出两张表之间的关系配置，供构建 JOIN/过滤条件时使用。找不到就是配置缺失，直接报错。 */
    public SysTableRelation relationOf(String tableA, String tableB) {
        Optional<SysTableRelation> rel = registry.findRelation(tableA, tableB);
        return rel.orElseThrow(() -> new MetadataValidationException(
                "表 " + tableA + " 与 " + tableB + " 之间没有配置 sys_table_relation 关系"));
    }
}
