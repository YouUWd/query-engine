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

/** Compiles Module SQL mutations into a physical-independent logical plan. */
public final class MutationCompiler {
    private static final Pattern MODULE_SOURCE = Pattern.compile("(?i)\\bmodule\\s*\\(\\s*('(?:''|[^'])*'|\\d+)\\s*\\)");
    private final MetadataRegistry registry;
    public MutationCompiler(MetadataRegistry registry) { this.registry = Objects.requireNonNull(registry); }

    public MutationPlan compile(String sql) {
        try {
            if (sql == null || sql.isBlank()) throw new IllegalArgumentException("Mutation SQL cannot be blank");
            ModuleSource source = moduleSource(sql);
            Statement s = CCJSqlParserUtil.parse(source.normalizedSql());
            if (s instanceof Insert i) return compileInsert(i, source.module());
            if (s instanceof Update u) return compileUpdate(u, source.module());
            if (s instanceof Delete d) return compileDelete(d, source.module());
            throw new IllegalArgumentException("Only INSERT/UPDATE/DELETE are supported: " + s.getClass().getSimpleName());
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("Invalid module mutation SQL", e);
        }
    }

    private ModuleSource moduleSource(String sql) {
        Matcher matcher = MODULE_SOURCE.matcher(sql);
        SysModule explicit = null;
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            if (explicit != null) throw new IllegalArgumentException("Only one module(...) source is supported in mutation SQL");
            String token = matcher.group(1);
            if (token.startsWith("'") && token.endsWith("'")) token = token.substring(1, token.length() - 1).replace("''", "'");
            explicit = registry.module(token);
            if (explicit.isVirtual()) throw new IllegalArgumentException("Cannot mutate virtual module: " + explicit.id());
            matcher.appendReplacement(result, Matcher.quoteReplacement("`" + explicit.primaryTable() + "`"));
        }
        matcher.appendTail(result);
        return new ModuleSource(result.toString(), explicit);
    }

    private SysModule moduleForTable(String rawTable) {
        String table = rawTable == null ? "" : rawTable.replace("`", "");
        List<SysModule> matches = registry.allModules().stream()
                .filter(m -> !m.isVirtual() && m.primaryTable().equalsIgnoreCase(table)).toList();
        if (matches.size() != 1) {
            if (matches.isEmpty()) throw new IllegalArgumentException("Unknown module physical table: " + rawTable);
            throw new IllegalArgumentException("Ambiguous physical table '" + rawTable + "'; use module(moduleIdOrCode)");
        }
        return matches.get(0);
    }

    private SysModule targetModule(SysModule explicit, String physicalTable) {
        return explicit != null ? explicit : moduleForTable(physicalTable);
    }

    private MutationPlan compileInsert(Insert insert, SysModule explicit) {
        SysModule module = targetModule(explicit, insert.getTable().getName());
        if (insert.getColumns() == null || insert.getColumns().isEmpty()) throw new IllegalArgumentException("INSERT must specify columns");
        Select select = insert.getSelect();
        if (select == null) throw new IllegalArgumentException("INSERT must specify VALUES");
        String body = select.toString().trim();
        if (body.regionMatches(true, 0, "VALUES", 0, 6)) body = body.substring(6).trim();
        if (!body.startsWith("(") || !body.endsWith(")")) throw new IllegalArgumentException("Only a single-row VALUES INSERT is supported");
        List<String> values = splitValues(body.substring(1, body.length() - 1));
        if (values.size() != insert.getColumns().size()) throw new IllegalArgumentException("INSERT column/value count mismatch");
        List<MutationPlan.Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < insert.getColumns().size(); i++) {
            assignments.add(new MutationPlan.Assignment(resolveWritable(module.id(), insert.getColumns().get(i).getColumnName()), literal(values.get(i))));
        }
        return new MutationPlan(MutationPlan.Operation.INSERT, module.id(), assignments, List.of(), null);
    }

    private MutationPlan compileUpdate(Update update, SysModule explicit) {
        SysModule module = targetModule(explicit, update.getTable().getName());
        List<MutationPlan.Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < update.getColumns().size(); i++) {
            assignments.add(new MutationPlan.Assignment(resolveWritable(module.id(), update.getColumns().get(i).getColumnName()), literal(update.getExpressions().get(i).toString())));
        }
        return new MutationPlan(MutationPlan.Operation.UPDATE, module.id(), assignments, List.of(), where(module.id(), update.getWhere()));
    }

    private MutationPlan compileDelete(Delete delete, SysModule explicit) {
        SysModule module = targetModule(explicit, delete.getTable().getName());
        return new MutationPlan(MutationPlan.Operation.DELETE, module.id(), List.of(), List.of(), where(module.id(), delete.getWhere()));
    }

    private MutationPlan.Where where(long moduleId, Expression e) {
        return e == null ? null : new MutationPlan.Where(compileWhereExpression(moduleId, e));
    }

    private MutationPlan.Expression compileWhereExpression(long moduleId, Expression e) {
        if (e instanceof AndExpression a) return new MutationPlan.And(compileWhereExpression(moduleId, a.getLeftExpression()), compileWhereExpression(moduleId, a.getRightExpression()));
        if (e instanceof OrExpression o) return new MutationPlan.Or(compileWhereExpression(moduleId, o.getLeftExpression()), compileWhereExpression(moduleId, o.getRightExpression()));
        return new MutationPlan.PredicateExpression(predicate(moduleId, e));
    }

    private MutationPlan.Predicate predicate(long moduleId, Expression e) {
        if (e instanceof IsNullExpression x) return new MutationPlan.Predicate(resolveWritable(moduleId, x.getLeftExpression().toString()), x.isNot() ? "IS_NOT_NULL" : "IS_NULL", null);
        if (e instanceof Between x) return new MutationPlan.Predicate(resolveWritable(moduleId, x.getLeftExpression().toString()), "BETWEEN", List.of(literal(x.getBetweenExpressionStart().toString()), literal(x.getBetweenExpressionEnd().toString())));
        if (e instanceof InExpression x) {
            String text = x.getRightExpression() == null ? "" : x.getRightExpression().toString();
            return new MutationPlan.Predicate(resolveWritable(moduleId, x.getLeftExpression().toString()), x.isNot() ? "NOT_IN" : "IN", parseList(text));
        }
        if (e instanceof BinaryExpression x) return new MutationPlan.Predicate(resolveWritable(moduleId, x.getLeftExpression().toString()), operator(x), literal(x.getRightExpression().toString()));
        throw new IllegalArgumentException("Unsupported DML WHERE expression: " + e);
    }

    private String operator(BinaryExpression e) {
        if (e instanceof EqualsTo) return "EQ";
        if (e instanceof NotEqualsTo) return "NE";
        if (e instanceof GreaterThan) return "GT";
        if (e instanceof GreaterThanEquals) return "GE";
        if (e instanceof MinorThan) return "LT";
        if (e instanceof MinorThanEquals) return "LE";
        if (e instanceof LikeExpression) return "LIKE";
        throw new IllegalArgumentException("Unsupported DML operator: " + e);
    }

    private List<Object> parseList(String text) {
        String x = text.trim();
        if (x.startsWith("(") && x.endsWith(")")) x = x.substring(1, x.length() - 1).trim();
        if (x.isEmpty()) return List.of();
        return splitValues(x).stream().map(this::literal).toList();
    }

    /** A Module SQL field may target any physical table owned by the logical module. */
    private LogicalFieldRef resolveWritable(long moduleId, String raw) {
        String x = raw.trim().replace("`", "");
        if (x.matches("f\\d+")) return resolveWritableId(moduleId, Long.parseLong(x.substring(1)));
        if (x.matches("\\d+")) return resolveWritableId(moduleId, Long.parseLong(x));
        String column = x.contains(".") ? x.substring(x.lastIndexOf('.') + 1) : x;
        List<SysModuleField> matches = new ArrayList<>();
        for (List<SysModuleField> fs : registry.fieldsGroupedByTable(moduleId).values()) {
            for (SysModuleField f : fs) if (f.columnName().equalsIgnoreCase(column)) matches.add(f);
        }
        if (matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous field: " + raw);
        SysModuleField f = matches.get(0);
        return new LogicalFieldRef(f.moduleId(), f.id());
    }

    private LogicalFieldRef resolveWritableId(long moduleId, long fieldId) {
        SysModuleField f = registry.field(fieldId);
        if (!registry.ancestorChain(f.moduleId()).contains(moduleId)) throw new IllegalArgumentException("fieldId=" + fieldId + " is outside module " + moduleId);
        return new LogicalFieldRef(f.moduleId(), f.id());
    }

    private Object literal(String text) {
        String x = text.trim();
        if (x.startsWith("'") && x.endsWith("'")) return x.substring(1, x.length() - 1).replace("''", "'");
        if (x.equalsIgnoreCase("null")) return null;
        if (x.equalsIgnoreCase("true")) return true;
        if (x.equalsIgnoreCase("false")) return false;
        try { return Long.parseLong(x); } catch (NumberFormatException ignored) { }
        try { return Double.parseDouble(x); } catch (NumberFormatException ignored) { }
        return x;
    }

    private List<String> splitValues(String text) {
        List<String> r = new ArrayList<>();
        StringBuilder c = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'' && (i + 1 >= text.length() || text.charAt(i + 1) != '\'')) quoted = !quoted;
            if (ch == ',' && !quoted) { r.add(c.toString().trim()); c.setLength(0); }
            else c.append(ch);
        }
        r.add(c.toString().trim());
        return r;
    }

    private record ModuleSource(String normalizedSql, SysModule module) {}
}
