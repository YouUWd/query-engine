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

    public QueryTreeBuilder(MetadataRegistry registry, RelationResolver resolver) {
        this.registry = registry;
        this.resolver = resolver;
        this.logicalRelations = new LogicalRelationResolver(registry);
    }

    public FlatGroup buildFromRoot(long rootModuleId, Set<Long> touchedModuleIds) {
        if (registry.module(rootModuleId).isVirtual()) {
            throw new IllegalArgumentException("模块 " + rootModuleId + " 是虚拟模块，不能作为查询根节点");
        }
        for (long mid : touchedModuleIds) {
            if (!registry.ancestorChain(mid).contains(rootModuleId)) {
                throw new IllegalArgumentException("字段所属模块 " + mid + " 不是根模块 " + rootModuleId + " 的子孙（或自身）");
            }
        }
        return buildGroup(rootModuleId, registry.module(rootModuleId), computeBackbone(rootModuleId, touchedModuleIds));
    }

    /** Builds the logical module tree and then resolves physical joins using requested fields. */
    public FlatGroup buildResolvedFromRoot(long rootModuleId,
                                           Map<Long, List<SysModuleField>> requestedByModule) {
        Objects.requireNonNull(requestedByModule, "requestedByModule");
        FlatGroup tree = buildFromRoot(rootModuleId, requestedByModule.keySet());
        return resolveTableJoins(tree, requestedByModule);
    }

    public FlatGroup buildAutoRoot(Set<Long> touchedModuleIds) {
        if (touchedModuleIds.isEmpty()) throw new IllegalArgumentException("字段列表为空，无法推断查询根节点");
        long lca = findLowestCommonAncestor(touchedModuleIds);
        long root = registry.module(lca).isVirtual() ? registry.nearestRealAncestor(lca).id() : lca;
        return buildGroup(root, registry.module(root), computeBackbone(root, touchedModuleIds));
    }

    public FlatGroup buildResolvedAutoRoot(Map<Long, List<SysModuleField>> requestedByModule) {
        Objects.requireNonNull(requestedByModule, "requestedByModule");
        FlatGroup tree = buildAutoRoot(requestedByModule.keySet());
        return resolveTableJoins(tree, requestedByModule);
    }

    /**
     * Resolves physical table joins in the context of the already-built module tree.
     * A table pair is not the identity of a join; the owning module and its path are.
     */
    public FlatGroup resolveTableJoins(FlatGroup group, Map<Long, List<SysModuleField>> requestedByModule) {
        if (group.isVirtual()) return group;

        LinkedHashMap<String, ResolvedTableJoinPlan> joinsByTable = new LinkedHashMap<>();
        for (long moduleId : group.mergedModuleIds()) {
            SysModule module = registry.module(moduleId);
            String modulePrimaryTable = module.primaryTable();
            if (!group.primaryTable().equals(modulePrimaryTable)) {
                throw new IllegalStateException("模块组 " + group.mergedModuleIds()
                        + " 中模块 " + moduleId + " 的主表 " + modulePrimaryTable
                        + " 与组主表 " + group.primaryTable() + " 不一致");
            }

            for (SysModuleField field : requestedByModule.getOrDefault(moduleId, List.of())) {
                if (modulePrimaryTable.equals(field.tableName())) continue;

                ResolvedTableJoinPlan candidate = resolveTableJoin(moduleId, modulePrimaryTable, field.tableName());
                ResolvedTableJoinPlan previous = joinsByTable.putIfAbsent(field.tableName(), candidate);
                if (previous != null && !sameJoin(previous, candidate)) {
                    throw new IllegalStateException("模块树下物理表 JOIN 不自洽：模块 "
                            + previous.ownerModuleId() + " 与模块 " + candidate.ownerModuleId()
                            + " 都需要连接表 " + field.tableName()
                            + "，但连接上下文或连接键不同（" + previous.resolvedModulePath()
                            + ": " + previous.primaryColumn() + "=" + previous.otherColumn()
                            + " vs " + candidate.resolvedModulePath() + ": "
                            + candidate.primaryColumn() + "=" + candidate.otherColumn() + "）");
                }
            }
        }

        List<NestedGroup> children = new ArrayList<>();
        for (NestedGroup child : group.nestedChildren()) {
            children.add(new NestedGroup(
                    child.childModuleId(),
                    child.resolvedRelation(),
                    resolveTableJoins(child.group(), requestedByModule)));
        }
        return new FlatGroup(group.primaryTable(), group.mergedModuleIds(), children, new ArrayList<>(joinsByTable.values()));
    }

    /** Resolve a table relation only after the owning module has been established by the module tree. */
    private ResolvedTableJoinPlan resolveTableJoin(long moduleId, String primaryTable, String otherTable) {
        if (resolver == null) throw new IllegalStateException("RelationResolver is required to resolve physical table joins");
        SysTableRelation rel = resolver.relationOf(primaryTable, otherTable);
        List<Long> modulePath = new ArrayList<>(registry.ancestorChain(moduleId));
        Collections.reverse(modulePath);
        if (rel.mainTable().equals(primaryTable)) {
            return new ResolvedTableJoinPlan(moduleId, modulePath, primaryTable, otherTable,
                    rel.mainField(), rel.joinField());
        }
        return new ResolvedTableJoinPlan(moduleId, modulePath, primaryTable, otherTable,
                rel.joinField(), rel.mainField());
    }

    private boolean sameJoin(ResolvedTableJoinPlan left, ResolvedTableJoinPlan right) {
        return left.ownerModuleId() == right.ownerModuleId()
                && left.resolvedModulePath().equals(right.resolvedModulePath())
                && left.primaryTable().equals(right.primaryTable())
                && left.otherTable().equals(right.otherTable())
                && left.primaryColumn().equals(right.primaryColumn())
                && left.otherColumn().equals(right.otherColumn());
    }

    private long findLowestCommonAncestor(Set<Long> ids) {
        Iterator<Long> it = ids.iterator();
        List<Long> first = registry.ancestorChain(it.next());
        Set<Long> common = new LinkedHashSet<>(first);
        while (it.hasNext()) common.retainAll(new HashSet<>(registry.ancestorChain(it.next())));
        if (common.isEmpty()) throw new IllegalStateException("被请求的模块之间没有公共祖先，无法组成一棵查询树");
        for (Long c : first) if (common.contains(c)) return c;
        throw new IllegalStateException("unreachable");
    }

    private Set<Long> computeBackbone(long root, Set<Long> ids) {
        Set<Long> b = new LinkedHashSet<>();
        b.add(root);
        for (long mid : ids) {
            for (long node : registry.ancestorChain(mid)) {
                b.add(node);
                if (node == root) break;
            }
        }
        return b;
    }

    private FlatGroup buildGroup(long startModuleId, SysModule anchor, Set<Long> backbone) {
        SysModule start = registry.module(startModuleId);
        List<Long> merged = new ArrayList<>();
        List<NestedGroup> nested = new ArrayList<>();
        if (!start.isVirtual()) merged.add(start.id());
        processChildren(start.isVirtual() ? anchor : start, start, backbone, merged, nested);
        if (merged.isEmpty() && !start.isVirtual()) {
            throw new IllegalStateException("模块组 " + startModuleId + " 没有真实物理模块");
        }
        return new FlatGroup(start.isVirtual() ? null : start.primaryTable(), merged, nested);
    }

    private void processChildren(SysModule anchor, SysModule current, Set<Long> backbone,
                                 List<Long> merged, List<NestedGroup> nested) {
        for (SysModule child : registry.children(current.id())) {
            if (!backbone.contains(child.id())) continue;
            if (child.isVirtual()) {
                nested.add(new NestedGroup(child.id(), virtualRelation(anchor, child), buildGroup(child.id(), anchor, backbone)));
                continue;
            }
            ModuleRelationKind kind = resolver.resolveParentChild(anchor, child);
            if (kind == ModuleRelationKind.SAME_ENTITY && !current.isVirtual()) {
                merged.add(child.id());
                processChildren(child, child, backbone, merged, nested);
            } else {
                ResolvedRelationPlan rel = logicalRelations.resolve(anchor.id(), child.id());
                nested.add(new NestedGroup(child.id(), rel, buildGroup(child.id(), child, backbone)));
            }
        }
    }

    private ResolvedRelationPlan virtualRelation(SysModule anchor, SysModule virtual) {
        return new ResolvedRelationPlan(anchor.id(), virtual.id(),
                com.example.schoolquery.plan.RelationPlan.RelationType.ONE_TO_ONE,
                anchor.primaryTable(), "", anchor.primaryTable(), "");
    }
}
