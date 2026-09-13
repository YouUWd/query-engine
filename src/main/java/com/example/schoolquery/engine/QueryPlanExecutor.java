package com.example.schoolquery.engine;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.plan.LogicalFieldRef;
import com.example.schoolquery.plan.PlanConditionCompiler;
import com.example.schoolquery.plan.QueryPlan;
import com.example.schoolquery.querytree.FlatGroup;
import com.example.schoolquery.querytree.QueryTreeBuilder;
import com.example.schoolquery.relation.RelationResolver;
import com.example.schoolquery.render.RecordRenderer;
import com.example.schoolquery.sql.DynamicFields;
import com.example.schoolquery.sql.FlatGroupSqlBuilder;
import org.jooq.*;
import org.jooq.Record;
import java.util.*;
import static org.jooq.impl.DSL.*;

/** Executes a semantic QueryPlan without exposing QueryTree/FlatGroup to callers. */
public final class QueryPlanExecutor {
    private final MetadataRegistry registry;
    private final QueryTreeBuilder treeBuilder;
    private final FlatGroupSqlBuilder sqlBuilder;
    private final PlanConditionCompiler conditionCompiler;
    private final RecordRenderer renderer=new RecordRenderer();

    public QueryPlanExecutor(MetadataRegistry registry){
        this.registry=Objects.requireNonNull(registry,"registry");
        RelationResolver resolver=new RelationResolver(registry);
        this.treeBuilder=new QueryTreeBuilder(registry,resolver);
        this.sqlBuilder=new FlatGroupSqlBuilder(resolver);
        this.conditionCompiler=new PlanConditionCompiler(registry);
    }

    public ModuleQueryResult execute(DSLContext dsl,QueryPlan plan){
        Objects.requireNonNull(dsl,"dsl"); Objects.requireNonNull(plan,"plan");
        if(plan.projections().isEmpty())throw new IllegalArgumentException("SELECT projection must not be empty");
        Map<Long,List<SysModuleField>> requested=resolveProjection(plan.projections());
        FlatGroup tree=treeBuilder.buildFromRoot(plan.rootModuleId(),requested.keySet());
        Condition condition=conditionCompiler.compile(dsl,plan.rootModuleId(),plan.filterExpression());
        SelectConditionStep<Record> select=sqlBuilder.build(dsl,tree,requested,condition,null,Map.of());
        SelectLimitStep<Record> limited=select.orderBy(buildOrderBy(plan));
        Result<Record> rows;
        if(plan.pagination()==null) rows=limited.fetch();
        else rows=limited.limit(plan.pagination().pageSize()).offset(plan.pagination().offset()).fetch();
        List<Map<String,Object>> rendered=rows.stream().map(r->renderer.renderRecord(plan.rootModuleId(),tree,r,requested)).toList();
        return new ModuleQueryResult(metadata(plan.projections()),rendered);
    }

    private Map<Long,List<SysModuleField>> resolveProjection(List<LogicalFieldRef> projections){
        Map<Long,List<SysModuleField>> result=new LinkedHashMap<>();
        for(LogicalFieldRef ref:projections){SysModuleField field=registry.field(ref.fieldId());if(field.moduleId()!=ref.moduleId())throw new IllegalArgumentException("fieldId="+ref.fieldId()+" does not belong to moduleId="+ref.moduleId());result.computeIfAbsent(ref.moduleId(),k->new ArrayList<>()).add(field);}
        return result;
    }
    private List<SortField<?>> buildOrderBy(QueryPlan plan){
        List<SortField<?>> result=new ArrayList<>();
        for(var item:plan.sort().items()){SysModuleField field=registry.field(item.field().fieldId());Field<Object> column=DynamicFields.field(table(name(field.tableName())),field.columnName());result.add(item.direction()==com.example.schoolquery.plan.SortPlan.Direction.DESC?column.desc():column.asc());}
        return result;
    }
    private List<ColumnMeta> metadata(List<LogicalFieldRef> projections){
        List<ColumnMeta> result=new ArrayList<>();
        for(LogicalFieldRef ref:projections){SysModuleField field=registry.field(ref.fieldId());var module=registry.module(ref.moduleId());result.add(new ColumnMeta(field.id(),module.id(),module.moduleName(),field.tableName(),field.columnName(),FlatGroupSqlBuilder.fieldAlias(field.id()),com.example.schoolquery.model.SysFieldType.UNKNOWN));}
        return result;
    }
}
