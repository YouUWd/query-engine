package com.example.schoolquery.querytree;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.ModuleRelationKind;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.model.SysTableRelation;
import com.example.schoolquery.plan.LogicalRelationResolver;
import com.example.schoolquery.plan.ResolvedRelationPlan;
import com.example.schoolquery.relation.RelationResolver;
import java.util.*;

/** Builds the physical query tree from logical modules. Virtual modules remain structural nodes. */
public class QueryTreeBuilder {
    private final MetadataRegistry registry;
    private final RelationResolver resolver;
    private final LogicalRelationResolver logicalRelations;
    public QueryTreeBuilder(MetadataRegistry registry, RelationResolver resolver) { this.registry=registry; this.resolver=resolver; this.logicalRelations=new LogicalRelationResolver(registry); }
    public FlatGroup buildFromRoot(long rootModuleId, Set<Long> touchedModuleIds) {
        if (registry.module(rootModuleId).isVirtual()) throw new IllegalArgumentException("模块 " + rootModuleId + " 是虚拟模块，不能作为查询根节点");
        for (long mid:touchedModuleIds) if (!registry.ancestorChain(mid).contains(rootModuleId)) throw new IllegalArgumentException("字段所属模块 " + mid + " 不是根模块 " + rootModuleId + " 的子孙（或自身）");
        return buildGroup(rootModuleId,registry.module(rootModuleId),computeBackbone(rootModuleId,touchedModuleIds));
    }
    public FlatGroup buildAutoRoot(Set<Long> touchedModuleIds) {
        if (touchedModuleIds.isEmpty()) throw new IllegalArgumentException("字段列表为空，无法推断查询根节点");
        long lca=findLowestCommonAncestor(touchedModuleIds); long root=registry.module(lca).isVirtual()?registry.nearestRealAncestor(lca).id():lca;
        return buildGroup(root,registry.module(root),computeBackbone(root,touchedModuleIds));
    }

    /**
     * Resolves only the physical tables actually needed by the requested fields.
     * The returned tree is immutable and is the only input required by the SQL renderer.
     */
    public FlatGroup resolveTableJoins(FlatGroup group, Map<Long, List<SysModuleField>> requestedByModule) {
        if (group.isVirtual()) return group;
        LinkedHashMap<String, ResolvedTableJoinPlan> joins = new LinkedHashMap<>();
        for (long moduleId : group.mergedModuleIds()) {
            for (SysModuleField field : requestedByModule.getOrDefault(moduleId, List.of())) {
                if (group.primaryTable().equals(field.tableName())) continue;
                joins.computeIfAbsent(field.tableName(), table -> resolveTableJoin(group.primaryTable(), table));
            }
        }
        List<NestedGroup> children = new ArrayList<>();
        for (NestedGroup child : group.nestedChildren()) {
            children.add(new NestedGroup(child.childModuleId(), child.resolvedRelation(), resolveTableJoins(child.group(), requestedByModule)));
        }
        return new FlatGroup(group.primaryTable(), group.mergedModuleIds(), children, new ArrayList<>(joins.values()));
    }

    private ResolvedTableJoinPlan resolveTableJoin(String primaryTable, String otherTable) {
        if (resolver == null) throw new IllegalStateException("RelationResolver is required to resolve physical table joins");
        SysTableRelation rel = resolver.relationOf(primaryTable, otherTable);
        if (rel.mainTable().equals(primaryTable)) {
            return new ResolvedTableJoinPlan(primaryTable, otherTable, rel.mainField(), rel.joinField());
        }
        return new ResolvedTableJoinPlan(primaryTable, otherTable, rel.joinField(), rel.mainField());
    }

    private long findLowestCommonAncestor(Set<Long> ids) { Iterator<Long> it=ids.iterator(); List<Long> first=registry.ancestorChain(it.next()); Set<Long> common=new LinkedHashSet<>(first); while(it.hasNext()) common.retainAll(new HashSet<>(registry.ancestorChain(it.next()))); if(common.isEmpty()) throw new IllegalStateException("被请求的模块之间没有公共祖先，无法组成一棵查询树"); for(Long c:first) if(common.contains(c)) return c; throw new IllegalStateException("unreachable"); }
    private Set<Long> computeBackbone(long root,Set<Long> ids) { Set<Long> b=new LinkedHashSet<>(); b.add(root); for(long mid:ids) for(long node:registry.ancestorChain(mid)){b.add(node);if(node==root)break;} return b; }
    private FlatGroup buildGroup(long startModuleId,SysModule anchor,Set<Long> backbone) {
        SysModule start=registry.module(startModuleId); List<Long> merged=new ArrayList<>(); List<NestedGroup> nested=new ArrayList<>();
        if(!start.isVirtual()) merged.add(start.id());
        processChildren(start.isVirtual()?anchor:start,start,backbone,merged,nested);
        if(merged.isEmpty()&&!start.isVirtual()) throw new IllegalStateException("模块组 "+startModuleId+" 没有真实物理模块");
        return new FlatGroup(start.isVirtual()?null:start.primaryTable(),merged,nested);
    }
    private void processChildren(SysModule anchor,SysModule current,Set<Long> backbone,List<Long> merged,List<NestedGroup> nested) {
        for(SysModule child:registry.children(current.id())) {
            if(!backbone.contains(child.id())) continue;
            if(child.isVirtual()) {
                nested.add(new NestedGroup(child.id(),virtualRelation(anchor,child),buildGroup(child.id(),anchor,backbone)));
                continue;
            }
            ModuleRelationKind kind=resolver.resolveParentChild(anchor,child);
            if(kind==ModuleRelationKind.SAME_ENTITY&&!current.isVirtual()) { merged.add(child.id()); processChildren(child,child,backbone,merged,nested); }
            else { ResolvedRelationPlan rel=logicalRelations.resolve(anchor.id(),child.id()); nested.add(new NestedGroup(child.id(),rel,buildGroup(child.id(),child,backbone))); }
        }
    }
    private ResolvedRelationPlan virtualRelation(SysModule anchor,SysModule virtual) {
        return new ResolvedRelationPlan(anchor.id(),virtual.id(),com.example.schoolquery.plan.RelationPlan.RelationType.ONE_TO_ONE,anchor.primaryTable(),"",anchor.primaryTable(),"");
    }
}
