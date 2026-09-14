package com.example.schoolquery.mutation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.plan.LogicalFieldRef;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles standard INSERT/UPDATE/DELETE syntax into a physical-independent plan. */
public final class MutationCompiler {
    private static final Pattern MODULE_SOURCE = Pattern.compile("(?i)\\bmodule\\s*\\(\\s*('(?:''|[^'])*'|\\d+)\\s*\\)");
    private final MetadataRegistry registry;
    public MutationCompiler(MetadataRegistry registry) { this.registry = Objects.requireNonNull(registry); }

    public MutationPlan compile(String sql) {
        try {
            Statement s = CCJSqlParserUtil.parse(normalizeModuleSource(sql));
            if (s instanceof Insert i) return compileInsert(i);
            if (s instanceof Update u) return compileUpdate(u);
            if (s instanceof Delete d) return compileDelete(d);
            throw new IllegalArgumentException("Only INSERT/UPDATE/DELETE are supported: " + s.getClass().getSimpleName());
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("Invalid module mutation SQL", e);
        }
    }

    private String normalizeModuleSource(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("Mutation SQL cannot be blank");
        Matcher matcher = MODULE_SOURCE.matcher(sql);
        StringBuffer result = new StringBuffer(); boolean found = false;
        while (matcher.find()) {
            found = true; String token = matcher.group(1);
            if (token.startsWith("'") && token.endsWith("'")) token = token.substring(1, token.length() - 1).replace("''", "'");
            SysModule module = registry.module(token);
            if (module.isVirtual()) throw new IllegalArgumentException("Cannot mutate virtual module: " + module.id());
            matcher.appendReplacement(result, Matcher.quoteReplacement("`" + module.primaryTable() + "`"));
        }
        matcher.appendTail(result); return found ? result.toString() : sql;
    }

    private SysModule moduleForTable(String rawTable) {
        String table = rawTable == null ? "" : rawTable.replace("`", "");
        for (SysModule module : registry.allModules()) if (!module.isVirtual() && module.primaryTable().equalsIgnoreCase(table)) return module;
        throw new IllegalArgumentException("Unknown module physical table: " + rawTable);
    }

    private MutationPlan compileInsert(Insert insert) {
        SysModule module = moduleForTable(insert.getTable().getName());
        if (insert.getColumns() == null || insert.getColumns().isEmpty()) throw new IllegalArgumentException("INSERT must specify columns");
        Select select = insert.getSelect(); if (select == null) throw new IllegalArgumentException("INSERT must specify VALUES");
        String body = select.toString().trim(); if (body.regionMatches(true, 0, "VALUES", 0, 6)) body = body.substring(6).trim();
        if (!body.startsWith("(") || !body.endsWith(")")) throw new IllegalArgumentException("Only a single-row VALUES INSERT is supported");
        List<String> values = splitValues(body.substring(1, body.length() - 1));
        if (values.size() != insert.getColumns().size()) throw new IllegalArgumentException("INSERT column/value count mismatch");
        List<MutationPlan.Assignment> a = new ArrayList<>();
        for (int i = 0; i < insert.getColumns().size(); i++) a.add(new MutationPlan.Assignment(resolve(module.id(), insert.getColumns().get(i).getColumnName()), literal(values.get(i))));
        return new MutationPlan(MutationPlan.Operation.INSERT, module.id(), a, List.of(), null);
    }

    private MutationPlan compileUpdate(Update update) {
        SysModule module = moduleForTable(update.getTable().getName()); List<MutationPlan.Assignment> a = new ArrayList<>();
        for (int i = 0; i < update.getColumns().size(); i++) a.add(new MutationPlan.Assignment(resolve(module.id(), update.getColumns().get(i).getColumnName()), literal(update.getExpressions().get(i).toString())));
        return new MutationPlan(MutationPlan.Operation.UPDATE, module.id(), a, List.of(), where(module.id(), update.getWhere()));
    }

    private MutationPlan compileDelete(Delete delete) {
        SysModule module = moduleForTable(delete.getTable().getName());
        return new MutationPlan(MutationPlan.Operation.DELETE, module.id(), List.of(), List.of(), where(module.id(), delete.getWhere()));
    }

    private MutationPlan.Where where(long moduleId, Expression e) {
        return e == null ? null : new MutationPlan.Where(compileWhereExpression(moduleId, e));
    }

    private MutationPlan.Expression compileWhereExpression(long moduleId, Expression e) {
        if (e instanceof AndExpression a)
            return new MutationPlan.And(compileWhereExpression(moduleId, a.getLeftExpression()), compileWhereExpression(moduleId, a.getRightExpression()));
        if (e instanceof OrExpression o)
            return new MutationPlan.Or(compileWhereExpression(moduleId, o.getLeftExpression()), compileWhereExpression(moduleId, o.getRightExpression()));
        return new MutationPlan.PredicateExpression(predicate(moduleId, e));
    }

    private MutationPlan.Predicate predicate(long moduleId, Expression e) {
        if (e instanceof IsNullExpression x) return new MutationPlan.Predicate(resolve(moduleId, x.getLeftExpression().toString()), x.isNot() ? "IS_NOT_NULL" : "IS_NULL", null);
        if (e instanceof Between x) return new MutationPlan.Predicate(resolve(moduleId, x.getLeftExpression().toString()), "BETWEEN", List.of(literal(x.getBetweenExpressionStart().toString()), literal(x.getBetweenExpressionEnd().toString())));
        if (e instanceof InExpression x) { String text = x.getRightExpression() == null ? "" : x.getRightExpression().toString(); return new MutationPlan.Predicate(resolve(moduleId, x.getLeftExpression().toString()), x.isNot() ? "NOT_IN" : "IN", parseList(text)); }
        if (e instanceof BinaryExpression x) return new MutationPlan.Predicate(resolve(moduleId, x.getLeftExpression().toString()), operator(x), literal(x.getRightExpression().toString()));
        throw new IllegalArgumentException("Unsupported DML WHERE expression: " + e);
    }

    private String operator(BinaryExpression e) {
        if (e instanceof EqualsTo) return "EQ"; if (e instanceof NotEqualsTo) return "NE"; if (e instanceof GreaterThan) return "GT";
        if (e instanceof GreaterThanEquals) return "GE"; if (e instanceof MinorThan) return "LT"; if (e instanceof MinorThanEquals) return "LE";
        if (e instanceof LikeExpression) return "LIKE"; throw new IllegalArgumentException("Unsupported DML operator: " + e);
    }

    private List<Object> parseList(String text) {
        String x = text.trim(); if (x.startsWith("(") && x.endsWith(")")) x = x.substring(1, x.length() - 1).trim();
        if (x.isEmpty()) return List.of(); return splitValues(x).stream().map(this::literal).toList();
    }

    private LogicalFieldRef resolve(long moduleId, String raw) {
        String x = raw.trim().replace("`", "");
        if (x.matches("f\\d+")) return resolveId(moduleId, Long.parseLong(x.substring(1)));
        if (x.matches("\\d+")) return resolveId(moduleId, Long.parseLong(x));
        String column = x.contains(".") ? x.substring(x.lastIndexOf('.') + 1) : x; List<SysModuleField> m = new ArrayList<>();
        for (List<SysModuleField> fs : registry.fieldsGroupedByTable(moduleId).values()) for (SysModuleField f : fs) if (f.columnName().equals(column)) m.add(f);
        if (m.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous field: " + raw);
        SysModuleField f = m.get(0); return new LogicalFieldRef(f.moduleId(), f.id());
    }

    private LogicalFieldRef resolveId(long moduleId, long fieldId) {
        SysModuleField f = registry.field(fieldId);
        if (!registry.ancestorChain(f.moduleId()).contains(moduleId)) throw new IllegalArgumentException("fieldId=" + fieldId + " is outside module " + moduleId);
        return new LogicalFieldRef(f.moduleId(), f.id());
    }

    private Object literal(String text) {
        String x = text.trim(); if (x.startsWith("'") && x.endsWith("'")) return x.substring(1, x.length() - 1).replace("''", "'");
        if (x.equalsIgnoreCase("null")) return null; if (x.equalsIgnoreCase("true")) return true; if (x.equalsIgnoreCase("false")) return false;
        try { return Long.parseLong(x); } catch (NumberFormatException ignored) { } try { return Double.parseDouble(x); } catch (NumberFormatException ignored) { } return x;
    }

    private List<String> splitValues(String text) {
        List<String> r = new ArrayList<>(); StringBuilder c = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < text.length(); i++) { char ch = text.charAt(i); if (ch == '\'' && (i + 1 >= text.length() || text.charAt(i + 1) != '\'')) quoted = !quoted; if (ch == ',' && !quoted) { r.add(c.toString().trim()); c.setLength(0); } else c.append(ch); }
        r.add(c.toString().trim()); return r;
    }
}
