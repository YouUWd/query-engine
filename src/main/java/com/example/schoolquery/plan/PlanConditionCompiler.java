package com.example.schoolquery.plan;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.model.SysTableRelation;
import com.example.schoolquery.relation.RelationResolver;
import org.jooq.*;
import java.util.*;
import static org.jooq.impl.DSL.*;

/** Converts the logical filter tree into one root Condition; descendant 1:N predicates become EXISTS chains. */
public final class PlanConditionCompiler {
    private final MetadataRegistry registry; private final RelationResolver relations;
    public PlanConditionCompiler(MetadataRegistry registry,RelationResolver relations){this.registry=Objects.requireNonNull(registry);this.relations=Objects.requireNonNull(relations);}
    public Condition compile(DSLContext dsl,long rootModuleId,FilterExpressionPlan expression){return expression==null?trueCondition():compileExpression(dsl,rootModuleId,expression);}
    private Condition compileExpression(DSLContext dsl,long rootId,FilterExpressionPlan e){
        if(e instanceof FilterExpressionPlan.Predicate p)return compilePredicate(dsl,rootId,p.filter());
        if(e instanceof FilterExpressionPlan.And a){Condition c=trueCondition();for(var x:a.children())c=c.and(compileExpression(dsl,rootId,x));return c;}
        if(e instanceof FilterExpressionPlan.Or o){Condition c=falseCondition();for(var x:o.children())c=c.or(compileExpression(dsl,rootId,x));return c;}
        throw new IllegalArgumentException("Unsupported filter expression: "+e);
    }
    private Condition compilePredicate(DSLContext dsl,long rootId,FilterPlan f){SysModuleField fm=registry.field(f.field().fieldId());SysModule owner=registry.module(fm.moduleId());SysModule root=registry.module(rootId);if(owner.id()==root.id()||samePhysicalPath(root,owner))return predicateCondition(field(fm),f);return descendantExists(dsl,root,owner,fm,f);}
    private Condition descendantExists(DSLContext dsl,SysModule root,SysModule owner,SysModuleField fm,FilterPlan f){
        List<SysModule> path=physicalPath(root,owner);if(path.size()<2)return predicateCondition(field(fm),f);SysModule leaf=path.get(path.size()-1);Table<?> leafTable=table(name(leaf.primaryTable()));Condition inner=predicateCondition(field(leafTable,fm.columnName()),f);SysModule child=leaf;
        for(int i=path.size()-2;i>=0;i--){SysModule parent=path.get(i);Table<?> parentTable=table(name(parent.primaryTable()));Table<?> childTable=table(name(child.primaryTable()));SysTableRelation rel=relations.relationOf(parent.primaryTable(),child.primaryTable());boolean parentIsMain=rel.mainTable().equals(parent.primaryTable());Field<Object> parentField=field(parentTable,parentIsMain?rel.mainField():rel.joinField());Field<Object> childField=field(childTable,parentIsMain?rel.joinField():rel.mainField());inner=inner.and(parentField.eq(childField));inner=exists(dsl.selectOne().from(childTable).where(inner));child=parent;}
        return inner;
    }
    private List<SysModule> physicalPath(SysModule root,SysModule owner){List<Long> chain=registry.ancestorChain(owner.id());Collections.reverse(chain);List<SysModule> r=new ArrayList<>();boolean started=false;for(long id:chain){SysModule m=registry.module(id);if(m.id()==root.id())started=true;if(started&&!m.isVirtual()&&(r.isEmpty()||!r.get(r.size()-1).primaryTable().equals(m.primaryTable())))r.add(m);}if(r.isEmpty()||r.get(0).id()!=root.id())throw new IllegalArgumentException("field module is outside root module");return r;}
    private boolean samePhysicalPath(SysModule root,SysModule owner){return root.primaryTable().equals(owner.primaryTable())&&registry.ancestorChain(owner.id()).contains(root.id());}
    private Condition predicateCondition(Field<Object> field,FilterPlan f){return switch(f.operator()){case EQ->field.eq(f.value());case NE->field.ne(f.value());case GT->field.gt(f.value());case GE->field.ge(f.value());case LT->field.lt(f.value());case LE->field.le(f.value());case LIKE->field.like(String.valueOf(f.value()));case IN->field.in(asList(f.value()));case BETWEEN->{List<?>v=(List<?>)f.value();yield field.between(v.get(0),v.get(1));}case IS_NULL->field.isNull();case IS_NOT_NULL->field.isNotNull();};}
    private List<?> asList(Object v){return v instanceof Collection<?> c?List.copyOf(c):List.of(v);}
    private Field<Object> field(SysModuleField f){return field(name(f.tableName(),f.columnName()),Object.class);}
    private Field<Object> field(Table<?> t,String column){return field(name(t.getName(),column),Object.class);}
}
