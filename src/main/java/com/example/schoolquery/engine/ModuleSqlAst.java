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
        if (rootModuleToken == null || rootModuleToken.isBlank())
            throw new IllegalArgumentException("rootModuleToken must not be blank");
        projectionTokens = projectionTokens == null ? List.of() : List.copyOf(projectionTokens);
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
        if (limit != null && limit < 0) throw new IllegalArgumentException("limit must not be negative");
        if (offset != null && offset < 0) throw new IllegalArgumentException("offset must not be negative");
    }
    public record SortSpec(String expression, boolean ascending) {
        public SortSpec {
            if (expression == null || expression.isBlank())
                throw new IllegalArgumentException("sort expression must not be blank");
        }
    }
}
