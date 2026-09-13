package com.example.schoolquery.engine;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.mutation.MutationCompiler;
import com.example.schoolquery.mutation.MutationExecutor;
import com.example.schoolquery.mutation.MutationPlan;
import com.example.schoolquery.service.PagedFieldDrivenQueryService;
import org.jooq.DSLContext;
import java.util.ArrayList;
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
        List<ColumnMeta> result = new ArrayList<>();
        for (int i = 0; i < plan.projections().size(); i++) {
            var ref = plan.projections().get(i);
            var field = registry.field(ref.fieldId());
            var module = registry.module(ref.moduleId());
            result.add(new ColumnMeta(field.id(), module.id(), module.moduleName(), field.tableName(),
                    field.columnName(), plan.projectionAliases().get(i),
                    com.example.schoolquery.model.SysFieldType.UNKNOWN));
        }
        return List.copyOf(result);
    }

    @Override
    public ModuleUpdateResult executeUpdate(DSLContext dsl, String sql) {
        MutationPlan plan = mutationCompiler.compile(sql);
        var result = mutationExecutor.executeWithResult(dsl, plan);
        return new ModuleUpdateResult(result.affectedRows(), result.generatedKeys());
    }
}
