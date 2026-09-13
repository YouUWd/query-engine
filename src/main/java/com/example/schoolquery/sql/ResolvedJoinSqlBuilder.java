package com.example.schoolquery.sql;

import com.example.schoolquery.plan.ResolvedRelationPlan;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Renders joins from a ResolvedRelationPlan. This class deliberately has no metadata
 * resolver: relation discovery belongs to the semantic compilation phase.
 */
public final class ResolvedJoinSqlBuilder {
    private ResolvedJoinSqlBuilder() {}

    public static Table<?> table(String tableName) {
        return table(name(tableName));
    }

    public static Condition joinCondition(ResolvedRelationPlan relation,
                                          Table<?> parent,
                                          Table<?> child) {
        Field<Object> parentField = field(name(parent.getName(), relation.parentColumn()), Object.class);
        Field<Object> childField = field(name(child.getName(), relation.childColumn()), Object.class);
        return parentField.eq(childField);
    }
}
