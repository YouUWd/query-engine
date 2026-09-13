package com.example.schoolquery.engine;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.plan.*;
import com.example.schoolquery.query.*;
import com.example.schoolquery.service.PagedFieldDrivenQueryService;
import org.jooq.DSLContext;
import java.util.*;

/**
 * First production-facing adapter. It reuses the proven paged/nested executor
 * while keeping parsing and semantic resolution outside that legacy service.
 * DML is deliberately rejected until the aggregate mutation planner is wired.
 */
public final class DefaultModuleSqlEngine implements ModuleSqlEngine {
    private final MetadataRegistry registry;
    private final ModuleQueryCompiler compiler;
    private final PagedFieldDrivenQueryService executor;

    public DefaultModuleSqlEngine(MetadataRegistry registry, PagedFieldDrivenQueryService executor){
        this.registry=Objects.requireNonNull(registry);this.compiler=new ModuleQueryCompiler(registry);this.executor=Objects.requireNonNull(executor);
    }
    @Override public ModuleQueryResult executeQuery(DSLContext dsl,String sql){
        QueryPlan plan=compiler.compile(sql);
        if(plan.pagination()==null)throw new IllegalArgumentException("Module SQL execution currently requires LIMIT; use an explicit page size");
        List<FilterCriterion> filters=plan.filters().stream().map(this::toCriterion).toList();
        List<SortCriterion> sorts=plan.sort()==null?List.of():plan.sort().items().stream().map(x->new SortCriterion(x.field().fieldId(),x.direction()==SortPlan.Direction.DESC?SortDirection.DESC:SortDirection.ASC)).toList();
        if(sql.toUpperCase(Locale.ROOT).matches("(?s).*\\bOR\\b.*"))throw new IllegalArgumentException("OR execution requires the boolean-filter executor; use AND predicates in this compatibility adapter");
        QueryRequest req=new QueryRequest(plan.rootModuleId(),plan.pagination().pageNo(),plan.pagination().pageSize(),plan.projections().stream().map(LogicalFieldRef::fieldId).toList(),filters,sorts,false);
        PagedResult result=executor.execute(dsl,req);
        List<ColumnMeta> columns=plan.projections().stream().map(this::columnMeta).toList();
        return new ModuleQueryResult(columns,result.records());
    }
    @Override public List<ColumnMeta> getMetadata(String sql){QueryPlan p=compiler.compile(sql);return p.projections().stream().map(this::columnMeta).toList();}
    @Override public ModuleUpdateResult executeUpdate(DSLContext dsl,String sql){throw new UnsupportedOperationException("DML aggregate mutation planner is not enabled yet");}
    private FilterCriterion toCriterion(FilterPlan f){FilterOperator op=switch(f.operator()){case EQ->FilterOperator.EQ;case NE->FilterOperator.NE;case GT->FilterOperator.GT;case GE->FilterOperator.GTE;case LT->FilterOperator.LT;case LE->FilterOperator.LTE;case LIKE->FilterOperator.LIKE;case IN->FilterOperator.IN;case BETWEEN->FilterOperator.BETWEEN;case IS_NULL->FilterOperator.IS_NULL;case IS_NOT_NULL->FilterOperator.IS_NOT_NULL;};return new FilterCriterion(f.field().fieldId(),op,f.value());}
    private ColumnMeta columnMeta(LogicalFieldRef ref){SysModuleField f=registry.field(ref.fieldId());SysModule m=registry.module(ref.moduleId());return new ColumnMeta(f.id(),m.id(),m.moduleName(),f.tableName(),f.columnName(),"f"+f.id(),com.example.schoolquery.model.SysFieldType.UNKNOWN);}
}
