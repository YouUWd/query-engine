package com.example.schoolquery.sql;

import com.example.schoolquery.plan.ResolvedRelationPlan;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/** Renders joins from a resolved semantic relation; it performs no metadata lookup. */
public final class ResolvedJoinSqlBuilder {
    private ResolvedJoinSqlBuilder() {}
    public static Table<?> table(String tableName){return table(name(tableName));}
    public static Condition joinCondition(ResolvedRelationPlan relation,Table<?> parent,Table<?> child){
        Field<Object> parentField=parent.field(relation.parentColumn(),Object.class);
        Field<Object> childField=child.field(relation.childColumn(),Object.class);
        if(parentField==null||childField==null)throw new IllegalArgumentException("Relation column not found: "+relation);
        return parentField.eq(childField);
    }
}
