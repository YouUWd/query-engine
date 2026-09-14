package com.example.schoolquery.service;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.metadata.SysModuleField;
import com.example.schoolquery.permission.PermissionContext;
import com.example.schoolquery.permission.PermissionRegistry;
import com.example.schoolquery.query.resolver.FlatGroup;
import com.example.schoolquery.query.resolver.QueryTreeBuilder;
import com.example.schoolquery.query.resolver.RelationResolver;
import com.example.schoolquery.query.renderer.FlatGroupSqlBuilder;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SelectConditionStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诉求1 {@link #queryByModuleAndFields}：指定 moduleId，字段可能来自该模块自身或其子孙模块。
 * 诉求2 {@link #queryByFieldsOnly}     ：不指定 moduleId，自动推断查询树的根。
 *
 * 每个入口都有一个带 {@link PermissionContext} 参数的重载——不需要权限功能的调用方
 * 用不带该参数的老签名即可，行为完全不变。
 */
public class FieldDrivenQueryService {

    private final MetadataRegistry registry;
    private final QueryTreeBuilder treeBuilder;
    private final FlatGroupSqlBuilder sqlBuilder;

    public FieldDrivenQueryService(MetadataRegistry registry, RelationResolver resolver) {
        this(registry, resolver, new PermissionRegistry(List.of()));
    }

    public FieldDrivenQueryService(MetadataRegistry registry, RelationResolver resolver,
                                    PermissionRegistry permissionRegistry) {
        this.registry = registry;
        this.treeBuilder = new QueryTreeBuilder(registry, resolver);
        this.sqlBuilder = new FlatGroupSqlBuilder(resolver, permissionRegistry);
    }

    /** 诉求1：指定 moduleId + 字段列表（可能来自当前模块及子孙模块），不做权限过滤。 */
    public Result<Record> queryByModuleAndFields(DSLContext dsl, long moduleId,
                                                  List<Long> fieldIds, Condition rootCondition) {
        return queryByModuleAndFields(dsl, moduleId, fieldIds, rootCondition, null);
    }

    /** 诉求1 + 权限：字段级过滤 SELECT 列表，行级给每个驱动表叠加数据范围条件。 */
    public Result<Record> queryByModuleAndFields(DSLContext dsl, long moduleId, List<Long> fieldIds,
                                                  Condition rootCondition, PermissionContext permissionContext) {
        Map<Long, List<SysModuleField>> requested = resolveByModule(fieldIds);
        FlatGroup tree = treeBuilder.buildFromRoot(moduleId, requested.keySet());
        return buildSelect(dsl, requested, tree, rootCondition, permissionContext).fetch();
    }

    /** 诉求2：不指定 moduleId，仅凭字段列表推断查询树的根，不做权限过滤。 */
    public Result<Record> queryByFieldsOnly(DSLContext dsl, List<Long> fieldIds, Condition rootCondition) {
        return queryByFieldsOnly(dsl, fieldIds, rootCondition, null);
    }

    /** 诉求2 + 权限。 */
    public Result<Record> queryByFieldsOnly(DSLContext dsl, List<Long> fieldIds,
                                             Condition rootCondition, PermissionContext permissionContext) {
        Map<Long, List<SysModuleField>> requested = resolveByModule(fieldIds);
        FlatGroup tree = treeBuilder.buildAutoRoot(requested.keySet());
        return buildSelect(dsl, requested, tree, rootCondition, permissionContext).fetch();
    }

    /** 只构建查询树，不执行——用于调用方想先知道根节点是哪张表（比如诉求2要决定过滤条件时）。 */
    public FlatGroup previewTree(List<Long> fieldIds, Long explicitRootModuleId) {
        Map<Long, List<SysModuleField>> requested = resolveByModule(fieldIds);
        return explicitRootModuleId != null
                ? treeBuilder.buildFromRoot(explicitRootModuleId, requested.keySet())
                : treeBuilder.buildAutoRoot(requested.keySet());
    }

    private SelectConditionStep<Record> buildSelect(DSLContext dsl, Map<Long, List<SysModuleField>> requested,
                                                      FlatGroup tree, Condition rootCondition,
                                                      PermissionContext permissionContext) {
        FlatGroup resolvedTree = treeBuilder.resolveTableJoins(tree, requested);
        return sqlBuilder.build(dsl, resolvedTree, requested, rootCondition, permissionContext);
    }

    private Map<Long, List<SysModuleField>> resolveByModule(List<Long> fieldIds) {
        Map<Long, List<SysModuleField>> result = new LinkedHashMap<>();
        for (long fid : fieldIds) {
            SysModuleField f = registry.field(fid);
            result.computeIfAbsent(f.moduleId(), k -> new ArrayList<>()).add(f);
        }
        return result;
    }
}
