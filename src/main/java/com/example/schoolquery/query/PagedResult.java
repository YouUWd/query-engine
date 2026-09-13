package com.example.schoolquery.query;

import java.util.List;
import java.util.Map;

/**
 * @deprecated compatibility DTO for the legacy field-driven API. New platform
 * code should use {@code ModuleQueryResult} and {@code ColumnMeta}.
 */
@Deprecated(forRemoval = false)
public record PagedResult(int pageNo,int pageSize,long total,List<Map<String,Object>> records,HeaderNode header) {}
