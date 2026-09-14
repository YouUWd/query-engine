package com.example.schoolquery.mutation.model;

import com.example.schoolquery.query.model.LogicalFieldRef;
import java.util.List;

/** Physical-independent mutation plan. Nested aggregate payloads are represented as child mutations. */
public record MutationPlan(Operation operation,long rootModuleId,List<Assignment> assignments,List<MutationPlan> children,Where where){
    public MutationPlan{assignments=assignments==null?List.of():List.copyOf(assignments);children=children==null?List.of():List.copyOf(children);}
    public enum Operation{INSERT,UPDATE,DELETE}
    public record Assignment(LogicalFieldRef field,Object value){}

    /** Mutation predicates retain SQL boolean structure instead of flattening OR into AND. */
    public record Where(Expression expression){
        public Where { expression = expression == null ? null : expression; }
        /** Backward-compatible AND list constructor for existing callers. */
        public Where(List<Predicate> predicates) {
            this(and(predicates));
        }
        private static Expression and(List<Predicate> predicates) {
            if (predicates == null || predicates.isEmpty()) return null;
            Expression result = new PredicateExpression(predicates.get(0));
            for (int i = 1; i < predicates.size(); i++) result = new And(result, new PredicateExpression(predicates.get(i)));
            return result;
        }
    }

    public sealed interface Expression permits PredicateExpression,And,Or {}
    public record PredicateExpression(Predicate predicate) implements Expression {}
    public record And(Expression left,Expression right) implements Expression {}
    public record Or(Expression left,Expression right) implements Expression {}
    public record Predicate(LogicalFieldRef field,String operator,Object value){}
}
