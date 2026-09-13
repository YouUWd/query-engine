package com.example.schoolquery.sql;

import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.permission.PermissionContext;
import com.example.schoolquery.permission.PermissionRegistry;
import com.example.schoolquery.permission.SysDataScopeRule;
import com.example.schoolquery.plan.ResolvedRelationPlan;
import com.example.schoolquery.querytree.FlatGroup;
import com.example.schoolquery.querytree.NestedGroup;
import com.example.schoolquery.relation.RelationResolver;
import org.jooq.*;
import org.jooq.Record;

import java.util.*;

import static org.jooq.impl.DSL.*;

/**
 * 将物理 QueryTree 渲染为 jOOQ SQL。
 *
 * 新语义边界：NestedGroup 携带已经按 moduleId 解析完成的
 * {@link ResolvedRelationPlan}，因此这里绝不再通过物理表名重新寻找关系。
 * RelationResolver 仅保留给旧 API/兼容调用；新的树执行路径使用 logical relation。
 */
public class FlatGroupSqlBuilder {
    private final RelationResolver resolver;
    private final PermissionRegistry permissionRegistry;

    public FlatGroupSqlBuilder(RelationResolver resolver) { this(resolver, new PermissionRegistry(List.of())); }
    public FlatGroupSqlBuilder(RelationResolver resolver, PermissionRegistry permissionRegistry) {
        this.resolver = resolver;
        this.permissionRegistry = permissionRegistry;
    }

    public static String fieldAlias(long fieldId) { return "f" + fieldId; }
    public static String nestedAlias(long childModuleId) { return "m" + childModuleId; }

    public SelectConditionStep<Record> build(DSLContext dsl, FlatGroup group,
            Map<Long, List<SysModuleField>> requestedByModule, Condition condition) {
        return build(dsl, group, requestedByModule, condition, null, Map.of());
    }

    public SelectConditionStep<Record> build(DSLContext dsl, FlatGroup group,
            Map<Long, List<SysModuleField>> requestedByModule, Condition condition,
            PermissionContext permissionContext) {
        return build(dsl, group, requestedByModule, condition, permissionContext, Map.of());
    }

    public SelectConditionStep<Record> build(DSLContext dsl, FlatGroup group,
            Map<Long, List<SysModuleField>> requestedByModule, Condition condition,
            PermissionContext permissionContext, Map<String, Condition> extraConditionsByTable) {
        Table<?> primary = table(name(group.primaryTable()));
        List<SelectFieldOrAsterisk> selectFields = new ArrayList<>();
        Map<String, Table<?>> joinedTables = new LinkedHashMap<>();
        Map<String, Condition> joinConditions = new LinkedHashMap<>();

        for (long moduleId : group.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                Table<?> owner;
                if (f.tableName().equals(group.primaryTable())) owner = primary;
                else {
                    owner = joinedTables.computeIfAbsent(f.tableName(), t -> table(name(t)));
                    joinConditions.computeIfAbsent(f.tableName(),
                            t -> buildFlatJoinCondition(primary, group.primaryTable(), owner, t));
                }
                selectFields.add(DynamicFields.field(owner, f.columnName()).as(fieldAlias(f.id())));
            }
        }

        for (NestedGroup nested : group.nestedChildren()) {
            ResolvedRelationPlan rel = nested.relation();
            Field<Object> parentKey = DynamicFields.field(primary, rel.parentColumn());
            Table<?> childPrimary = table(name(nested.group().primaryTable()));
            Field<Object> childFk = DynamicFields.field(childPrimary, rel.childColumn());
            SelectConditionStep<Record> childSelect = build(dsl, nested.group(), requestedByModule,
                    childFk.eq(parentKey), permissionContext, extraConditionsByTable);
            selectFields.add(multiset(childSelect).as(nestedAlias(nested.childModuleId())));
        }

        if (selectFields.isEmpty()) throw new IllegalArgumentException("模块组 " + group.mergedModuleIds()
                + "（主表 " + group.primaryTable() + "）没有任何被请求的字段");

        SelectJoinStep<Record> from = dsl.select(selectFields).from(primary);
        SelectOnConditionStep<Record> joined = null;
        for (Map.Entry<String, Table<?>> e : joinedTables.entrySet()) {
            SelectJoinStep<Record> base = joined == null ? from : joined;
            joined = base.leftJoin(e.getValue()).on(joinConditions.get(e.getKey()));
        }
        Condition scoped = applyDataScope(primary, group.primaryTable(), condition, permissionContext);
        Condition withExtra = applyExtraCondition(group.primaryTable(), scoped, extraConditionsByTable);
        return joined != null ? joined.where(withExtra) : from.where(withExtra);
    }

    /** Build one physical group only; nested children are intentionally excluded. */
    public SelectConditionStep<Record> buildSingleGroupQuery(DSLContext dsl, FlatGroup group,
            Map<Long, List<SysModuleField>> requestedByModule, Condition condition,
            PermissionContext permissionContext, Map<String, Condition> extraConditionsByTable,
            Map<String, Field<?>> extraProjectedFields) {
        Table<?> primary = table(name(group.primaryTable()));
        List<SelectFieldOrAsterisk> selectFields = new ArrayList<>();
        Map<String, Table<?>> joinedTables = new LinkedHashMap<>();
        Map<String, Condition> joinConditions = new LinkedHashMap<>();
        Set<String> projectedAliases = new HashSet<>();

        for (long moduleId : group.mergedModuleIds()) {
            for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of())) {
                Table<?> owner;
                if (f.tableName().equals(group.primaryTable())) owner = primary;
                else {
                    owner = joinedTables.computeIfAbsent(f.tableName(), t -> table(name(t)));
                    joinConditions.computeIfAbsent(f.tableName(),
                            t -> buildFlatJoinCondition(primary, group.primaryTable(), owner, t));
                }
                String alias = fieldAlias(f.id());
                selectFields.add(DynamicFields.field(owner, f.columnName()).as(alias));
                projectedAliases.add(alias);
            }
        }
        if (extraProjectedFields != null) for (Map.Entry<String, Field<?>> e : extraProjectedFields.entrySet())
            if (projectedAliases.add(e.getKey())) selectFields.add(e.getValue().as(e.getKey()));

        if (selectFields.isEmpty()) throw new IllegalArgumentException("模块组 " + group.mergedModuleIds()
                + "（主表 " + group.primaryTable() + "）没有任何被请求的字段");
        SelectJoinStep<Record> from = dsl.select(selectFields).from(primary);
        SelectOnConditionStep<Record> joined = null;
        for (Map.Entry<String, Table<?>> e : joinedTables.entrySet()) {
            SelectJoinStep<Record> base = joined == null ? from : joined;
            joined = base.leftJoin(e.getValue()).on(joinConditions.get(e.getKey()));
        }
        Condition scoped = applyDataScope(primary, group.primaryTable(), condition, permissionContext);
        Condition withExtra = applyExtraCondition(group.primaryTable(), scoped, extraConditionsByTable);
        return joined != null ? joined.where(withExtra) : from.where(withExtra);
    }

    public SelectConditionStep<Record1<Integer>> buildCountQuery(DSLContext dsl, FlatGroup rootGroup,
            Map<Long, List<SysModuleField>> requestedByModule, Condition rootCondition,
            PermissionContext permissionContext, Map<String, Condition> extraConditionsByTable) {
        Table<?> primary = table(name(rootGroup.primaryTable()));
        Map<String, Table<?>> joinedTables = new LinkedHashMap<>();
        Map<String, Condition> joinConditions = new LinkedHashMap<>();
        for (long moduleId : rootGroup.mergedModuleIds()) for (SysModuleField f : requestedByModule.getOrDefault(moduleId, List.of()))
            if (!f.tableName().equals(rootGroup.primaryTable())) {
                Table<?> owner = joinedTables.computeIfAbsent(f.tableName(), t -> table(name(t)));
                joinConditions.computeIfAbsent(f.tableName(),
                        t -> buildFlatJoinCondition(primary, rootGroup.primaryTable(), owner, t));
            }

        SelectJoinStep<Record1<Integer>> from = dsl.selectCount().from(primary);
        SelectOnConditionStep<Record1<Integer>> joined = null;
        for (Map.Entry<String, Table<?>> e : joinedTables.entrySet()) {
            SelectJoinStep<Record1<Integer>> base = joined == null ? from : joined;
            joined = base.leftJoin(e.getValue()).on(joinConditions.get(e.getKey()));
        }
        Condition scoped = applyDataScope(primary, rootGroup.primaryTable(), rootCondition, permissionContext);
        Condition withExtra = applyExtraCondition(rootGroup.primaryTable(), scoped, extraConditionsByTable);
        return joined != null ? joined.where(withExtra) : from.where(withExtra);
    }

    private Condition applyExtraCondition(String tableName, Condition base, Map<String, Condition> extraByTable) {
        Condition extra = extraByTable.get(tableName);
        return extra == null ? base : base.and(extra);
    }

    private Condition applyDataScope(Table<?> primary, String tableName, Condition baseCondition, PermissionContext ctx) {
        if (ctx == null) return baseCondition;
        Optional<SysDataScopeRule> ruleOpt = permissionRegistry.scopeRuleFor(tableName);
        if (ruleOpt.isEmpty()) return baseCondition;
        SysDataScopeRule rule = ruleOpt.get();
        Field<Object> scopeField = DynamicFields.field(primary, rule.scopeColumn());
        return baseCondition.and(scopeField.in(ctx.scopeValues(rule.contextKey())));
    }

    /** Legacy physical-table join API retained for old callers only. */
    private Condition buildFlatJoinCondition(Table<?> primary, String primaryTableName,
            Table<?> other, String otherTableName) {
        if (resolver == null) throw new IllegalStateException("RelationResolver is required for legacy physical-table joins");
        var rel = resolver.relationOf(primaryTableName, otherTableName);
        if (rel.mainTable().equals(primaryTableName))
            return DynamicFields.field(primary, rel.mainField()).eq(DynamicFields.field(other, rel.joinField()));
        return DynamicFields.field(primary, rel.joinField()).eq(DynamicFields.field(other, rel.mainField()));
    }
}
