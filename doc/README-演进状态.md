# 《基于元数据的模块化SQL引擎演进方案》实施状态

分支：`refactor/query-plan`

## 已落地

- JSqlParser 4.9 作为 Module SQL 语法边界。
- `ColumnMeta` / `ModuleQueryResult` / `ModuleUpdateResult` 平台契约。
- `LogicalFieldRef`、`QueryPlan`、`FilterPlan`、`FilterExpressionPlan`、`SortPlan`、`PaginationPlan`。
- `QueryPlan` 保留完整 Boolean Filter Tree，AND/OR 不在语义编译阶段丢失。
- `ModuleQueryCompiler`：模块、字段、AND/OR、比较谓词、排序、分页语义解析。
- `ModuleSqlParser` 支持 `FROM 101`、反引号模块标识以及 `FROM module(101)` / `module('CODE')` 形式。
- `PaginationPlan` 保留精确 SQL OFFSET，同时兼容原有 pageNo/pageSize 调用方式。
- `PlanConditionCompiler`：完整 Boolean Filter Tree 编译为 jOOQ `Condition`；后代 1:N 字段形成相关 `EXISTS` 链。
- `ResolvedRelationPlan` 成为跨模块关系的语义解析结果；`NestedGroup` 不再要求 SQL renderer 根据物理表重新推断父子模块关系。
- `ResolvedTableJoinPlan` 成为模块内部跨物理表 JOIN 的预解析结果。
- `QueryTreeBuilder.resolveTableJoins(...)` 在逻辑字段解析完成后，仅针对本次请求实际需要的字段预解析物理表 JOIN。
- `FlatGroupSqlBuilder` 已切换为只消费 `FlatGroup.tableJoins()`；SQL renderer 不再通过 `tableA/tableB` 调用 `RelationResolver.relationOf(...)` 推断模块内部 JOIN。
- 新增 `QueryPlanExecutor`：直接执行 `QueryPlan`，不再依赖 `PagedFieldDrivenQueryService` 的 LIMIT/OR 兼容限制。
- `DefaultModuleSqlEngine`：公开 DQL 入口已经切换到 `ModuleSqlParser -> ModuleQueryCompiler -> QueryPlanExecutor`；标量 DML 使用独立 Mutation Pipeline。
- `MutationCompiler`：标准 INSERT / UPDATE / DELETE -> `MutationPlan`。
- `MutationExecutor`：基于 jOOQ 执行标量 DML。
- `AggregateMutation`：独立于标准 SQL 的聚合保存模型，显式表达 PATCH / FULL_SYNC / orphanRemoval。
- `AggregateMutationExecutor`：单数据源事务、父先子后 INSERT、子先父后 DELETE、FULL_SYNC orphan removal 骨架。
- 虚拟模块继续作为 QueryTree 的结构节点保留，不把其错误地当成物理表。
- `PagedResult` / `HeaderNode` 标记 deprecated，作为兼容层保留。
- Maven CI 验证工作流。
- H2/JUnit 已覆盖 Boolean Filter Tree、module()、精确 OFFSET、标量 INSERT，以及直接 Module SQL 执行等核心路径。

## 当前架构原则

### 1. ModuleId / FieldId 是唯一语义身份

Module SQL 首先解析为逻辑模块和逻辑字段，再映射到物理表、物理列。不能因为两个 Module 指向同一张物理表，就把两个 Module 当成同一个业务语义对象。

### 2. 关系必须先解析、后渲染

关系解析分成两个层次：

- **模块关系**：`ResolvedRelationPlan`，表达父 Module -> 子 Module 的 1:1 / 1:N 语义。
- **模块内部物理表 JOIN**：`ResolvedTableJoinPlan`，表达当前 FlatGroup 内 primary table 与实际投影表之间已经确定的列关联。

SQL renderer 只负责把上述结果翻译成 jOOQ SQL，不再根据物理表名称猜测业务关系。

### 3. 1:N 后代过滤使用 EXISTS

例如：

```sql
SELECT f101, f102
FROM module(101)
WHERE f201 = 'Java'
```

当 `f201` 位于 1:N 子模块时，语义是“根实体存在满足条件的子实体”，而不是把根查询直接 JOIN 成重复行。因此过滤条件编译为相关 `EXISTS` 链。

### 4. 权限与分页不建立特殊执行体系

权限本质上是附加 `WHERE` 条件；分页本质上是 `LIMIT/OFFSET`。它们不再被设计成独立的特殊执行器或特殊根分页抽象。后续权限接入应保持 `Condition` 注入模型，确保 Data / Count / DML 的过滤语义一致。

### 5. QueryTree / FlatGroup / jOOQ 是内部实现细节

公开 API 不暴露 QueryTree、FlatGroup、ResolvedTableJoinPlan 等实现模型。调用方只面对 Module SQL 和标准结果：

```java
ModuleQueryResult executeQuery(DSLContext dsl, String sql);
List<ColumnMeta> getMetadata(String sql);
ModuleUpdateResult executeUpdate(DSLContext dsl, String sql);
```

## 仍在演进

1. **测试与 CI 稳定化**：最近一次“预解析物理表 JOIN”提交的 Maven Test 为失败状态，需要继续定位失败用例并补齐对应 H2 E2E，而不能把失败状态标记为完成。
2. **1:N 局部过滤**：`compileLocal(...)` 已能把过滤表达式投影到子树；复杂跨层 OR 仍需要继续明确其安全语义和结果集语义。
3. **SELECT ***：当前方向是将 `SELECT *` 定义为“当前根模块子树内所有可见 FieldId 的投影展开”，而不是物理 `table.*`。
4. **ProjectionPlan**：目前投影别名已经可以进入 QueryPlan、结果和 `ColumnMeta`；下一步应把“投影 occurrence”提升为独立模型，以支持同一 FieldId 多次投影并使用不同别名。
5. **DQL 性能路径**：当前正确性实现仍使用 jOOQ MULTISET。后续在语义稳定后，再评估 Root Page -> Batch Child Load -> ResultAssembler，以解决大分页和高基数 1:N 场景。
6. **标量 DML 完整性**：继续补齐生成主键、类型转换、更新条件、批量操作等边界。
7. **Aggregate Mutation**：继续接入乐观锁、软删除以及 Association / Composition 等元数据语义。
8. **E2E 覆盖**：扩展到根查询、1:1、1:N、同表多 Module、虚拟 Module、模块内多物理表 JOIN、后代 EXISTS、AND/OR、SELECT *、别名、LIMIT/OFFSET、权限 Condition、标量 DML 和级联保存。

## 目标架构

```text
Module SQL
   |
   v
ModuleSqlParser
   |
   v
ModuleSqlAst
   |
   v
Semantic Compiler
   |
   +--> QueryPlan / MutationPlan / AggregateMutation
   |
   +--> LogicalFieldRef
   +--> ResolvedRelationPlan
   +--> ResolvedTableJoinPlan
   |
   v
QueryPlanExecutor / MutationExecutor
   |
   v
jOOQ SQL Builder
   |
   v
DB
   |
   v
ModuleQueryResult / ModuleUpdateResult
```

## 当前里程碑

**Module SQL 已经成为公开执行入口，语义层已经从“按物理表拼 SQL”进一步演进为“先完成 Module/Field/Relation 解析，再渲染 SQL”。**

当前最重要的架构边界已经明确：

```text
逻辑身份解析
    ↓
模块关系解析
    ↓
请求字段解析
    ↓
实际需要的物理表 JOIN 预解析
    ↓
WHERE / EXISTS / LIMIT / OFFSET Condition 化
    ↓
jOOQ 渲染
```

下一阶段不再继续堆叠 renderer 层的特殊判断，而是优先把失败的 CI 用例修正，并以 H2 E2E 把上述语义边界锁死。
