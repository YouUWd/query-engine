package com.example.schoolquery.engine;

import com.example.schoolquery.mutation.AggregateMutation;
import com.example.schoolquery.mutation.AggregateMutationResult;
import org.jooq.DSLContext;
import java.util.List;

/** Public Module SQL facade. Aggregate mutations are the structured extension for hierarchical saves. */
public interface ModuleSqlEngine {
    ModuleQueryResult executeQuery(DSLContext dsl, String sql);
    List<ColumnMeta> getMetadata(String sql);
    ModuleUpdateResult executeUpdate(DSLContext dsl, String sql);
    AggregateMutationResult executeAggregate(DSLContext dsl, AggregateMutation mutation);
}
