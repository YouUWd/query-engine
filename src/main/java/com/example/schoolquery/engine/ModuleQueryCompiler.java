package com.example.schoolquery.engine;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.plan.*;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import java.util.*;

/** Semantic compiler: identifiers are resolved here; SQL rendering is downstream. */
public final class ModuleQueryCompiler {
    private final MetadataRegistry registry;
    private final ModuleSqlParser parser = new ModuleSqlParser();

    public ModuleQueryCompiler(MetadataRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public QueryPlan compile(String sql) {
        ModuleSqlAst ast = parser.parseSelect(sql);
        SysModule root = registry.module(ast.rootModuleToken());
        List<LogicalFieldRef> projections = new ArrayList<>();
        List<ProjectionToken> tokens = new ArrayList<>();
        for (String token : ast.projectionTokens()) tokens.add(projectionToken(token));

        Set<String> explicitAliases = new HashSet<>();
        for (ProjectionToken token : tokens) {
            if (token.alias() != null && !explicitAliases.add(token.alias().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate projection alias: " + token.alias());
        }

        List<String> aliases = new ArrayList<>();
        Set<String> usedAliases = new HashSet<>();
        for (ProjectionToken projection : tokens) {
            if ("*".equals(projection.expression())) {
                for (LogicalFieldRef ref : allVisible(root.id())) {
                    projections.add(ref);
                    aliases.add(uniqueDefaultAlias(registry.field(ref.fieldId()).columnName(), usedAliases, explicitAliases));
                }
            } else {
                LogicalFieldRef ref = resolve(root.id(), projection.expression());
                projections.add(ref);
                if (projection.alias() != null) {
                    aliases.add(projection.alias());
                    usedAliases.add(projection.alias().toLowerCase(Locale.ROOT));
                } else {
                    aliases.add(uniqueDefaultAlias(registry.field(ref.fieldId()).columnName(), usedAliases, explicitAliases));
                }
            }
        }

        List<FilterPlan> flat = new ArrayList<>();
        FilterExpressionPlan expr = ast.where() == null ? null : compileFilter(root.id(), ast.where(), flat);
        List<SortPlan.SortItem> sorts = new ArrayList<>();
        for (ModuleSqlAst.SortSpec sort : ast.sorts()) {
            sorts.add(new SortPlan.SortItem(resolve(root.id(), sort.expression()),
                    sort.ascending() ? SortPlan.Direction.ASC : SortPlan.Direction.DESC));
        }
        PaginationPlan page = null;
        if (ast.limit() != null) {
            int offset = ast.offset() == null ? 0 : ast.offset();
            page = new PaginationPlan(offset / ast.limit() + 1, ast.limit(), offset);
        }
        return new QueryPlan(root.id(), projections, List.of(), flat, expr, new SortPlan(sorts), page, aliases);
    }

    private String uniqueDefaultAlias(String base, Set<String> used, Set<String> explicitAliases) {
        String candidate = base;
        int suffix = 2;
        while (used.contains(candidate.toLowerCase(Locale.ROOT)) || explicitAliases.contains(candidate.toLowerCase(Locale.ROOT)))
            candidate = base + "_" + suffix++;
        used.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    private FilterExpressionPlan compileFilter(long root, Expression e, List<FilterPlan> flat) {
        if (e instanceof AndExpression a) {
            return new FilterExpressionPlan.And(List.of(
                    compileFilter(root, a.getLeftExpression(), flat),
                    compileFilter(root, a.getRightExpression(), flat)));
        }
        if (e instanceof OrExpression o) {
            return new FilterExpressionPlan.Or(List.of(
                    compileFilter(root, o.getLeftExpression(), flat),
                    compileFilter(root, o.getRightExpression(), flat)));
        }
        FilterPlan p = predicate(root, e);
        flat.add(p);
        return new FilterExpressionPlan.Predicate(p);
    }

    private FilterPlan predicate(long root, Expression e) {
        if (e instanceof IsNullExpression x) {
            return new FilterPlan(resolve(root, x.getLeftExpression().toString()),
                    x.isNot() ? FilterPlan.Operator.IS_NOT_NULL : FilterPlan.Operator.IS_NULL, null);
        }
        if (e instanceof Between x) {
            return new FilterPlan(resolve(root, x.getLeftExpression().toString()), FilterPlan.Operator.BETWEEN,
                    List.of(value(x.getBetweenExpressionStart()), value(x.getBetweenExpressionEnd())));
        }
        if (e instanceof InExpression x) {
            return new FilterPlan(resolve(root, x.getLeftExpression().toString()), FilterPlan.Operator.IN,
                    parseList(x.getRightExpression() == null ? "" : x.getRightExpression().toString()));
        }
        if (e instanceof BinaryExpression x) {
            return new FilterPlan(resolve(root, x.getLeftExpression().toString()), operator(x), value(x.getRightExpression()));
        }
        throw new IllegalArgumentException("Unsupported WHERE expression: " + e);
    }

    private FilterPlan.Operator operator(BinaryExpression e) {
        if (e instanceof EqualsTo) return FilterPlan.Operator.EQ;
        if (e instanceof NotEqualsTo) return FilterPlan.Operator.NE;
        if (e instanceof GreaterThan) return FilterPlan.Operator.GT;
        if (e instanceof GreaterThanEquals) return FilterPlan.Operator.GE;
        if (e instanceof MinorThan) return FilterPlan.Operator.LT;
        if (e instanceof MinorThanEquals) return FilterPlan.Operator.LE;
        if (e instanceof LikeExpression) return FilterPlan.Operator.LIKE;
        throw new IllegalArgumentException("Unsupported operator: " + e);
    }

    private Object value(Expression e) {
        if (e instanceof LongValue x) return x.getValue();
        if (e instanceof StringValue x) return x.getValue();
        if (e instanceof NullValue) return null;
        return literal(e.toString());
    }

    private List<Object> parseList(String value) {
        String x = value.trim();
        if (x.startsWith("(") && x.endsWith(")")) x = x.substring(1, x.length() - 1).trim();
        if (x.isEmpty()) return List.of();
        return Arrays.stream(x.split(",")).map(String::trim).map(this::literal).toList();
    }

    private Object literal(String value) {
        String x = value.trim();
        if (x.startsWith("'") && x.endsWith("'")) return x.substring(1, x.length() - 1).replace("''", "'");
        if ("true".equalsIgnoreCase(x)) return true;
        if ("false".equalsIgnoreCase(x)) return false;
        if ("null".equalsIgnoreCase(x)) return null;
        try { return Long.parseLong(x); } catch (NumberFormatException ignored) { }
        try { return Double.parseDouble(x); } catch (NumberFormatException ignored) { }
        return x;
    }

    private LogicalFieldRef resolve(long root, String raw) {
        String x = raw.trim().replace("`", "");
        if (x.matches("f\\d+")) return ref(root, Long.parseLong(x.substring(1)));
        if (x.matches("\\d+")) return ref(root, Long.parseLong(x));
        String[] parts = x.split("\\.");
        List<SysModuleField> candidates = new ArrayList<>();
        for (SysModule module : registry.allModules()) {
            if (!registry.ancestorChain(module.id()).contains(root)) continue;
            for (List<SysModuleField> fields : registry.fieldsGroupedByTable(module.id()).values()) {
                for (SysModuleField field : fields) if (match(field, parts)) candidates.add(field);
            }
        }
        Map<Long, SysModuleField> unique = new LinkedHashMap<>();
        candidates.forEach(f -> unique.put(f.id(), f));
        if (unique.size() == 1) {
            SysModuleField f = unique.values().iterator().next();
            return new LogicalFieldRef(f.moduleId(), f.id());
        }
        if (unique.isEmpty()) throw new IllegalArgumentException("Unknown field: " + raw);
        throw new IllegalArgumentException("Ambiguous field: " + raw + "; use fieldId or module.table.column");
    }

    private boolean match(SysModuleField f, String[] p) {
        if (p.length == 1) return f.columnName().equals(p[0]);
        if (p.length == 2) return f.tableName().equals(p[0]) && f.columnName().equals(p[1]);
        if (p.length == 3) return f.tableName().equals(p[1]) && f.columnName().equals(p[2]);
        return false;
    }

    private LogicalFieldRef ref(long root, long id) {
        SysModuleField f = registry.field(id);
        if (!registry.ancestorChain(f.moduleId()).contains(root))
            throw new IllegalArgumentException("fieldId=" + id + " is outside root " + root);
        return new LogicalFieldRef(f.moduleId(), f.id());
    }

    private List<LogicalFieldRef> allVisible(long root) {
        List<LogicalFieldRef> r = new ArrayList<>();
        for (SysModule m : registry.allModules()) if (registry.ancestorChain(m.id()).contains(root)) {
            for (List<SysModuleField> fs : registry.fieldsGroupedByTable(m.id()).values()) {
                for (SysModuleField f : fs) r.add(new LogicalFieldRef(f.moduleId(), f.id()));
            }
        }
        return r;
    }

    private ProjectionToken projectionToken(String value) {
        String x = value.trim();
        int as = x.toLowerCase(Locale.ROOT).lastIndexOf(" as ");
        if (as > 0) return new ProjectionToken(x.substring(0, as).trim(), cleanAlias(x.substring(as + 4)));
        String[] parts = x.split("\\s+");
        if (parts.length == 2 && parts[1].matches("[A-Za-z_][A-Za-z0-9_]*"))
            return new ProjectionToken(parts[0], cleanAlias(parts[1]));
        return new ProjectionToken(x, null);
    }

    private String cleanAlias(String alias) {
        return alias.trim().replace("`", "").replace("\"", "");
    }

    private record ProjectionToken(String expression, String alias) {}
}
