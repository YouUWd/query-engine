package com.example.schoolquery.querytree;

import com.example.schoolquery.model.RelationType;
import com.example.schoolquery.model.SysTableRelation;
import com.example.schoolquery.plan.RelationPlan;
import com.example.schoolquery.plan.ResolvedRelationPlan;

/** Nested child keeps both the semantic relation and a legacy metadata view. */
public record NestedGroup(long childModuleId, SysTableRelation relation, ResolvedRelationPlan resolvedRelation, FlatGroup group) {
    public NestedGroup(long childModuleId, ResolvedRelationPlan resolvedRelation, FlatGroup group){this(childModuleId,legacyRelation(resolvedRelation),resolvedRelation,group);}
    public NestedGroup(long childModuleId, SysTableRelation relation, FlatGroup group){this(childModuleId,relation,resolveLegacy(relation,childModuleId),group);}
    private static SysTableRelation legacyRelation(ResolvedRelationPlan r){RelationType type=r.type()==RelationPlan.RelationType.ONE_TO_ONE?RelationType.ONE_TO_ONE:RelationType.ONE_TO_MANY;return new SysTableRelation(0,r.parentTable(),r.parentColumn(),r.childTable(),r.childColumn(),type);}
    private static ResolvedRelationPlan resolveLegacy(SysTableRelation r,long childModuleId){return new ResolvedRelationPlan(0,childModuleId,r.type()==RelationType.ONE_TO_ONE?RelationPlan.RelationType.ONE_TO_ONE:RelationPlan.RelationType.ONE_TO_MANY,r.mainTable(),r.mainField(),r.joinTable(),r.joinField());}
}
