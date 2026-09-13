package com.example.schoolquery.engine;

import net.sf.jsqlparser.expression.Expression;
import java.util.List;

/** Syntax-only representation. No metadata lookup or SQL generation belongs here. */
public record ModuleSqlAst(
        String rootModuleToken,
        List<String> projectionTokens,
        Expression where,
        List<SortSpec> sorts,
        Integer limit,
        Integer offset) {
    public ModuleSqlAst {
        projectionTokens = projectionTokens == null ? List.of() : List.copyOf(projectionTokens);
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
    }
    public record SortSpec(String expression, boolean ascending) {}
}
