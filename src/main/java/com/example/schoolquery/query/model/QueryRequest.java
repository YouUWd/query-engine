package com.example.schoolquery.query.model;

import java.util.List;

/**
 * 对应给定的请求 JSON：
 * {@code {moduleId, pageNo, pageSize, fields, filters, sorts, withHeader}}
 *
 * fields/filters/sorts 里的字段全部用 sys_module_field.id 定位，调用方不需要
 * 知道字段属于哪张表、哪一列——那是元数据的事。
 */
public record QueryRequest(
        long moduleId,
        int pageNo,
        int pageSize,
        List<Long> fields,
        List<FilterCriterion> filters,
        List<SortCriterion> sorts,
        boolean withHeader
) {
    public QueryRequest {
        if (pageNo < 1) throw new IllegalArgumentException("pageNo 必须从1开始");
        if (pageSize < 1) throw new IllegalArgumentException("pageSize 必须大于0");
        filters = filters == null ? List.of() : filters;
        sorts = sorts == null ? List.of() : sorts;
    }
}
