package com.example.schoolquery.mutation;

import com.example.schoolquery.plan.LogicalFieldRef;
import java.util.List;
import java.util.Map;

/** Physical-independent mutation plan. Nested aggregate payloads are represented as child mutations. */
public record MutationPlan(Operation operation,long rootModuleId,List<Assignment> assignments,List<MutationPlan> children,Where where){
    public MutationPlan{assignments=assignments==null?List.of():List.copyOf(assignments);children=children==null?List.of():List.copyOf(children);}
    public enum Operation{INSERT,UPDATE,DELETE}
    public record Assignment(LogicalFieldRef field,Object value){}
    public record Where(List<Predicate> predicates){public Where{predicates=predicates==null?List.of():List.copyOf(predicates);}}
    public record Predicate(LogicalFieldRef field,String operator,Object value){}
}
