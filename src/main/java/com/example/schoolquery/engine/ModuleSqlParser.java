package com.example.schoolquery.engine;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQL syntax boundary; metadata semantics are resolved by the compiler. */
public final class ModuleSqlParser {
    private static final Pattern MODULE_FROM = Pattern.compile(
            "(?i)(\\bFROM\\s+)module\\s*\\(\\s*(?:'([^']+)'|`([^`]+)`|(\\d+))\\s*\\)");

    public ModuleSqlAst parseSelect(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("sql must not be blank");
        try {
            ParsedSource source = normalizeModuleSource(sql);
            Statement statement = CCJSqlParserUtil.parse(source.sql());
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
            Integer limit = null, offset = null;
            Limit l = ps.getLimit();
            if (l != null) {
                limit = longValue(l.getRowCount(), "LIMIT");
                if (l.getOffset() != null) offset = longValue(l.getOffset(), "OFFSET");
            }
            String rootToken = source.explicitRootToken() == null ? table.getName() : source.explicitRootToken();
            return new ModuleSqlAst(rootToken, projections, ps.getWhere(), sorts, limit, offset);
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw e;
            throw new IllegalArgumentException("Invalid module SQL: " + e.getMessage(), e);
        }
    }

    private ParsedSource normalizeModuleSource(String sql) {
        Matcher m = MODULE_FROM.matcher(sql);
        if (!m.find()) return new ParsedSource(sql, null);
        String token = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
        String replacement = m.group(1) + "`" + token.replace("`", "") + "`";
        return new ParsedSource(sql.substring(0, m.start()) + replacement + sql.substring(m.end()), token);
    }

    private int longValue(Expression expression, String keyword) {
        if (!(expression instanceof LongValue v)) throw new IllegalArgumentException(keyword + " must be a numeric literal");
        long value = v.getValue();
        if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException(keyword + " is out of range");
        return (int) value;
    }

    private record ParsedSource(String sql, String explicitRootToken) {}
}
