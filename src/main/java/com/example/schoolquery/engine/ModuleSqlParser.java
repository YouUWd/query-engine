package com.example.schoolquery.engine;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL syntax boundary. It deliberately does not resolve module/field metadata.
 * The accepted SQL is ordinary SELECT syntax whose identifiers are interpreted
 * by the module compiler afterwards.
 */
public final class ModuleSqlParser {
    public ModuleSqlAst parseSelect(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("sql must not be blank");
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select) || !(select.getSelectBody() instanceof PlainSelect ps)) {
                throw new IllegalArgumentException("Only a single SELECT statement is supported");
            }
            if (!(ps.getFromItem() instanceof net.sf.jsqlparser.schema.Table table)) {
                throw new IllegalArgumentException("FROM must reference a module identifier");
            }
            List<String> projections = new ArrayList<>();
            for (SelectItem item : ps.getSelectItems()) projections.add(item.toString());
            List<ModuleSqlAst.SortSpec> sorts = new ArrayList<>();
            if (ps.getOrderByElements() != null) {
                for (OrderByElement e : ps.getOrderByElements()) {
                    sorts.add(new ModuleSqlAst.SortSpec(e.getExpression().toString(), e.isAsc()));
                }
            }
            Integer limit = null;
            Integer offset = null;
            Limit l = ps.getLimit();
            if (l != null && l.getRowCount() != null) limit = ((Number) l.getRowCount()).intValue();
            if (l != null && l.getOffset() != null) offset = ((Number) l.getOffset()).intValue();
            return new ModuleSqlAst(table.getName(), projections, ps.getWhere(), sorts, limit, offset);
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw e;
            throw new IllegalArgumentException("Invalid module SQL: " + e.getMessage(), e);
        }
    }
}
