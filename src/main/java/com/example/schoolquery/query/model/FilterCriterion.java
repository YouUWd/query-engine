package com.example.schoolquery.query.model;

/** 请求里 filters 数组的一项：按 fieldId 定位字段，不需要调用方自己知道表名/列名。 */
public record FilterCriterion(long fieldId, FilterOperator operator, Object value) {}
