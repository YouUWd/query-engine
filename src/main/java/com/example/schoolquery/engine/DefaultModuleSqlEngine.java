package com.example.schoolquery.engine;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.mutation.MutationCompiler;
import com.example.schoolquery.mutation.MutationExecutor;
import com.example.schoolquery.mutation.MutationPlan;
import com.example.schoolquery.service.PagedFieldDrivenQueryService;
import org.jooq.DSLContext;
import java.util.List;
import java.util.Objects;

/** Public Module SQL facade. DQL and DML use independent semantic pipelines. */
public final class DefaultModuleSqlEngine implements ModuleSqlEngine {
    private final MetadataRegistry registry;
    private final ModuleQueryCompiler compiler;
    private final QueryPlanExecutor queryExecutor;
    private final MutationCompiler mutationCompiler;
    private final MutationExecutor mutationExecutor;

    public DefaultModuleSqlEngine(MetadataRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.compiler = new ModuleQueryCompiler(registry);
        this.queryExecutor = new QueryPlanExecutor(registry);
        this.mutationCompiler = new MutationCompiler(registry);
        this.mutationExecutor = new MutationExecutor(registry);
    }

    /** Binary/source compatibility for callers that still construct the legacy paged service. */
    public DefaultModuleSqlEngine(MetadataRegistry registry, PagedFieldDrivenQueryService ignoredLegacyExecutor) {
        this(registry);
    }

    @Override
    public ModuleQueryResult executeQuery(DSLContext dsl, String sql) {
        return queryExecutor.execute(dsl, compiler.compile(sql));
    }

    @Override
    public List<ColumnMeta> getMetadata(String sql) {
        var plan = compiler.compile(sql);
        return java.util.stream.IntStream.range(0, plan.projections().size())
                .mapToObj(i -> {
                    var ref = plan.projections().get(i);
                    var f = registry.field(ref.fieldId());
                    var m = registry.module(ref.moduleId());
                    return new ColumnMeta(f.id(), m.id(), m.moduleName(), f.tableName(), f.columnName(),
                            plan.projectionAliases().get(i), com.example.schoolquery.model.SysFieldType.UNKNOWN);
                })
                .toList();
    }

    @Override
    public ModuleUpdateResult executeUpdate(DSLContext dsl, String sql) {
        MutationPlan plan = mutationCompiler.compile(sql);
        return new ModuleUpdateResult(mutationExecutor.execute(dsl, plan), List.of());
    }
}
