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
    public ModuleQueryCompiler(MetadataRegistry registry) { this.registry=Objects.requireNonNull(registry); }
    public QueryPlan compile(String sql) {
        ModuleSqlAst ast=parser.parseSelect(sql); SysModule root=registry.module(ast.rootModuleToken());
        List<LogicalFieldRef> projections=new ArrayList<>();
        for(String token:ast.projectionTokens()){String expression=stripAlias(token);if("*".equals(expression))projections.addAll(allVisible(root.id()));else projections.add(resolve(root.id(),expression));}
        List<FilterPlan> flat=new ArrayList<>(); FilterExpressionPlan expr=ast.where()==null?null:compileFilter(root.id(),ast.where(),flat);
        List<SortPlan.SortItem> sorts=new ArrayList<>(); for(ModuleSqlAst.SortSpec sort:ast.sorts())sorts.add(new SortPlan.SortItem(resolve(root.id(),sort.expression()),sort.ascending()?SortPlan.Direction.ASC:SortPlan.Direction.DESC));
        PaginationPlan page=null; if(ast.limit()!=null){int offset=ast.offset()==null?0:ast.offset(); page=new PaginationPlan(offset/ast.limit()+1,ast.limit(),offset);}
        return new QueryPlan(root.id(),projections,List.of(),flat,expr,new SortPlan(sorts),page);
    }
    private FilterExpressionPlan compileFilter(long root,Expression expression,List<FilterPlan> flat){if(expression instanceof AndExpression and)return new FilterExpressionPlan.And(List.of(compileFilter(root,and.getLeftExpression(),flat),compileFilter(root,and.getRightExpression(),flat)));if(expression instanceof OrExpression or)return new FilterExpressionPlan.Or(List.of(compileFilter(root,or.getLeftExpression(),flat),compileFilter(root,or.getRightExpression(),flat)));FilterPlan p=predicate(root,expression);flat.add(p);return new FilterExpressionPlan.Predicate(p);}
    private FilterPlan predicate(long root,Expression expression){if(expression instanceof IsNullExpression x)return new FilterPlan(resolve(root,x.getLeftExpression().toString()),x.isNot()?FilterPlan.Operator.IS_NOT_NULL:FilterPlan.Operator.IS_NULL,null);if(expression instanceof Between x)return new FilterPlan(resolve(root,x.getLeftExpression().toString()),FilterPlan.Operator.BETWEEN,List.of(value(x.getBetweenExpressionStart()),value(x.getBetweenExpressionEnd())));if(expression instanceof InExpression x){String items=x.getRightItemsList()==null?"":x.getRightItemsList().toString();return new FilterPlan(resolve(root,x.getLeftExpression().toString()),FilterPlan.Operator.IN,parseList(items));}if(expression instanceof BinaryExpression x)return new FilterPlan(resolve(root,x.getLeftExpression().toString()),operator(x),value(x.getRightExpression()));throw new IllegalArgumentException("Unsupported WHERE expression: "+expression);}
    private FilterPlan.Operator operator(BinaryExpression e){if(e instanceof EqualsTo)return FilterPlan.Operator.EQ;if(e instanceof NotEqualsTo)return FilterPlan.Operator.NE;if(e instanceof GreaterThan)return FilterPlan.Operator.GT;if(e instanceof GreaterThanEquals)return FilterPlan.Operator.GE;if(e instanceof MinorThan)return FilterPlan.Operator.LT;if(e instanceof MinorThanEquals)return FilterPlan.Operator.LE;if(e instanceof LikeExpression)return FilterPlan.Operator.LIKE;throw new IllegalArgumentException("Unsupported operator: "+e);}
    private Object value(Expression e){if(e instanceof LongValue x)return x.getValue();if(e instanceof StringValue x)return x.getValue();if(e instanceof BooleanValue x)return x.getValue();if(e instanceof NullValue)return null;return literal(e.toString());}
    private List<Object> parseList(String value){String x=value.replace("(","").replace(")","").trim();if(x.isEmpty())return List.of();return Arrays.stream(x.split(",")).map(String::trim).map(this::literal).toList();}
    private Object literal(String value){String x=value.trim();if(x.startsWith("'")&&x.endsWith("'"))return x.substring(1,x.length()-1).replace("''", "'");try{return Long.parseLong(x);}catch(Exception ignored){}try{return Double.parseDouble(x);}catch(Exception ignored){}return x;}
    private LogicalFieldRef resolve(long root,String raw){String x=raw.trim().replace("`","");if(x.matches("f\\d+"))return ref(root,Long.parseLong(x.substring(1)));if(x.matches("\\d+"))return ref(root,Long.parseLong(x));String[] parts=x.split("\\.");List<SysModuleField> candidates=new ArrayList<>();for(SysModule module:registry.allModules()){if(!registry.ancestorChain(module.id()).contains(root))continue;for(List<SysModuleField> fields:registry.fieldsGroupedByTable(module.id()).values())for(SysModuleField field:fields)if(match(field,parts))candidates.add(field);}Map<Long,SysModuleField> unique=new LinkedHashMap<>();candidates.forEach(field->unique.put(field.id(),field));if(unique.size()==1){SysModuleField field=unique.values().iterator().next();return new LogicalFieldRef(field.moduleId(),field.id());}if(unique.isEmpty())throw new IllegalArgumentException("Unknown field: "+raw);throw new IllegalArgumentException("Ambiguous field: "+raw+"; use fieldId or module.table.column");}
    private boolean match(SysModuleField field,String[] parts){if(parts.length==1)return field.columnName().equals(parts[0]);if(parts.length==2)return field.tableName().equals(parts[0])&&field.columnName().equals(parts[1]);if(parts.length==3)return field.tableName().equals(parts[1])&&field.columnName().equals(parts[2]);return false;}
    private LogicalFieldRef ref(long root,long id){SysModuleField field=registry.field(id);if(!registry.ancestorChain(field.moduleId()).contains(root))throw new IllegalArgumentException("fieldId="+id+" is outside root "+root);return new LogicalFieldRef(field.moduleId(),id);}
    private List<LogicalFieldRef> allVisible(long root){List<LogicalFieldRef> result=new ArrayList<>();for(SysModule module:registry.allModules())if(registry.ancestorChain(module.id()).contains(root))for(List<SysModuleField> fields:registry.fieldsGroupedByTable(module.id()).values())for(SysModuleField field:fields)result.add(new LogicalFieldRef(field.moduleId(),field.id()));return result;}
    private String stripAlias(String value){String x=value.trim();int i=x.toLowerCase(Locale.ROOT).lastIndexOf(" as ");return i>0?x.substring(0,i).trim():x;}
}
