package com.example.schoolquery.querytree;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.ModuleRelationKind;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.plan.LogicalRelationResolver;
import com.example.schoolquery.plan.ResolvedRelationPlan;
import com.example.schoolquery.relation.RelationResolver;

import java.util.*;

/** Builds the physical query tree from logical modules. Relation semantics are resolved by module identity. */
public class QueryTreeBuilder {
    private final MetadataRegistry registry;
    private final RelationResolver resolver;
    private final LogicalRelationResolver logicalRelations;

    public QueryTreeBuilder(MetadataRegistry registry, RelationResolver resolver) {
        this.registry = registry;
        this.resolver = resolver;
        this.logicalRelations = new LogicalRelationResolver(registry);
    }

    public FlatGroup buildFromRoot(long rootModuleId, Set<Long> touchedModuleIds) {
        if (registry.module(rootModuleId).isVirtual()) throw new IllegalArgumentException("模块 " + rootModuleId + " 是虚拟模块，不能作为查询根节点");
        for (long mid : touchedModuleIds) if (!registry.ancestorChain(mid).contains(rootModuleId)) throw new IllegalArgumentException("字段所属模块 " + mid + " 不是根模块 " + rootModuleId + " 的子孙（或自身）");
        return buildGroup(rootModuleId, registry.module(rootModuleId), computeBackbone(rootModuleId, touchedModuleIds));
    }

    public FlatGroup buildAutoRoot(Set<Long> touchedModuleIds) {
        if (touchedModuleIds.isEmpty()) throw new IllegalArgumentException("字段列表为空，无法推断查询根节点");
        long lca = findLowestCommonAncestor(touchedModuleIds);
        long root = registry.module(lca).isVirtual() ? registry.nearestRealAncestor(lca).id() : lca;
        return buildGroup(root, registry.module(root), computeBackbone(root, touchedModuleIds));
    }

    private long findLowestCommonAncestor(Set<Long> moduleIds) {
        Iterator<Long> it = moduleIds.iterator();
        List<Long> firstChain = registry.ancestorChain(it.next());
        Set<Long> common = new LinkedHashSet<>(firstChain);
        while (it.hasNext()) common.retainAll(new HashSet<>(registry.ancestorChain(it.next())));
        if (common.isEmpty()) throw new IllegalStateException("被请求的模块之间没有公共祖先，无法组成一棵查询树");
        for (Long candidate : firstChain) if (common.contains(candidate)) return candidate;
        throw new IllegalStateException("unreachable");
    }

    private Set<Long> computeBackbone(long root, Set<Long> touchedModuleIds) {
        Set<Long> backbone = new LinkedHashSet<>(); backbone.add(root);
        for (long mid : touchedModuleIds) for (long node : registry.ancestorChain(mid)) { backbone.add(node); if (node == root) break; }
        return backbone;
    }

    private FlatGroup buildGroup(long startModuleId, SysModule anchor, Set<Long> backbone) {
        SysModule start = registry.module(startModuleId);
        List<Long> merged = new ArrayList<>(); List<NestedGroup> nested = new ArrayList<>();
        if (!start.isVirtual()) merged.add(start.id());
        processChildren(anchor, start, backbone, merged, nested);
        if (merged.isEmpty()) throw new IllegalStateException("模块组 " + startModuleId + " 没有真实物理模块");
        return new FlatGroup(start.isVirtual() ? registry.nearestRealAncestor(start.id()).primaryTable() : start.primaryTable(), merged, nested);
    }

    private void processChildren(SysModule anchor, SysModule current, Set<Long> backbone, List<Long> merged, List<NestedGroup> nested) {
        for (SysModule child : registry.children(current.id())) {
            if (!backbone.contains(child.id())) continue;
            if (child.isVirtual()) { processChildren(anchor, child, backbone, merged, nested); continue; }
            ModuleRelationKind kind = resolver.resolveParentChild(anchor, child);
            if (kind == ModuleRelationKind.SAME_ENTITY) {
                merged.add(child.id()); processChildren(child, child, backbone, merged, nested);
            } else {
                ResolvedRelationPlan rel = logicalRelations.resolve(anchor.id(), child.id());
                nested.add(new NestedGroup(child.id(), rel, buildGroup(child.id(), child, backbone)));
            }
        }
    }
}
