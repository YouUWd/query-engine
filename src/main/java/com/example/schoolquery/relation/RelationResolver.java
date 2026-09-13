package com.example.schoolquery.relation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.metadata.MetadataValidationException;
import com.example.schoolquery.model.*;

import java.util.*;

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

    public void validateAllModules() {
        for (SysModule m : registry.allModules()) {
            validateModule(m);
        }
    }

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
     * 调用方必须先由模块树确定 ownerModule；这里不接受两个裸表名作为唯一语义入口。
     *
     * <p>当目标表不是 primary_table 的直接关系时，返回从模块主表到目标表的唯一最短
     * 物理关系路径。路径上的每一条边都必须来自 sys_table_relation；若存在多条等长
     * 最短路径，则认为元数据不足以唯一确定 JOIN，直接失败。</p>
     */
    public List<SysTableRelation> relationPathOfModule(long ownerModuleId, String otherTable) {
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

        record State(String table, List<SysTableRelation> path) {}
        Deque<State> queue = new ArrayDeque<>();
        Map<String, Integer> distance = new HashMap<>();
        Map<String, Integer> shortestPathCount = new HashMap<>();
        queue.add(new State(owner.primaryTable(), List.of()));
        distance.put(owner.primaryTable(), 0);
        shortestPathCount.put(owner.primaryTable(), 1);
        State found = null;
        while (!queue.isEmpty()) {
            State current = queue.removeFirst();
            int nextDistance = current.path().size() + 1;
            for (SysTableRelation relation : registry.allRelations()) {
                String next = adjacentTable(relation, current.table());
                if (next == null) continue;
                List<SysTableRelation> nextPath = new ArrayList<>(current.path());
                nextPath.add(relation);
                Integer known = distance.get(next);
                if (known == null) {
                    distance.put(next, nextDistance);
                    shortestPathCount.put(next, shortestPathCount.getOrDefault(current.table(), 1));
                    State state = new State(next, List.copyOf(nextPath));
                    if (next.equals(otherTable)) found = state;
                    queue.addLast(state);
                } else if (known == nextDistance) {
                    shortestPathCount.merge(next, shortestPathCount.getOrDefault(current.table(), 1), Integer::sum);
                    if (next.equals(otherTable)) {
                        throw new MetadataValidationException("模块 " + ownerModuleId + " 的主表 "
                                + owner.primaryTable() + " 到关联表 " + otherTable
                                + " 存在多条等长物理关系路径，无法唯一确定 JOIN");
                    }
                }
            }
            if (found != null && current.path().size() + 1 > found.path().size()) break;
        }
        if (found == null) {
            throw new MetadataValidationException("模块 " + ownerModuleId + " 的主表 "
                    + owner.primaryTable() + " 与关联表 " + otherTable + " 之间没有可用关系路径");
        }
        return found.path();
    }

    /** Compatibility API for callers that only need a direct relation. */
    public SysTableRelation relationOfModule(long ownerModuleId, String otherTable) {
        List<SysTableRelation> path = relationPathOfModule(ownerModuleId, otherTable);
        if (path.size() != 1) {
            throw new MetadataValidationException("模块 " + ownerModuleId + " 的表 " + otherTable
                    + " 不是主表的直接关联表，请使用 relationPathOfModule 获取完整 JOIN 路径");
        }
        return path.get(0);
    }

    private String adjacentTable(SysTableRelation relation, String table) {
        if (relation.mainTable().equals(table)) return relation.joinTable();
        if (relation.joinTable().equals(table)) return relation.mainTable();
        return null;
    }

    /** 取出两张表之间的底层直接关系。调用方应已完成模块语义解析。 */
    public SysTableRelation relationOf(String tableA, String tableB) {
        Optional<SysTableRelation> rel = registry.findRelation(tableA, tableB);
        return rel.orElseThrow(() -> new MetadataValidationException(
                "表 " + tableA + " 与 " + tableB + " 之间没有配置 sys_table_relation 关系"));
    }
}
