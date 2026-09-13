package com.example.schoolquery.relation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.metadata.MetadataValidationException;
import com.example.schoolquery.model.*;

import java.util.List;
import java.util.Optional;

/**
 * 新规则："同一模块内，主表与关联表只能是 1:1 / N:1；1:N 必须拆成子模块。"
 *
 * <p>物理表关系本身只是底层元数据。查询语义必须先确定逻辑模块及模块树上下文，
 * 再使用该模块的 primary_table 解释物理关系；不能仅凭 tableA/tableB 推断一个
 * 查询 JOIN 的业务语义。</p>
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
     * 关联错误地拆成了父子模块，直接报错提醒去修配置。
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

    /**
     * 按“模块上下文 + 目标物理表”解析模块内部关系。
     *
     * <p>调用方必须先由模块树确定 ownerModule；这里不接受两个裸表名作为唯一语义入口。
     * 返回的仍然是底层物理关系，具体方向由上层 resolved plan 按模块上下文确定。</p>
     */
    public SysTableRelation relationOfModule(long ownerModuleId, String otherTable) {
        SysModule owner = registry.module(ownerModuleId);
        if (owner.isVirtual()) {
            throw new MetadataValidationException("虚拟模块 " + ownerModuleId + " 不能拥有物理表 JOIN");
        }
        if (otherTable == null || otherTable.isBlank()) {
            throw new IllegalArgumentException("关联表不能为空");
        }
        if (owner.primaryTable().equals(otherTable)) {
            throw new IllegalArgumentException("关联表 " + otherTable + " 与模块 " + ownerModuleId + " 的主表相同");
        }
        return relationOf(owner.primaryTable(), otherTable);
    }

    /** 取出两张表之间的底层关系配置。调用方应已完成模块语义解析。 */
    public SysTableRelation relationOf(String tableA, String tableB) {
        Optional<SysTableRelation> rel = registry.findRelation(tableA, tableB);
        return rel.orElseThrow(() -> new MetadataValidationException(
                "表 " + tableA + " 与 " + tableB + " 之间没有配置 sys_table_relation 关系"));
    }
}
