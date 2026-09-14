package com.example.schoolquery.sql.parser;

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
    private static final Pattern MODULE_FROM=Pattern.compile("(?i)(\\bFROM\\s+)module\\s*\\(\\s*(?:'([^']+)'|`([^`]+)`|(\\d+))\\s*\\)");
    private static final Pattern BARE_MODULE_FROM=Pattern.compile("(?i)(\\bFROM\\s+)(\\d+)(?=\\s|$)");
    private static final Pattern OFFSET_LITERAL=Pattern.compile("(?i)\\bOFFSET\\s+(\\d+)\\b");
    public ModuleSqlAst parseSelect(String sql){
        if(sql==null||sql.isBlank())throw new IllegalArgumentException("sql must not be blank");
        try{
            ParsedSource source=normalizeModuleSource(sql); Statement statement=CCJSqlParserUtil.parse(source.sql());
            if(!(statement instanceof Select select)||!(select.getSelectBody() instanceof PlainSelect ps))throw new IllegalArgumentException("Only a single SELECT statement is supported");
            if(!(ps.getFromItem() instanceof net.sf.jsqlparser.schema.Table table))throw new IllegalArgumentException("FROM must reference a module identifier");
            List<String> projections=new ArrayList<>(); for(SelectItem item:ps.getSelectItems())projections.add(item.toString());
            List<ModuleSqlAst.SortSpec> sorts=new ArrayList<>(); if(ps.getOrderByElements()!=null)for(OrderByElement e:ps.getOrderByElements())sorts.add(new ModuleSqlAst.SortSpec(e.getExpression().toString(),e.isAsc()));
            Integer limit=null,offset=null; Limit l=ps.getLimit();
            if(l!=null){limit=longValue(l.getRowCount(),"LIMIT");if(l.getOffset()!=null)offset=longValue(l.getOffset(),"OFFSET");}
            if(offset==null){Matcher m=OFFSET_LITERAL.matcher(sql);if(m.find())offset=parseNonNegativeInt(m.group(1),"OFFSET");}
            String rootToken=source.explicitRootToken()==null?table.getName():source.explicitRootToken();
            return new ModuleSqlAst(rootToken,projections,ps.getWhere(),sorts,limit,offset);
        }catch(Exception e){if(e instanceof IllegalArgumentException iae)throw iae;throw new IllegalArgumentException("Invalid module SQL: "+e.getMessage(),e);}
    }
    private ParsedSource normalizeModuleSource(String sql){
        Matcher module=MODULE_FROM.matcher(sql);
        if(module.find()){String token=module.group(2)!=null?module.group(2):module.group(3)!=null?module.group(3):module.group(4);String replacement=module.group(1)+"`"+token.replace("`","")+"`";return new ParsedSource(sql.substring(0,module.start())+replacement+sql.substring(module.end()),token);}
        Matcher bare=BARE_MODULE_FROM.matcher(sql);
        if(bare.find()){String token=bare.group(2);String replacement=bare.group(1)+"`"+token+"`";return new ParsedSource(sql.substring(0,bare.start())+replacement+sql.substring(bare.end()),token);}
        return new ParsedSource(sql,null);
    }
    private int longValue(Expression expression,String keyword){if(!(expression instanceof LongValue v))throw new IllegalArgumentException(keyword+" must be a numeric literal");return parseNonNegativeInt(Long.toString(v.getValue()),keyword);}
    private int parseNonNegativeInt(String value,String keyword){try{long n=Long.parseLong(value);if(n<0||n>Integer.MAX_VALUE)throw new IllegalArgumentException(keyword+" is out of range");return(int)n;}catch(NumberFormatException e){throw new IllegalArgumentException(keyword+" must be a numeric literal",e);}}
    private record ParsedSource(String sql,String explicitRootToken){}
}
