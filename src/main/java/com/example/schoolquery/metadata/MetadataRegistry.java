package com.example.schoolquery.metadata;

import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.model.SysTableRelation;

import java.util.*;
import java.util.stream.Collectors;

/** In-memory metadata index; semantic query decisions remain outside this class. */
public class MetadataRegistry {
    private final Map<Long, SysModule> modulesById = new LinkedHashMap<>();
    private final Map<String, SysModule> modulesByCode = new LinkedHashMap<>();
    private final Map<Long, List<SysModule>> childrenByParentId = new LinkedHashMap<>();
    private final Map<Long, List<SysModuleField>> fieldsByModuleId = new LinkedHashMap<>();
    private final Map<Long, SysModuleField> fieldsById = new LinkedHashMap<>();
    private final List<SysTableRelation> relations;

    public MetadataRegistry(List<SysModule> modules, List<SysModuleField> fields, List<SysTableRelation> relations) {
        for (SysModule m : modules) {
            if (modulesById.putIfAbsent(m.id(), m) != null) throw new MetadataValidationException("重复的 module id: " + m.id());
            if (m.moduleCode() != null && !m.moduleCode().isBlank() && modulesByCode.putIfAbsent(m.moduleCode(), m) != null)
                throw new MetadataValidationException("重复的 module code: " + m.moduleCode());
            childrenByParentId.computeIfAbsent(m.parentId(), k -> new ArrayList<>()).add(m);
        }
        for (SysModule m : modules) {
            if (!m.isRoot() && !modulesById.containsKey(m.parentId())) throw new MetadataValidationException("模块 " + m.id() + " 的 parent_id=" + m.parentId() + " 不存在");
        }
        for (SysModuleField f : fields) {
            SysModule owner = modulesById.get(f.moduleId());
            if (owner == null) throw new MetadataValidationException("field " + f.id() + " 引用了不存在的 module " + f.moduleId());
            if (owner.isVirtual()) throw new MetadataValidationException("field " + f.id() + " 不能挂在虚拟模块 " + f.moduleId() + " 下");
            fieldsByModuleId.computeIfAbsent(f.moduleId(), k -> new ArrayList<>()).add(f);
            if (fieldsById.putIfAbsent(f.id(), f) != null) throw new MetadataValidationException("重复的 sys_module_field id: " + f.id());
        }
        this.relations = List.copyOf(relations);
    }

    public SysModule module(long moduleId) {
        SysModule m = modulesById.get(moduleId);
        if (m == null) throw new IllegalArgumentException("Unknown module id: " + moduleId);
        return m;
    }
    public SysModule module(String idOrCode) {
        try { return module(Long.parseLong(idOrCode)); }
        catch (NumberFormatException ignored) {
            SysModule m = modulesByCode.get(idOrCode);
            if (m == null) throw new IllegalArgumentException("Unknown module: " + idOrCode);
            return m;
        }
    }
    public Collection<SysModule> allModules() { return modulesById.values(); }
    public SysModuleField field(long fieldId) {
        SysModuleField f = fieldsById.get(fieldId);
        if (f == null) throw new IllegalArgumentException("Unknown sys_module_field id: " + fieldId);
        return f;
    }
    public List<SysModule> children(long moduleId) { return childrenByParentId.getOrDefault(moduleId, List.of()); }
    public Map<String, List<SysModuleField>> fieldsGroupedByTable(long moduleId) {
        return fieldsByModuleId.getOrDefault(moduleId, List.of()).stream().sorted(Comparator.comparingInt(SysModuleField::sortOrder))
                .collect(Collectors.groupingBy(SysModuleField::tableName, LinkedHashMap::new, Collectors.toList()));
    }
    public List<Long> ancestorChain(long moduleId) {
        List<Long> chain = new ArrayList<>(); long current = moduleId; Set<Long> visited = new HashSet<>();
        while (true) {
            if (!visited.add(current)) throw new MetadataValidationException("模块树存在循环: " + current);
            chain.add(current); SysModule m = module(current); if (m.isRoot()) return chain; current = m.parentId();
        }
    }
    public SysModule nearestRealAncestor(long moduleId) {
        for (long id : ancestorChain(moduleId)) { SysModule m = module(id); if (!m.isVirtual()) return m; }
        throw new MetadataValidationException("模块 " + moduleId + " 没有真实物理祖先");
    }
    public Optional<SysTableRelation> findRelation(String tableA, String tableB) {
        List<SysTableRelation> matches = relations.stream().filter(r -> r.connects(tableA, tableB)).toList();
        if (matches.size() > 1) throw new MetadataValidationException("表 " + tableA + " 与 " + tableB + " 存在多条关系，无法推断");
        return matches.stream().findFirst();
    }
    public List<SysTableRelation> allRelations() { return relations; }
}
