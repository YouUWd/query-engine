# Header 接口与数据接口彻底拆分计划

## 1. 目标
彻底分离表头接口与数据接口：
1. **数据响应 [PagedResult.java](file:///d:/study/school-query-engine/src/main/java/com/example/schoolquery/query/PagedResult.java)**：**不保留 `header` 字段**，变为纯粹的分页数据结构：
   ```java
   public record PagedResult(
           int pageNo,
           int pageSize,
           long total,
           List<Map<String, Object>> records
   ) {}
   ```
2. **数据请求 [QueryRequest.java](file:///d:/study/school-query-engine/src/main/java/com/example/schoolquery/query/QueryRequest.java)**：移除 `withHeader` 字段，提供干净的查询参数：
   ```java
   public record QueryRequest(
           long moduleId,
           int pageNo,
           int pageSize,
           List<Long> fields,
           List<FilterCriterion> filters,
           List<SortCriterion> sorts
   ) {}
   ```
   （保留带 `withHeader` 的兼容构造器标记废弃，或重载调用）。
3. **独立表头请求/响应**：
   - 新建 `HeaderRequest(long moduleId, List<Long> fields)`。
   - 服务提供独立的表头生成接口：
     ```java
     HeaderNode buildHeader(HeaderRequest request);
     HeaderNode buildHeader(long moduleId, List<Long> fields);
     ```
     完全无需传 `DSLContext`（不查业务数据库），由内存元数据秒级返回。

---

## 2. 改动清单

### 2.1 实体模型
- **[PagedResult.java](file:///d:/study/school-query-engine/src/main/java/com/example/schoolquery/query/PagedResult.java)**：
  - 移除 `HeaderNode header` 字段。
- **[QueryRequest.java](file:///d:/study/school-query-engine/src/main/java/com/example/schoolquery/query/QueryRequest.java)**：
  - 核心 record 字段精简为：`(moduleId, pageNo, pageSize, fields, filters, sorts)`。
  - 保留 7 参数构造器 `QueryRequest(..., boolean withHeader)` 作为 `@Deprecated` 兼容过渡（内部忽略 `withHeader`）。
- **[HeaderRequest.java](file:///d:/study/school-query-engine/src/main/java/com/example/schoolquery/query/HeaderRequest.java)**：
  - 新建标准请求类：`public record HeaderRequest(long moduleId, List<Long> fields)`。

### 2.2 服务层 [PagedFieldDrivenQueryService.java](file:///d:/study/school-query-engine/src/main/java/com/example/schoolquery/service/PagedFieldDrivenQueryService.java)
- 新增独立表头方法：
  - `public HeaderNode getHeader(HeaderRequest request)`
  - `public HeaderNode getHeader(long moduleId, List<Long> fields)`
- 数据查询方法 `execute`：
  - 移除对 `headerBuilder` 的调用；
  - 返回 `new PagedResult(request.pageNo(), request.pageSize(), total, wrappedRecords)`。

### 2.3 测试用例同步
- [PagedFieldDrivenQueryServiceH2IT.java](file:///d:/study/school-query-engine/src/test/java/com/example/schoolquery/service/PagedFieldDrivenQueryServiceH2IT.java)：
  - 分离数据查询与表头测试，分别调用 `service.execute(dsl, request)` 和 `service.getHeader(moduleId, fields)`。
- [VirtualModuleQueryH2IT.java](file:///d:/study/school-query-engine/src/test/java/com/example/schoolquery/service/VirtualModuleQueryH2IT.java)：
  - 数据部分验证 `service.execute(...)` 返回无 `header` 的纯分页数据；
  - 表头部分独立调用 `service.getHeader(...)` 验证虚拟模块在表头分组中的正确性。

---

## 3. 验证计划
- 执行 `mvn clean test`，保证全量测试绿灯通过。
