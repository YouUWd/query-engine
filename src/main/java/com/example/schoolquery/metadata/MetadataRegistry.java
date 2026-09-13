package com.example.schoolquery.metadata;

import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.model.SysTableRelation;

import java.util.*;
import java.util.stream.Collectors;

/**
 * sys_module / sys_module_field / sys_table_relation 三张配置表在内存里的索引。
 * 只负责“存 + 查”，不做连接策略推断（那是 RelationResolver 的职责）。
 */
public class MetadataRegistry {

    private final Map<Long, SysModule> modulesById = new LinkedHashMap<>();
    private final Map<Long, List<SysModule>> childrenByParentId = new LinkedHashMap<>();
    private final Map<Long, List<SysModuleField>> fieldsByModuleId = new LinkedHashMap<>();
    private final Map<Long, SysModuleField> fieldsById = new LinkedHashMap<>();
    private final List<SysTableRelation> relations;

    public MetadataRegistry(List<SysModule> modules, List<SysModuleField> fields, List<SysTableRelation> relations) {
        for (SysModule m : modules) {
            if (modulesById.putIfAbsent(m.id(), m) != null) {
                throw new MetadataValidationException("重复的 module id: " + m.id());
            }
            childrenByParentId.computeIfAbsent(m.parentId(), k -> new ArrayList<>()).add(m);
        }
        for (SysModule m : modules) {
            if (!m.isRoot() && !modulesById.containsKey(m.parentId())) {
                throw new MetadataValidationException(
                        "模块 " + m.id() + " 的 parent_id=" + m.parentId() + " 在 sys_module 中不存在");
            }
        }
        for (SysModuleField f : fields) {
            if (!modulesById.containsKey(f.moduleId())) {
                throw new MetadataValidationException(
                        "sys_module_field.id=" + f.id() + " 引用了不存在的 module_id=" + f.moduleId());
            }
            if (modulesById.get(f.moduleId()).isVirtual()) {
                throw new MetadataValidationException(
                        "sys_module_field.id=" + f.id() + " 挂在虚拟模块 " + f.moduleId() + " 下面——" +
                        "虚拟模块不对应物理表，不应该配置字段");
            }
            fieldsByModuleId.computeIfAbsent(f.moduleId(), k -> new ArrayList<>()).add(f);
            if (fieldsById.putIfAbsent(f.id(), f) != null) {
                throw new MetadataValidationException("重复的 sys_module_field id: " + f.id());
            }
        }
        this.relations = List.copyOf(relations);
    }

    public SysModule module(long moduleId) {
        SysModule m = modulesById.get(moduleId);
        if (m == null) throw new IllegalArgumentException("Unknown module id: " + moduleId);
        return m;
    }

    public Collection<SysModule> allModules() {
        return modulesById.values();
    }

    /** 按 sys_module_field.id 查字段配置——字段驱动查询的输入就是这个 id 的列表。 */
    public SysModuleField field(long fieldId) {
        SysModuleField f = fieldsById.get(fieldId);
        if (f == null) throw new IllegalArgumentException("Unknown sys_module_field id: " + fieldId);
        return f;
    }

    public List<SysModule> children(long moduleId) {
        return childrenByParentId.getOrDefault(moduleId, List.of());
    }

    /** 某模块下按 table_name 分组的字段列表，保留 sort_order。 */
    public Map<String, List<SysModuleField>> fieldsGroupedByTable(long moduleId) {
        return fieldsByModuleId.getOrDefault(moduleId, List.of()).stream()
                .sorted(Comparator.comparingInt(SysModuleField::sortOrder))
                .collect(Collectors.groupingBy(SysModuleField::tableName, LinkedHashMap::new, Collectors.toList()));
    }

    /** 沿 parent_id 从 moduleId 向上到根模块（含自身，含根）：[moduleId, 父模块, ..., 根模块]。 */
    public List<Long> ancestorChain(long moduleId) {
        List<Long> chain = new ArrayList<>();
        long current = moduleId;
        chain.add(current);
        while (!module(current).isRoot()) {
            current = module(current).parentId();
            chain.add(current);
        }
        return chain;
    }

    /**
     * 找到 moduleId 自己或它最近的真实（非虚拟）祖先。虚拟模块不对应物理表，
     * 不能作为查询的驱动表，凡是需要"这个模块对应哪张表"的场景都应该先过一遍这个方法。
     */
    public SysModule nearestRealAncestor(long moduleId) {
        for (long id : ancestorChain(moduleId)) {
            SysModule m = module(id);
            if (!m.isVirtual()) return m;
        }
        throw new MetadataValidationException(
                "模块 " + moduleId + " 一路到根模块都是虚拟模块，没有可用的物理表");
    }

    /** 查找 tableA 与 tableB 之间的关系配置（不关心谁是 main）。要求每对表最多一条关系记录。 */
    public Optional<SysTableRelation> findRelation(String tableA, String tableB) {
        List<SysTableRelation> matches = relations.stream()
                .filter(r -> r.connects(tableA, tableB))
                .toList();
        if (matches.size() > 1) {
            throw new MetadataValidationException(
                    "表 " + tableA + " 与 " + tableB + " 之间存在 " + matches.size() + " 条关系记录，配置有歧义");
        }
        return matches.stream().findFirst();
    }

    public List<SysTableRelation> allRelations() {
        return relations;
    }
}
