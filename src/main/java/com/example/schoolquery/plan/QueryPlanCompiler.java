package com.example.schoolquery.plan;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.model.SysTableRelation;
import com.example.schoolquery.querytree.FlatGroup;
import com.example.schoolquery.querytree.NestedGroup;
import com.example.schoolquery.querytree.QueryTreeBuilder;
import com.example.schoolquery.relation.RelationResolver;

import java.util.*;

/**
 * Compiles the existing module query tree into an immutable semantic QueryPlan.
 *
 * This is intentionally an incremental compiler: QueryTreeBuilder remains the
 * compatibility layer for the current implementation, while downstream code
 * can start depending on QueryPlan instead of rediscovering module semantics.
 */
public final class QueryPlanCompiler {
    private final MetadataRegistry registry;
    private final QueryTreeBuilder treeBuilder;

    public QueryPlanCompiler(MetadataRegistry registry, RelationResolver resolver) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.treeBuilder = new QueryTreeBuilder(registry, Objects.requireNonNull(resolver, "resolver"));
    }

    /** Compile a query with an explicit root module. */
    public QueryPlan compile(long rootModuleId, Collection<Long> fieldIds) {
        Set<Long> ids = normalizeFieldIds(fieldIds);
        List<SysModuleField> fields = ids.stream().map(registry::field).toList();
        Set<Long> touchedModules = new LinkedHashSet<>();
        List<LogicalFieldRef> projections = new ArrayList<>();
        for (SysModuleField field : fields) {
            touchedModules.add(field.moduleId());
            projections.add(new LogicalFieldRef(field.moduleId(), field.id()));
        }

        FlatGroup tree = treeBuilder.buildFromRoot(rootModuleId, touchedModules);
        List<RelationPlan> relations = collectRelations(tree);
        return new QueryPlan(rootModuleId, projections, relations, List.of(), null, null);
    }

    /** Compile a query and infer its root from the requested fields. */
    public QueryPlan compile(Collection<Long> fieldIds) {
        Set<Long> ids = normalizeFieldIds(fieldIds);
        List<SysModuleField> fields = ids.stream().map(registry::field).toList();
        Set<Long> touchedModules = new LinkedHashSet<>();
        List<LogicalFieldRef> projections = new ArrayList<>();
        for (SysModuleField field : fields) {
            touchedModules.add(field.moduleId());
            projections.add(new LogicalFieldRef(field.moduleId(), field.id()));
        }

        FlatGroup tree = treeBuilder.buildAutoRoot(touchedModules);
        List<RelationPlan> relations = collectRelations(tree);
        return new QueryPlan(registry.nearestRealAncestor(treeRootModuleId(tree)).id(), projections,
                relations, List.of(), null, null);
    }

    private long treeRootModuleId(FlatGroup tree) {
        if (tree.mergedModuleIds().isEmpty()) {
            throw new IllegalStateException("QueryTree root has no module id");
        }
        return tree.mergedModuleIds().get(0);
    }

    private List<RelationPlan> collectRelations(FlatGroup group) {
        List<RelationPlan> result = new ArrayList<>();
        collectRelations(group, result);
        return List.copyOf(result);
    }

    private void collectRelations(FlatGroup group, List<RelationPlan> result) {
        for (NestedGroup nested : group.nestedChildren()) {
            SysTableRelation relation = nested.relation();
            if (relation != null) {
                boolean parentIsMain = relation.mainTable().equals(group.primaryTable());
                result.add(new RelationPlan(
                        group.mergedModuleIds().get(0),
                        nested.childModuleId(),
                        toPlanType(relation),
                        relation.mainTable(),
                        relation.mainField(),
                        relation.joinTable(),
                        relation.joinField()));
            }
            collectRelations(nested.group(), result);
        }
    }

    private RelationPlan.RelationType toPlanType(SysTableRelation relation) {
        return switch (relation.type()) {
            case ONE_TO_ONE -> RelationPlan.RelationType.ONE_TO_ONE;
            case ONE_TO_MANY -> RelationPlan.RelationType.ONE_TO_MANY;
            case MANY_TO_ONE -> RelationPlan.RelationType.MANY_TO_ONE;
        };
    }

    private Set<Long> normalizeFieldIds(Collection<Long> fieldIds) {
        if (fieldIds == null || fieldIds.isEmpty()) {
            throw new IllegalArgumentException("fieldIds must not be empty");
        }
        return new LinkedHashSet<>(fieldIds);
    }
}
