package com.example.schoolquery.plan;
public record FilterPlan(LogicalFieldRef field,Operator operator,Object value){public enum Operator{EQ,NE,GT,GE,LT,LE,LIKE,IN,BETWEEN,IS_NULL,IS_NOT_NULL}}
