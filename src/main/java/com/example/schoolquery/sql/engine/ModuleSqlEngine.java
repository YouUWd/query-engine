
package com.example.schoolquery.sql.engine;
import com.example.schoolquery.query.model.*;
import com.example.schoolquery.query.resolver.*;
import com.example.schoolquery.query.renderer.*;
import com.example.schoolquery.result.*;
import com.example.schoolquery.sql.parser.*;
import com.example.schoolquery.query.compiler.*;
import com.example.schoolquery.query.executor.*;
import com.example.schoolquery.mutation.model.*;
import com.example.schoolquery.mutation.executor.*;
import com.example.schoolquery.mutation.compiler.*;


import com.example.schoolquery.mutation.model.AggregateMutation;
import com.example.schoolquery.result.AggregateMutationResult;
import org.jooq.DSLContext;
import java.util.List;

/** Public Module SQL facade. Aggregate mutations are the structured extension for hierarchical saves. */
public interface ModuleSqlEngine {
    ModuleQueryResult executeQuery(DSLContext dsl, String sql);
    List<ColumnMeta> getMetadata(String sql);
    ModuleUpdateResult executeUpdate(DSLContext dsl, String sql);
    AggregateMutationResult executeAggregate(DSLContext dsl, AggregateMutation mutation);
}
