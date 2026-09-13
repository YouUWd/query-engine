package com.example.schoolquery.header;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.ModuleRelationKind;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.query.HeaderNode;
import com.example.schoolquery.relation.RelationResolver;

import java.util.*;

/**
 * 构建表头树，形状跟着 sys_module 的模块层级走，而不是跟着 FlatGroup 的数据结构走——
 * 两者的关键区别是：虚拟模块在 FlatGroup 里完全不出现，但在表头里应该保留（作为一个
 * 只有 label、没有对应字段的分组节点），因为表头是给前端渲染导航/分组用的，
 * 虚拟模块存在的意义就是提供一层分组。
 *
 * SAME_ENTITY 的模块（和父模块共享同一张 primary_table）不单独占一层，字段直接
 * 平铺进父节点的 children——跟 FlatGroup 里"合并成同一行"是同一个道理，只是这里
 * 合并的是 children 列表而不是 SQL 列。
 */
public class HeaderTreeBuilder {

    private final MetadataRegistry registry;
    private final RelationResolver resolver;

    public HeaderTreeBuilder(MetadataRegistry registry, RelationResolver resolver) {
        this.registry = registry;
        this.resolver = resolver;
    }

    public HeaderNode build(long rootModuleId, Map<Long, List<SysModuleField>> requestedByModule) {
        SysModule root = registry.module(rootModuleId);
        Set<Long> backbone = computeBackbone(rootModuleId, requestedByModule.keySet());
        List<HeaderNode> children = buildChildren(root, root, backbone, requestedByModule);
        return HeaderNode.module(root.id(), root.moduleName(), children);
    }

    private List<HeaderNode> buildChildren(SysModule headerParent, SysModule anchor, Set<Long> backbone,
                                            Map<Long, List<SysModuleField>> requestedByModule) {
        List<HeaderNode> result = new ArrayList<>();

        if (!headerParent.isVirtual()) {
            for (SysModuleField f : requestedByModule.getOrDefault(headerParent.id(), List.of())) {
                result.add(HeaderNode.leaf(f.id(), f.displayName(), f.tableName() + "." + f.columnName()));
            }
        }

        for (SysModule child : registry.children(headerParent.id())) {
            if (!backbone.contains(child.id())) continue;

            if (child.isVirtual()) {
                // 虚拟模块自己占一层分组节点，但不参与表关联判断，anchor 保持不变继续往下传
                result.add(HeaderNode.module(child.id(), child.moduleName(),
                        buildChildren(child, anchor, backbone, requestedByModule)));
                continue;
            }

            ModuleRelationKind kind = resolver.resolveParentChild(anchor, child);
            if (kind == ModuleRelationKind.SAME_ENTITY) {
                // 同一张表的另一套字段——直接摊平进当前这一层，不新开节点
                result.addAll(buildChildren(child, child, backbone, requestedByModule));
            } else {
                result.add(HeaderNode.module(child.id(), child.moduleName(),
                        buildChildren(child, child, backbone, requestedByModule)));
            }
        }

        return result;
    }

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
}
