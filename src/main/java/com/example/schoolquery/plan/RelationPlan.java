package com.example.schoolquery.plan;
public record RelationPlan(Long parentModuleId,Long childModuleId,RelationType type,String parentTable,String parentColumn,String childTable,String childColumn){public enum RelationType{ONE_TO_ONE,ONE_TO_MANY,MANY_TO_ONE}}
