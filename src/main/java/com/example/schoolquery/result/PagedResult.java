
package com.example.schoolquery.result;
import com.example.schoolquery.query.model.*;
import com.example.schoolquery.query.resolver.*;
import com.example.schoolquery.query.renderer.*;
import com.example.schoolquery.result.*;
import com.example.schoolquery.sql.parser.*;
import com.example.schoolquery.query.compiler.*;
import com.example.schoolquery.query.executor.*;
import com.example.schoolquery.mutation.model.*;
import com.example.schoolquery.mutation.executor.*;
import com.example.schoolquery.mutation.compiler.*;


import java.util.List;
import java.util.Map;

/**
 * @deprecated compatibility DTO for the legacy field-driven API. New platform
 * code should use {@code ModuleQueryResult} and {@code ColumnMeta}.
 */
@Deprecated(forRemoval = false)
public record PagedResult(int pageNo,int pageSize,long total,List<Map<String,Object>> records,HeaderNode header) {}
