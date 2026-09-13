package com.example.schoolquery.querytree;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.ModuleRelationKind;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysTableRelation;
import com.example.schoolquery.relation.RelationResolver;

import java.util.*;

/**
 * 公用查询树构建方法。
 *
 * 给定一批“被请求字段”所属的 module_id 集合，找出连接它们的最小模块子树
 * （根节点到每个被请求模块路径的并集），再把子树上每条父子边分类成
 * SAME_ENTITY（合并成同一行）或 CHILD（1:N，MULTISET 嵌套），最终产出一棵
 * {@link FlatGroup} 树。
 *
 * 两个查询入口（{@code buildFromRoot} 对应“指定 moduleId”，{@code buildAutoRoot}
 * 对应“不指定 moduleId”）只是在“根节点怎么定”这一点上不同，其余逻辑完全复用。
 *
 * 虚拟模块（{@link SysModule#isVirtual()}）不对应物理表，不会出现在产出的
 * {@link FlatGroup} 树里——遍历到虚拟模块时直接透传过去，用它最近的真实祖先
 * 继续做表关联判断，虚拟模块本身既不算进 mergedModuleIds，也不会单独产生一层嵌套。
 */
public class QueryTreeBuilder {

    private final MetadataRegistry registry;
    private final RelationResolver resolver;

    public QueryTreeBuilder(MetadataRegistry registry, RelationResolver resolver) {
        this.registry = registry;
        this.resolver = resolver;
    }

    /**
     * 显式指定根模块。如果某个被请求字段所属的模块不在 rootModuleId 的子树内，
     * 直接报错——这通常意味着调用方传错了 moduleId。根模块本身不能是虚拟模块
     * （虚拟模块没有物理表，没法作为查询的驱动表）。
     */
    public FlatGroup buildFromRoot(long rootModuleId, Set<Long> touchedModuleIds) {
        if (registry.module(rootModuleId).isVirtual()) {
            throw new IllegalArgumentException("模块 " + rootModuleId + " 是虚拟模块，不能作为查询根节点");
        }
        for (long mid : touchedModuleIds) {
            if (!registry.ancestorChain(mid).contains(rootModuleId)) {
                throw new IllegalArgumentException(
                        "字段所属模块 " + mid + " 不是根模块 " + rootModuleId + " 的子孙（或自身）");
            }
        }
        Set<Long> backbone = computeBackbone(rootModuleId, touchedModuleIds);
        SysModule rootModule = registry.module(rootModuleId);
        return buildGroup(rootModuleId, rootModule, backbone);
    }

    /**
     * 不指定根模块，自动取 touchedModuleIds 在模块树里的最近公共祖先作为根。
     * 如果算出来的最近公共祖先恰好是个虚拟模块，再往上找最近的真实祖先顶上——
     * 查询必须从一张真实的表出发。
     */
    public FlatGroup buildAutoRoot(Set<Long> touchedModuleIds) {
        if (touchedModuleIds.isEmpty()) {
            throw new IllegalArgumentException("字段列表为空，无法推断查询根节点");
        }
        long lca = findLowestCommonAncestor(touchedModuleIds);
        long root = registry.module(lca).isVirtual() ? registry.nearestRealAncestor(lca).id() : lca;
        Set<Long> backbone = computeBackbone(root, touchedModuleIds);
        SysModule rootModule = registry.module(root);
        return buildGroup(root, rootModule, backbone);
    }

    // --- 内部实现 ---

    private long findLowestCommonAncestor(Set<Long> moduleIds) {
        Iterator<Long> it = moduleIds.iterator();
        List<Long> firstChain = registry.ancestorChain(it.next()); // [自身, 父, ..., 根]
        Set<Long> common = new LinkedHashSet<>(firstChain);
        while (it.hasNext()) {
            common.retainAll(new HashSet<>(registry.ancestorChain(it.next())));
        }
        if (common.isEmpty()) {
            throw new IllegalStateException("被请求的模块之间没有公共祖先，无法组成一棵查询树");
        }
        for (Long candidate : firstChain) {
            if (common.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("unreachable");
    }

    /** root 到每个被请求模块路径上所有模块 id 的并集，即“查询树骨架”（可能包含虚拟模块）。 */
    private Set<Long> computeBackbone(long root, Set<Long> touchedModuleIds) {
        Set<Long> backbone = new LinkedHashSet<>();
        backbone.add(root);
        for (long mid : touchedModuleIds) {
            for (long node : registry.ancestorChain(mid)) {
                backbone.add(node);
                if (node == root) break;
            }
        }
        return backbone;
    }

    private FlatGroup buildGroup(long startModuleId, SysModule anchor, Set<Long> backbone) {
        SysModule start = registry.module(startModuleId);
        List<Long> mergedModuleIds = new ArrayList<>();
        List<NestedGroup> nestedChildren = new ArrayList<>();
        mergedModuleIds.add(start.id());
        processChildren(anchor, start, backbone, mergedModuleIds, nestedChildren);
        return new FlatGroup(start.primaryTable(), mergedModuleIds, nestedChildren);
    }

    /**
     * @param anchor  用于关系判断的最近真实祖先（真实表关联永远相对它计算）
     * @param current 当前遍历到的模块
     */
    private void processChildren(SysModule anchor, SysModule current, Set<Long> backbone,
                                  List<Long> mergedModuleIds, List<NestedGroup> nestedChildren) {
        for (SysModule child : registry.children(current.id())) {
            if (!backbone.contains(child.id())) continue; // 不在骨架里的子模块，本次查询不需要

            if (child.isVirtual()) {
                // 虚拟模块也是正常模块组节点，但没有物理表；其内部的表关联依然相对 anchor 计算
                FlatGroup virtualGroup = buildGroup(child.id(), anchor, backbone);
                nestedChildren.add(new NestedGroup(child.id(), null, virtualGroup));
                continue;
            }

            ModuleRelationKind kind = resolver.resolveParentChild(anchor, child);
            if (kind == ModuleRelationKind.SAME_ENTITY) {
                mergedModuleIds.add(child.id());
                processChildren(child, child, backbone, mergedModuleIds, nestedChildren);
            } else {
                SysTableRelation rel = resolver.relationOf(anchor.primaryTable(), child.primaryTable());
                nestedChildren.add(new NestedGroup(child.id(), rel, buildGroup(child.id(), child, backbone)));
            }
        }
    }
}
