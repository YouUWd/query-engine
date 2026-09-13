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

/** Renders a QueryTree. Nested relations are resolved by logical module identity. */
public class FlatGroupSqlBuilder {
    private final RelationResolver resolver;
    private final PermissionRegistry permissionRegistry;
    public FlatGroupSqlBuilder(RelationResolver resolver){this(resolver,new PermissionRegistry(List.of()));}
    public FlatGroupSqlBuilder(RelationResolver resolver,PermissionRegistry permissionRegistry){this.resolver=resolver;this.permissionRegistry=permissionRegistry;}
    public static String fieldAlias(long fieldId){return "f"+fieldId;}
    public static String nestedAlias(long childModuleId){return "m"+childModuleId;}
    public SelectConditionStep<Record> build(DSLContext dsl,FlatGroup group,Map<Long,List<SysModuleField>> requestedByModule,Condition condition){return build(dsl,group,requestedByModule,condition,null,Map.of(),Map.of());}
    public SelectConditionStep<Record> build(DSLContext dsl,FlatGroup group,Map<Long,List<SysModuleField>> requestedByModule,Condition condition,PermissionContext ctx){return build(dsl,group,requestedByModule,condition,ctx,Map.of(),Map.of());}
    public SelectConditionStep<Record> build(DSLContext dsl,FlatGroup group,Map<Long,List<SysModuleField>> requestedByModule,Condition condition,PermissionContext ctx,Map<String,Condition> extra){return build(dsl,group,requestedByModule,condition,ctx,extra,Map.of());}
    /** Same renderer with logical-module-local conditions for nested result sets. */
    public SelectConditionStep<Record> build(DSLContext dsl,FlatGroup group,Map<Long,List<SysModuleField>> requestedByModule,Condition condition,PermissionContext ctx,Map<String,Condition> extra,Map<Long,Condition> localConditions){
        Table<?> primary=table(name(group.primaryTable())); List<SelectFieldOrAsterisk> fields=new ArrayList<>(); Map<String,Table<?>> joins=new LinkedHashMap<>(); Map<String,Condition> joinConditions=new LinkedHashMap<>();
        for(long mid:group.mergedModuleIds()) for(SysModuleField f:requestedByModule.getOrDefault(mid,List.of())){Table<?> owner;if(f.tableName().equals(group.primaryTable()))owner=primary;else{owner=joins.computeIfAbsent(f.tableName(),t->table(name(t)));joinConditions.computeIfAbsent(f.tableName(),t->buildFlatJoinCondition(primary,group.primaryTable(),owner,t));}fields.add(DynamicFields.field(owner,f.columnName()).as(fieldAlias(f.id())));}
        for(NestedGroup nested:group.nestedChildren()){
            ResolvedRelationPlan rel=nested.relation(); Field<Object> parentKey=DynamicFields.field(primary,rel.parentColumn()); Table<?> child=table(name(nested.group().primaryTable())); Field<Object> childFk=DynamicFields.field(child,rel.childColumn());
            Condition childCondition=childFk.eq(parentKey); Condition local=localConditions.get(nested.childModuleId()); if(local!=null)childCondition=childCondition.and(local);
            SelectConditionStep<Record> childSelect=build(dsl,nested.group(),requestedByModule,childCondition,ctx,extra,localConditions); fields.add(multiset(childSelect).as(nestedAlias(nested.childModuleId())));
        }
        if(fields.isEmpty())throw new IllegalArgumentException("模块组 "+group.mergedModuleIds()+"（主表 "+group.primaryTable()+"）没有任何被请求的字段");
        SelectJoinStep<Record> from=dsl.select(fields).from(primary); SelectOnConditionStep<Record> joined=null; for(Map.Entry<String,Table<?>> e:joins.entrySet()){SelectJoinStep<Record> base=joined==null?from:joined;joined=base.leftJoin(e.getValue()).on(joinConditions.get(e.getKey()));}
        Condition scoped=applyDataScope(primary,group.primaryTable(),condition,ctx); Condition withExtra=applyExtraCondition(group.primaryTable(),scoped,extra); return joined!=null?joined.where(withExtra):from.where(withExtra);
    }
    public SelectConditionStep<Record> buildSingleGroupQuery(DSLContext dsl,FlatGroup group,Map<Long,List<SysModuleField>> requestedByModule,Condition condition,PermissionContext ctx,Map<String,Condition> extra,Map<String,Field<?>> extraProjected){
        Table<?> primary=table(name(group.primaryTable())); List<SelectFieldOrAsterisk> fields=new ArrayList<>(); Map<String,Table<?>> joins=new LinkedHashMap<>(); Map<String,Condition> joinConditions=new LinkedHashMap<>(); Set<String> aliases=new HashSet<>();
        for(long mid:group.mergedModuleIds())for(SysModuleField f:requestedByModule.getOrDefault(mid,List.of())){Table<?> owner;if(f.tableName().equals(group.primaryTable()))owner=primary;else{owner=joins.computeIfAbsent(f.tableName(),t->table(name(t)));joinConditions.computeIfAbsent(f.tableName(),t->buildFlatJoinCondition(primary,group.primaryTable(),owner,t));}String a=fieldAlias(f.id());fields.add(DynamicFields.field(owner,f.columnName()).as(a));aliases.add(a);}
        if(extraProjected!=null)for(Map.Entry<String,Field<?>> e:extraProjected.entrySet())if(aliases.add(e.getKey()))fields.add(e.getValue().as(e.getKey()));
        if(fields.isEmpty())throw new IllegalArgumentException("模块组 "+group.mergedModuleIds()+"（主表 "+group.primaryTable()+"）没有任何被请求的字段");
        SelectJoinStep<Record> from=dsl.select(fields).from(primary); SelectOnConditionStep<Record> joined=null; for(Map.Entry<String,Table<?>> e:joins.entrySet()){SelectJoinStep<Record> base=joined==null?from:joined;joined=base.leftJoin(e.getValue()).on(joinConditions.get(e.getKey()));}
        Condition scoped=applyDataScope(primary,group.primaryTable(),condition,ctx);Condition withExtra=applyExtraCondition(group.primaryTable(),scoped,extra);return joined!=null?joined.where(withExtra):from.where(withExtra);
    }
    public SelectConditionStep<Record1<Integer>> buildCountQuery(DSLContext dsl,FlatGroup root,Map<Long,List<SysModuleField>> requestedByModule,Condition condition,PermissionContext ctx,Map<String,Condition> extra){
        Table<?> primary=table(name(root.primaryTable()));Map<String,Table<?>> joins=new LinkedHashMap<>();Map<String,Condition> joinConditions=new LinkedHashMap<>();for(long mid:root.mergedModuleIds())for(SysModuleField f:requestedByModule.getOrDefault(mid,List.of()))if(!f.tableName().equals(root.primaryTable())){Table<?> owner=joins.computeIfAbsent(f.tableName(),t->table(name(t)));joinConditions.computeIfAbsent(f.tableName(),t->buildFlatJoinCondition(primary,root.primaryTable(),owner,t));}
        SelectJoinStep<Record1<Integer>> from=dsl.selectCount().from(primary);SelectOnConditionStep<Record1<Integer>> joined=null;for(Map.Entry<String,Table<?>> e:joins.entrySet()){SelectJoinStep<Record1<Integer>> base=joined==null?from:joined;joined=base.leftJoin(e.getValue()).on(joinConditions.get(e.getKey()));}Condition scoped=applyDataScope(primary,root.primaryTable(),condition,ctx);Condition withExtra=applyExtraCondition(root.primaryTable(),scoped,extra);return joined!=null?joined.where(withExtra):from.where(withExtra);
    }
    private Condition applyExtraCondition(String tableName,Condition base,Map<String,Condition> extra){Condition e=extra.get(tableName);return e==null?base:base.and(e);}
    private Condition applyDataScope(Table<?> primary,String tableName,Condition base,PermissionContext ctx){if(ctx==null)return base;Optional<SysDataScopeRule> opt=permissionRegistry.scopeRuleFor(tableName);if(opt.isEmpty())return base;SysDataScopeRule r=opt.get();return base.and(DynamicFields.field(primary,r.scopeColumn()).in(ctx.scopeValues(r.contextKey())));}
    /** Legacy physical-table join API retained only for module-internal table joins. */
    private Condition buildFlatJoinCondition(Table<?> primary,String primaryTable,Table<?> other,String otherTable){if(resolver==null)throw new IllegalStateException("RelationResolver is required for legacy physical-table joins");var rel=resolver.relationOf(primaryTable,otherTable);if(rel.mainTable().equals(primaryTable))return DynamicFields.field(primary,rel.mainField()).eq(DynamicFields.field(other,rel.joinField()));return DynamicFields.field(primary,rel.joinField()).eq(DynamicFields.field(other,rel.mainField()));}
}
