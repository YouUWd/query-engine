package com.example.schoolquery.query;

import java.util.List;
import java.util.Map;

/** 对应给定的响应 JSON：{@code {pageNo, pageSize, total, records, header}}。 */
public record PagedResult(
        int pageNo,
        int pageSize,
        long total,
        List<Map<String, Object>> records,
        HeaderNode header  // withHeader=false 时为 null
) {}
