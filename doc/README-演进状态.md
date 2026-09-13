# 《基于元数据的模块化SQL引擎演进方案》实施状态

分支：`refactor/query-plan`

## 已落地

- JSqlParser 4.9 作为 SQL 语法边界。
- `ColumnMeta` / `ModuleQueryResult` / `ModuleUpdateResult` 平台契约。
- `LogicalFieldRef`、`QueryPlan`、`FilterPlan`、`FilterExpressionPlan`、`SortPlan`、`PaginationPlan`。
- `QueryPlan` 同时保留完整 Boolean Filter Tree 与 AND-only 兼容平面表示，OR 不再在语义编译阶段丢失。
- `ModuleQueryCompiler`：模块、字段、AND/OR、比较谓词、排序、分页语义解析。
- `DefaultModuleSqlEngine`：查询继续兼容现有分页/嵌套执行器；标量 DML 已接入独立 Mutation Pipeline。
- `MutationCompiler`：标准 INSERT / UPDATE / DELETE -> `MutationPlan`。
- `MutationExecutor`：基于 jOOQ 执行标量 DML。
- `AggregateMutation`：独立于标准 SQL 的聚合保存模型，显式表达 PATCH / FULL_SYNC / orphanRemoval。
- `AggregateMutationExecutor`：单数据源事务、父先子后 INSERT、子先父后 DELETE、FULL_SYNC orphan removal。
- 虚拟模块从物理 QueryTree 中透明化。
- `PagedResult` / `HeaderNode` 标记 deprecated，作为兼容层保留。
- Maven CI 验证工作流。
- H2/JUnit 编译层测试覆盖 Boolean Filter Tree 与标量 INSERT 语义。

## 仍在演进

1. `FlatGroupSqlBuilder` 还存在 legacy `RelationResolver` / table-name 二次解析；下一步要让 SQL Builder 只消费已解析的 `RelationPlan`。
2. 1:N 过滤目前在旧分页服务中仍是 table-based EXISTS；目标是统一由 `FilterPlan` / `FilterExpressionPlan` 编译成根级 EXISTS，并让 CountPlan 与 DataPlan 共用同一过滤计划。
3. `DefaultModuleSqlEngine` 查询仍通过兼容执行器，因此尚有 LIMIT、OR 等限制；需要建立真正的 `QueryPlanExecutor`。
4. `PagedFieldDrivenQueryService` 的 `loadAndAssemble` 仍承担 BatchLoader / Assembler / Renderer 三类职责，需要继续拆分。
5. 聚合保存已经具备事务、级联顺序和 orphan removal 的执行骨架，但乐观锁、软删除、Association / Composition 元数据还需要接入现有配置体系。
6. H2 端到端查询、1:N EXISTS、分页稳定性、同表多模块、虚拟模块和完整级联保存测试仍需补齐。

## 目标架构

```text
Module SQL / QueryRequest
        |
        v
   Syntax Parser
        |
        v
 Semantic Compiler
   |          |
   |          +--> PermissionPlan
   v
  QueryPlan / MutationPlan / AggregateMutation
        |
        +--> Root Data Plan + Count Plan
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

当前分支已经从“QueryTree 重构”进入“语义计划 + Mutation Pipeline”阶段；剩余工作集中在把旧物理 QueryTree 执行器彻底替换成 QueryPlanExecutor，以及把权限、EXISTS、分页和级联测试闭环。
