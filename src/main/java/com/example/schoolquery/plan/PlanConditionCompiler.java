package com.example.schoolquery.plan;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import java.util.*;
import static org.jooq.impl.DSL.*;

/** Converts logical filters to jOOQ conditions; descendant predicates become correlated EXISTS chains. */
public final class PlanConditionCompiler {
    private final MetadataRegistry registry;
    private final LogicalRelationResolver relations;
    public PlanConditionCompiler(MetadataRegistry registry){this.registry=Objects.requireNonNull(registry,"registry");this.relations=new LogicalRelationResolver(registry);}
    public PlanConditionCompiler(MetadataRegistry registry,Object ignoredRelationResolver){this(registry);}
    public Condition compile(DSLContext dsl,long rootModuleId,FilterExpressionPlan expression){return expression==null?trueCondition():compileExpression(dsl,rootModuleId,expression);}

    /**
     * Compiles the portion of a filter expression that is local to one nested module.
     * AND can safely discard predicates outside the subtree. A mixed-scope OR is left
     * unfiltered because dropping one OR branch would change its truth value.
     */
    public Condition compileLocal(DSLContext dsl,long targetModuleId,FilterExpressionPlan expression){
        FilterExpressionPlan projected=projectLocal(targetModuleId,expression);
        return projected==null?trueCondition():compileExpression(dsl,targetModuleId,projected);
    }
    private FilterExpressionPlan projectLocal(long target,FilterExpressionPlan expression){
        if(expression instanceof FilterExpressionPlan.Predicate p){return isInSubtree(target,p.filter())?expression:null;}
        if(expression instanceof FilterExpressionPlan.And a){List<FilterExpressionPlan> kept=new ArrayList<>();for(FilterExpressionPlan child:a.children()){FilterExpressionPlan x=projectLocal(target,child);if(x!=null)kept.add(x);}if(kept.isEmpty())return null;if(kept.size()==1)return kept.get(0);return new FilterExpressionPlan.And(kept);}
        if(expression instanceof FilterExpressionPlan.Or o){List<FilterExpressionPlan> kept=new ArrayList<>();for(FilterExpressionPlan child:o.children()){FilterExpressionPlan x=projectLocal(target,child);if(x==null)return null;kept.add(x);}return new FilterExpressionPlan.Or(kept);}
        throw new IllegalArgumentException("Unsupported filter expression: "+expression);
    }
    private boolean isInSubtree(long target,FilterPlan filter){return registry.ancestorChain(filter.field().moduleId()).contains(target);}

    private Condition compileExpression(DSLContext dsl,long rootId,FilterExpressionPlan expression){
        if(expression instanceof FilterExpressionPlan.Predicate p)return compilePredicate(dsl,rootId,p.filter());
        if(expression instanceof FilterExpressionPlan.And a){Condition result=trueCondition();for(FilterExpressionPlan child:a.children())result=result.and(compileExpression(dsl,rootId,child));return result;}
        if(expression instanceof FilterExpressionPlan.Or o){Condition result=falseCondition();for(FilterExpressionPlan child:o.children())result=result.or(compileExpression(dsl,rootId,child));return result;}
        throw new IllegalArgumentException("Unsupported filter expression: "+expression);
    }
    private Condition compilePredicate(DSLContext dsl,long rootId,FilterPlan filter){SysModuleField fieldMeta=registry.field(filter.field().fieldId());SysModule owner=registry.module(fieldMeta.moduleId());SysModule root=registry.module(rootId);if(owner.id()==root.id()||samePhysicalEntity(root,owner))return predicateCondition(field(fieldMeta),filter);return descendantExists(dsl,root,owner,fieldMeta,filter);}
    private Condition descendantExists(DSLContext dsl,SysModule root,SysModule owner,SysModuleField fieldMeta,FilterPlan filter){List<SysModule> path=modulePath(root,owner);if(path.size()<2)return predicateCondition(field(fieldMeta),filter);SysModule leaf=path.get(path.size()-1);Table<?> leafTable=table(name(leaf.primaryTable()));Condition inner=predicateCondition(field(leafTable,fieldMeta.columnName()),filter);SysModule child=leaf;for(int i=path.size()-2;i>=0;i--){SysModule parent=path.get(i);ResolvedRelationPlan relation=relations.resolve(parent.id(),child.id());if(relation.parentColumn().isBlank()||relation.childColumn().isBlank())throw new IllegalArgumentException("No physical key mapping for module relation "+parent.id()+" -> "+child.id());Table<?> parentTable=table(name(parent.primaryTable()));Table<?> childTable=table(name(child.primaryTable()));Field<Object> parentKey=field(name(parentTable.getName(),relation.parentColumn()),Object.class);Field<Object> childKey=field(name(childTable.getName(),relation.childColumn()),Object.class);inner=inner.and(parentKey.eq(childKey));inner=exists(dsl.selectOne().from(childTable).where(inner));child=parent;}return inner;}
    private List<SysModule> modulePath(SysModule root,SysModule owner){List<Long> chain=registry.ancestorChain(owner.id());Collections.reverse(chain);List<SysModule> result=new ArrayList<>();boolean started=false;for(long id:chain){SysModule module=registry.module(id);if(module.id()==root.id())started=true;if(!started||module.isVirtual())continue;if(result.isEmpty()||!result.get(result.size()-1).primaryTable().equals(module.primaryTable()))result.add(module);}if(result.isEmpty()||result.get(0).id()!=root.id())throw new IllegalArgumentException("field module is outside root module");return result;}
    private boolean samePhysicalEntity(SysModule root,SysModule owner){return root.primaryTable().equals(owner.primaryTable())&&registry.ancestorChain(owner.id()).contains(root.id());}
    private Condition predicateCondition(Field<Object> field,FilterPlan filter){return switch(filter.operator()){case EQ->field.eq(filter.value());case NE->field.ne(filter.value());case GT->field.gt(filter.value());case GE->field.ge(filter.value());case LT->field.lt(filter.value());case LE->field.le(filter.value());case LIKE->field.like(String.valueOf(filter.value()));case IN->field.in(asList(filter.value()));case BETWEEN->{List<?> v=asList(filter.value());if(v.size()!=2)throw new IllegalArgumentException("BETWEEN requires exactly two values");yield field.between(v.get(0),v.get(1));}case IS_NULL->field.isNull();case IS_NOT_NULL->field.isNotNull();};}
    private List<?> asList(Object value){return value instanceof Collection<?> c?List.copyOf(c):List.of(value);}
    private Field<Object> field(SysModuleField f){return field(name(f.tableName(),f.columnName()),Object.class);}
    private Field<Object> field(Table<?> t,String column){return field(name(t.getName(),column),Object.class);}
}
