package com.example.schoolquery.engine;

import org.jooq.DSLContext;
import java.util.List;

public interface ModuleSqlEngine {
    ModuleQueryResult executeQuery(DSLContext dsl, String sql);
    List<ColumnMeta> getMetadata(String sql);
    ModuleUpdateResult executeUpdate(DSLContext dsl, String sql);
}
