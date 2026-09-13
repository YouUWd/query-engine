package com.example.schoolquery.relation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.metadata.MetadataValidationException;
import com.example.schoolquery.model.*;

import java.util.*;

/** Resolves physical relations only after logical module context has been established. */
public class RelationResolver {
    private final MetadataRegistry registry;

    public RelationResolver(MetadataRegistry registry) { this.registry = registry; }

    public void validateModule(SysModule module) {
        List<String> tables = registry.fieldsGroupedByTable(module.id()).keySet().stream().toList();
        for (String table : tables) {
            if (table.equals(module.primaryTable())) continue;
            SysTableRelation rel = relationOf(module.primaryTable(), table);
            boolean primaryIsMain = rel.mainTable().equals(module.primaryTable());
            if (primaryIsMain && rel.type() == RelationType.ONE_TO_MANY) {
                throw new MetadataValidationException("模块 " + module.id() + "(" + module.moduleName() + ") 里的表 " + table
                        + " 与主表 " + module.primaryTable() + " 是 1:N 关系，按新规则应拆分为独立子模块");
            }
        }
    }

    public void validateAllModules() {
        for (SysModule m : registry.allModules()) validateModule(m);
    }

    public ModuleRelationKind resolveParentChild(SysModule parent, SysModule child) {
        if (parent.primaryTable().equals(child.primaryTable())) return ModuleRelationKind.SAME_ENTITY;
        SysTableRelation rel = relationOf(parent.primaryTable(), child.primaryTable());
        if (rel.type() == RelationType.ONE_TO_ONE) {
            throw new MetadataValidationException("父模块 " + parent.id() + " 与子模块 " + child.id()
                    + " 是 1:1 关系，按新规则这种关系应该合并进同一个模块，而不是拆成父子模块");
        }
        return ModuleRelationKind.CHILD;
    }

    /**
     * Resolves the unique shortest physical relation path from the owning module's primary table
     * to a requested secondary table. Traversal is strictly limited to tables configured on that
     * module, so unrelated physical tables can never be introduced merely because a global
     * table graph happens to connect them.
     */
    public List<SysTableRelation> relationPathOfModule(long ownerModuleId, String otherTable) {
        SysModule owner = registry.module(ownerModuleId);
        if (owner.isVirtual()) throw new MetadataValidationException("虚拟模块 " + ownerModuleId + " 不能拥有物理表 JOIN");
        if (otherTable == null || otherTable.isBlank()) throw new IllegalArgumentException("关联表不能为空");
        if (owner.primaryTable().equals(otherTable)) throw new IllegalArgumentException("关联表 " + otherTable + " 与模块 " + ownerModuleId + " 的主表相同");

        Set<String> allowedTables = new LinkedHashSet<>(registry.fieldsGroupedByTable(ownerModuleId).keySet());
        allowedTables.add(owner.primaryTable());
        record State(String table, List<SysTableRelation> path) {}
        Deque<State> queue = new ArrayDeque<>();
        Map<String, Integer> distance = new HashMap<>();
        queue.add(new State(owner.primaryTable(), List.of()));
        distance.put(owner.primaryTable(), 0);
        State found = null;
        while (!queue.isEmpty()) {
            State current = queue.removeFirst();
            int nextDistance = current.path().size() + 1;
            for (SysTableRelation relation : registry.allRelations()) {
                String next = adjacentTable(relation, current.table());
                if (next == null || !allowedTables.contains(next)) continue;
                List<SysTableRelation> nextPath = new ArrayList<>(current.path());
                nextPath.add(relation);
                Integer known = distance.get(next);
                if (known == null) {
                    distance.put(next, nextDistance);
                    State state = new State(next, List.copyOf(nextPath));
                    if (next.equals(otherTable)) found = state;
                    queue.addLast(state);
                } else if (known == nextDistance && next.equals(otherTable)) {
                    throw new MetadataValidationException("模块 " + ownerModuleId + " 的主表 " + owner.primaryTable()
                            + " 到关联表 " + otherTable + " 存在多条等长物理关系路径，无法唯一确定 JOIN");
                }
            }
            if (found != null && current.path().size() >= found.path().size()) break;
        }
        if (found == null) {
            throw new MetadataValidationException("模块 " + ownerModuleId + " 的主表 " + owner.primaryTable()
                    + " 与关联表 " + otherTable + " 在该模块配置的表集合内没有唯一关系路径");
        }
        return found.path();
    }

    /** Compatibility API for direct physical relation callers. */
    public SysTableRelation relationOfModule(long ownerModuleId, String otherTable) {
        List<SysTableRelation> path = relationPathOfModule(ownerModuleId, otherTable);
        if (path.size() != 1) throw new MetadataValidationException("模块 " + ownerModuleId + " 的表 " + otherTable
                + " 不是主表的直接关联表，请使用 relationPathOfModule 获取完整 JOIN 路径");
        return path.get(0);
    }

    private String adjacentTable(SysTableRelation relation, String table) {
        if (relation.mainTable().equals(table)) return relation.joinTable();
        if (relation.joinTable().equals(table)) return relation.mainTable();
        return null;
    }

    /** Low-level physical metadata lookup; callers should already have module semantic context. */
    public SysTableRelation relationOf(String tableA, String tableB) {
        Optional<SysTableRelation> rel = registry.findRelation(tableA, tableB);
        return rel.orElseThrow(() -> new MetadataValidationException("表 " + tableA + " 与 " + tableB + " 之间没有配置 sys_table_relation 关系"));
    }
}
