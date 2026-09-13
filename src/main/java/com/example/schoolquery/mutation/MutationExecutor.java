package com.example.schoolquery.mutation;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModuleField;
import org.jooq.*;
import org.jooq.impl.DSL;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.jooq.impl.DSL.*;

/** Executes scalar mutation plans. Aggregate/cascade mutations are deliberately separate. */
public final class MutationExecutor {
    private final MetadataRegistry registry;
    public MutationExecutor(MetadataRegistry registry){this.registry=registry;}
    public int execute(DSLContext dsl,MutationPlan plan){return switch(plan.operation()){case INSERT->insert(dsl,plan);case UPDATE->update(dsl,plan);case DELETE->delete(dsl,plan);};}
    private int insert(DSLContext dsl,MutationPlan plan){Table<?> table=table(name(registry.module(plan.rootModuleId()).primaryTable()));Map<Field<Object>,Object> values=new LinkedHashMap<>();for(MutationPlan.Assignment a:plan.assignments())values.put(field(registry.field(a.field().fieldId())),a.value());return dsl.insertInto(table).set(values).execute();}
    private int update(DSLContext dsl,MutationPlan plan){Table<?> table=table(name(registry.module(plan.rootModuleId()).primaryTable()));Map<Field<Object>,Object> values=new LinkedHashMap<>();for(MutationPlan.Assignment a:plan.assignments())values.put(field(registry.field(a.field().fieldId())),a.value());return dsl.update(table).set(values).where(condition(plan.where())).execute();}
    private int delete(DSLContext dsl,MutationPlan plan){return dsl.deleteFrom(table(name(registry.module(plan.rootModuleId()).primaryTable()))).where(condition(plan.where())).execute();}
    private Condition condition(MutationPlan.Where where){if(where==null||where.predicates().isEmpty())return trueCondition();Condition result=trueCondition();for(MutationPlan.Predicate p:where.predicates()){if(!"EQ".equalsIgnoreCase(p.operator()))throw new IllegalArgumentException("Unsupported mutation operator: "+p.operator());result=result.and(field(registry.field(p.field().fieldId())).eq(p.value()));}return result;}
    private Field<Object> field(SysModuleField meta){return DSL.field(name(meta.tableName(),meta.columnName()),Object.class);}
}
