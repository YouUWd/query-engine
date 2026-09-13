package com.example.schoolquery.plan;
public record LogicalFieldRef(Long moduleId, Long fieldId){public LogicalFieldRef{if(moduleId==null||fieldId==null)throw new IllegalArgumentException("moduleId and fieldId are required");}}
