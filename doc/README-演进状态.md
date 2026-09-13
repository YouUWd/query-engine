# 《基于元数据的模块化SQL引擎演进方案》实施状态

分支：`refactor/query-plan`

## 已落地

- JSqlParser 4.9 作为 SQL 语法边界。
- `ColumnMeta` / `ModuleQueryResult` / `ModuleUpdateResult` 平台契约。
- `LogicalFieldRef`、`QueryPlan`、`FilterPlan`、`FilterExpressionPlan`、`SortPlan`、`PaginationPlan`。
- `QueryPlan` 保留完整 Boolean Filter Tree，AND/OR 不在语义编译阶段丢失。
- `ModuleQueryCompiler`：模块、字段、AND/OR、比较谓词、排序、分页语义解析。
- `ModuleSqlParser` 支持 `FROM 101`、`FROM \`101\`` 以及 `FROM module(101)` / `module('CODE')` 形式。
- `PaginationPlan` 保留精确 SQL OFFSET，同时兼容原有 pageNo/pageSize 调用方式。
- `PlanConditionCompiler`：完整 Boolean Filter Tree 编译为 jOOQ Condition；后代 1:N 字段形成相关 EXISTS 链。
- `ResolvedRelationPlan` 成为跨模块关系的语义解析结果；`NestedGroup` 不再持有原始物理关系对象。
- `FlatGroupSqlBuilder` 的嵌套关系渲染直接消费 `ResolvedRelationPlan`，不再对父子模块关系做物理表二次推断。
- 新增 `QueryPlanExecutor`：直接执行 `QueryPlan`，不再依赖 `PagedFieldDrivenQueryService` 的 LIMIT/OR 兼容限制。
- `DefaultModuleSqlEngine`：公开 DQL 入口已经切换到 `ModuleSqlParser -> ModuleQueryCompiler -> QueryPlanExecutor`；标量 DML 使用独立 Mutation Pipeline。
- `MutationCompiler`：标准 INSERT / UPDATE / DELETE -> `MutationPlan`。
- `MutationExecutor`：基于 jOOQ 执行标量 DML。
- `AggregateMutation`：独立于标准 SQL 的聚合保存模型，显式表达 PATCH / FULL_SYNC / orphanRemoval。
- `AggregateMutationExecutor`：单数据源事务、父先子后 INSERT、子先父后 DELETE、FULL_SYNC orphan removal。
- 虚拟模块从物理 QueryTree 中透明化。
- `PagedResult` / `HeaderNode` 标记 deprecated，作为兼容层保留。
- Maven CI 验证工作流。
- H2/JUnit 编译层测试覆盖 Boolean Filter Tree、module()、精确 OFFSET、标量 INSERT，以及直接 Module SQL 执行。

## 仍在演进

1. 根组内部跨物理表 JOIN 仍保留 `RelationResolver` 兼容路径；下一步应把模块内部表关联也预解析为不可变的逻辑/物理 JoinPlan。
2. 当前 `QueryPlanExecutor` 已统一根过滤语义，但嵌套结果集的子行过滤仍需要进一步从 `FilterExpressionPlan` 推导局部子树条件，以完整覆盖复杂 OR 场景。
3. 当前 DQL 已经脱离旧分页服务，但仍使用 `FlatGroupSqlBuilder` 的 MULTISET 路径；下一步切换为文档中的 Root Page -> Batch Child Load -> ResultAssembler 方案，以解决大分页和高基数 1:N 的性能问题。
4. `ColumnMeta` 已形成平台契约，但 SQL `AS` 别名与 `SELECT *` 的最终结果列名还需要从语法 AST 一并进入 ProjectionPlan，不能继续丢失别名信息。
5. 权限计划尚未成为独立语义阶段；需要把字段/行权限作为 QueryPlan 的不可绕过约束统一注入 Data/Count/DML。
6. 聚合保存已经具备事务、级联顺序和 orphan removal 骨架，但乐观锁、软删除、Association / Composition 元数据还需要接入现有配置体系。
7. H2 端到端覆盖仍需扩展到真实多模块 1:N EXISTS、同表多模块、虚拟模块、JOIN、分页稳定性、权限和完整级联保存。

## 目标架构

```text
Module SQL
   |
   v
ModuleSqlParser
   |
   v
ModuleQueryCompiler / MutationCompiler
   |
   +--> QueryPlan / MutationPlan / AggregateMutation
   |
   +--> PermissionPlan
   |
   v
QueryPlanExecutor
   |
   +--> Root Data Plan / Count Plan
   +--> Logical Relation / Join Plan
   |
   v
jOOQ SQL Builder
   |
   v
BatchLoader / MutationExecutor
   |
   v
ResultAssembler / Renderer
```

### 当前里程碑

**Module SQL 已经成为公开执行入口**。调用方可以直接提交模块级 SQL，内部自动完成语法解析、FieldId/ModuleId 语义解析、模块树构建、EXISTS 条件编译、jOOQ 执行和标准结果封装。后续优化将围绕复杂嵌套过滤、ProjectionPlan、批量加载、权限统一和聚合 DML 完整生命周期继续推进。
