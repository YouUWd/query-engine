package com.example.schoolquery.engine;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.mutation.MutationCompiler;
import com.example.schoolquery.mutation.MutationExecutor;
import com.example.schoolquery.plan.*;
import com.example.schoolquery.query.*;
import com.example.schoolquery.service.PagedFieldDrivenQueryService;
import org.jooq.DSLContext;
import java.util.*;

/** Module SQL facade. Scalar DML uses the independent mutation pipeline. */
public final class DefaultModuleSqlEngine implements ModuleSqlEngine {
    private final MetadataRegistry registry;
    private final ModuleQueryCompiler compiler;
    private final MutationCompiler mutationCompiler;
    private final MutationExecutor mutationExecutor;
    private final PagedFieldDrivenQueryService executor;

    public DefaultModuleSqlEngine(MetadataRegistry registry, PagedFieldDrivenQueryService executor) {
        this.registry=Objects.requireNonNull(registry); this.compiler=new ModuleQueryCompiler(registry);
        this.mutationCompiler=new MutationCompiler(registry); this.mutationExecutor=new MutationExecutor(registry);
        this.executor=Objects.requireNonNull(executor);
    }
    @Override public ModuleQueryResult executeQuery(DSLContext dsl,String sql) {
        QueryPlan plan=compiler.compile(sql);
        if(plan.pagination()==null) throw new IllegalArgumentException("Module SQL execution requires LIMIT");
        if(plan.filterExpression() instanceof FilterExpressionPlan.Or)
            throw new IllegalArgumentException("OR execution is not enabled by the legacy paged adapter");
        List<FilterCriterion> filters=plan.filters().stream().map(this::toCriterion).toList();
        List<SortCriterion> sorts=plan.sort().items().stream().map(x->new SortCriterion(x.field().fieldId(),x.direction()==SortPlan.Direction.DESC?SortDirection.DESC:SortDirection.ASC)).toList();
        QueryRequest req=new QueryRequest(plan.rootModuleId(),plan.pagination().pageNo(),plan.pagination().pageSize(),plan.projections().stream().map(LogicalFieldRef::fieldId).toList(),filters,sorts,false);
        PagedResult result=executor.execute(dsl,req);
        return new ModuleQueryResult(plan.projections().stream().map(this::columnMeta).toList(),result.records());
    }
    @Override public List<ColumnMeta> getMetadata(String sql){return compiler.compile(sql).projections().stream().map(this::columnMeta).toList();}
    @Override public ModuleUpdateResult executeUpdate(DSLContext dsl,String sql){MutationPlan plan=mutationCompiler.compile(sql);return new ModuleUpdateResult(plan.operation().name(),mutationExecutor.execute(dsl,plan));}
    private FilterCriterion toCriterion(FilterPlan f){FilterOperator op=switch(f.operator()){case EQ->FilterOperator.EQ;case NE->FilterOperator.NE;case GT->FilterOperator.GT;case GE->FilterOperator.GTE;case LT->FilterOperator.LT;case LE->FilterOperator.LTE;case LIKE->FilterOperator.LIKE;case IN->FilterOperator.IN;case BETWEEN->FilterOperator.BETWEEN;case IS_NULL->FilterOperator.IS_NULL;case IS_NOT_NULL->FilterOperator.IS_NOT_NULL;};return new FilterCriterion(f.field().fieldId(),op,f.value());}
    private ColumnMeta columnMeta(LogicalFieldRef ref){SysModuleField f=registry.field(ref.fieldId());SysModule m=registry.module(ref.moduleId());return new ColumnMeta(f.id(),m.id(),m.moduleName(),f.tableName(),f.columnName(),"f"+f.id(),com.example.schoolquery.model.SysFieldType.UNKNOWN);}
}
