package com.example.schoolquery.plan;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysTableRelation;
import java.util.Objects;

/** Resolves module-to-module semantics once; SQL rendering must not rediscover them. */
public final class LogicalRelationResolver {
    private final MetadataRegistry registry;
    public LogicalRelationResolver(MetadataRegistry registry){this.registry=Objects.requireNonNull(registry,"registry");}
    public ResolvedRelationPlan resolve(long parentModuleId,long childModuleId){
        SysModule parent=registry.module(parentModuleId),child=registry.module(childModuleId);
        if(parent.isVirtual()||child.isVirtual())throw new IllegalArgumentException("virtual module cannot be a physical relation endpoint: "+parentModuleId+" -> "+childModuleId);
        if(parent.primaryTable().equals(child.primaryTable()))return new ResolvedRelationPlan(parent.id(),child.id(),RelationPlan.RelationType.ONE_TO_ONE,parent.primaryTable(),"",child.primaryTable(),"");
        SysTableRelation r=registry.findRelation(parent.primaryTable(),child.primaryTable()).orElseThrow(()->new IllegalArgumentException("No relation configured for modules "+parentModuleId+" -> "+childModuleId));
        RelationPlan.RelationType type=r.type()==com.example.schoolquery.model.RelationType.ONE_TO_ONE?RelationPlan.RelationType.ONE_TO_ONE:RelationPlan.RelationType.ONE_TO_MANY;
        return r.mainTable().equals(parent.primaryTable())?new ResolvedRelationPlan(parent.id(),child.id(),type,r.mainTable(),r.mainField(),r.joinTable(),r.joinField()):new ResolvedRelationPlan(parent.id(),child.id(),type,r.joinTable(),r.joinField(),r.mainTable(),r.mainField());
    }
}
