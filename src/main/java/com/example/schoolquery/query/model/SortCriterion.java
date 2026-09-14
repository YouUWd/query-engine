package com.example.schoolquery.query.model;

/** 请求里 sorts 数组的一项。目前只支持排序字段落在查询根节点自己的组内（见 README 的已知限制）。 */
public record SortCriterion(long fieldId, SortDirection direction) {}
