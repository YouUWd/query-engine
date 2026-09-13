package com.example.schoolquery.mutation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.plan.LogicalFieldRef;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;

import java.util.*;

/**
 * Compiles standard INSERT/UPDATE/DELETE syntax into a physical-independent plan.
 * Nested aggregate saves intentionally do not use SQL syntax; use AggregateMutation.
 */
public final class MutationCompiler {
    private final MetadataRegistry registry;

    public MutationCompiler(MetadataRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public MutationPlan compile(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Insert insert) return compileInsert(insert);
            if (statement instanceof Update update) return compileUpdate(update);
            if (statement instanceof Delete delete) return compileDelete(delete);
            throw new IllegalArgumentException("Only INSERT/UPDATE/DELETE are supported: " + statement.getClass().getSimpleName());
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("Invalid module mutation SQL", e);
        }
    }

    private MutationPlan compileInsert(Insert insert) {
        SysModule module = registry.module(insert.getTable().getName());
        if (insert.getColumns() == null || insert.getColumns().isEmpty()) {
            throw new IllegalArgumentException("INSERT must specify columns");
        }
        if (insert.getItemsList() == null) {
            throw new IllegalArgumentException("INSERT must specify VALUES");
        }
        String valuesText = insert.getItemsList().toString();
        String body = valuesText.trim();
        if (!body.startsWith("(") || !body.endsWith(")")) {
            throw new IllegalArgumentException("Only a single-row VALUES INSERT is supported");
        }
        List<String> values = splitValues(body.substring(1, body.length() - 1));
        if (values.size() != insert.getColumns().size()) {
            throw new IllegalArgumentException("INSERT column/value count mismatch");
        }
        List<MutationPlan.Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < insert.getColumns().size(); i++) {
            assignments.add(new MutationPlan.Assignment(
                    resolve(module.id(), insert.getColumns().get(i).getColumnName()),
                    literal(values.get(i))));
        }
        return new MutationPlan(MutationPlan.Operation.INSERT, module.id(), assignments, List.of(), null);
    }

    private MutationPlan compileUpdate(Update update) {
        SysModule module = registry.module(update.getTable().getName());
        List<MutationPlan.Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < update.getColumns().size(); i++) {
            assignments.add(new MutationPlan.Assignment(
                    resolve(module.id(), update.getColumns().get(i).getColumnName()),
                    literal(update.getExpressions().get(i).toString())));
        }
        return new MutationPlan(MutationPlan.Operation.UPDATE, module.id(), assignments,
                List.of(), where(module.id(), update.getWhere()));
    }

    private MutationPlan compileDelete(Delete delete) {
        SysModule module = registry.module(delete.getTable().getName());
        return new MutationPlan(MutationPlan.Operation.DELETE, module.id(), List.of(), List.of(),
                where(module.id(), delete.getWhere()));
    }

    private MutationPlan.Where where(long moduleId, Expression expression) {
        if (expression == null) return null;
        if (!(expression instanceof EqualsTo equals)) {
            throw new IllegalArgumentException("DML WHERE currently supports only equality predicates");
        }
        return new MutationPlan.Where(List.of(new MutationPlan.Predicate(
                resolve(moduleId, equals.getLeftExpression().toString()),
                "EQ", literal(equals.getRightExpression().toString()))));
    }

    private LogicalFieldRef resolve(long moduleId, String raw) {
        String x = raw.trim().replace("`", "");
        if (x.matches("f\\d+")) return resolveId(moduleId, Long.parseLong(x.substring(1)));
        if (x.matches("\\d+")) return resolveId(moduleId, Long.parseLong(x));
        String column = x.contains(".") ? x.substring(x.lastIndexOf('.') + 1) : x;
        List<SysModuleField> matches = new ArrayList<>();
        for (List<SysModuleField> fields : registry.fieldsGroupedByTable(moduleId).values()) {
            for (SysModuleField field : fields) if (field.columnName().equals(column)) matches.add(field);
        }
        if (matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous field: " + raw);
        SysModuleField field = matches.get(0);
        return new LogicalFieldRef(field.moduleId(), field.id());
    }

    private LogicalFieldRef resolveId(long moduleId, long fieldId) {
        SysModuleField field = registry.field(fieldId);
        if (!registry.ancestorChain(field.moduleId()).contains(moduleId)) {
            throw new IllegalArgumentException("fieldId=" + fieldId + " is outside module " + moduleId);
        }
        return new LogicalFieldRef(field.moduleId(), field.id());
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
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && (i + 1 >= text.length() || text.charAt(i + 1) != '\'')) quoted = !quoted;
            if (c == ',' && !quoted) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().trim());
        return result;
    }
}
