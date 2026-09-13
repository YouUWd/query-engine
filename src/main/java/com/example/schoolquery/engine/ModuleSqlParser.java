package com.example.schoolquery.engine;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.util.ArrayList;
import java.util.List;

/** SQL syntax boundary; metadata semantics are resolved by the compiler. */
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
                for (OrderByElement e : ps.getOrderByElements()) sorts.add(new ModuleSqlAst.SortSpec(e.getExpression().toString(), e.isAsc()));
            }
            Integer limit = null, offset = null;
            Limit l = ps.getLimit();
            if (l != null) {
                limit = longValue(l.getRowCount(), "LIMIT");
                if (l.getOffset() != null) offset = longValue(l.getOffset(), "OFFSET");
            }
            return new ModuleSqlAst(table.getName(), projections, ps.getWhere(), sorts, limit, offset);
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw e;
            throw new IllegalArgumentException("Invalid module SQL: " + e.getMessage(), e);
        }
    }

    private int longValue(Expression expression, String keyword) {
        if (!(expression instanceof LongValue v)) throw new IllegalArgumentException(keyword + " must be a numeric literal");
        long value = v.getValue();
        if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException(keyword + " is out of range");
        return (int) value;
    }
}
