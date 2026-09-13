# 《基于元数据的模块化SQL引擎演进方案》实施状态

分支：`refactor/query-plan`

## 已落地

- JSqlParser 4.9 作为 SQL 语法边界。
- `ColumnMeta` / `ModuleQueryResult` / `ModuleUpdateResult` 平台契约。
- `LogicalFieldRef`、`QueryPlan`、`FilterPlan`、`FilterExpressionPlan`、`SortPlan`、`PaginationPlan`。
- `ModuleQueryCompiler`：模块、字段、AND/OR/比较谓词、排序、分页语义解析。
- `DefaultModuleSqlEngine`：把 Module SQL 接到现有分页/嵌套执行器。
- 虚拟模块从物理 QueryTree 中透明化。
- `PagedResult` / `HeaderNode` 标记 deprecated，作为兼容层保留。
- Maven CI 验证工作流。
- `MutationPlan` 作为 DML/聚合保存的独立语义模型起点。

## 尚需完成

1. `FlatGroupSqlBuilder` 改为只消费已解析的 `RelationPlan`，彻底取消 table-name 二次关系解析。
2. 1:N 过滤从 `FilterPlan` 统一编译为根级 EXISTS，并让 CountPlan 与 DataPlan 共用同一过滤语义。
3. `DefaultModuleSqlEngine` 消除当前兼容适配器对 LIMIT 和 AND 的限制，直接执行完整 Boolean Filter Plan。
4. 独立 `BatchLoader` / `ResultAssembler` / `ResultRenderer`，移除分页服务中的业务编排。
5. 实现标准标量 INSERT / UPDATE / DELETE 的 MutationCompiler + MutationExecutor。
6. 实现扩展 JSON Payload 的 `AggregateMutation`、Diff、Orphan Removal、Top-Down Insert、Bottom-Up Delete。
7. 明确 Association / Composition / cascade / optimistic locking / soft-delete 元数据。
8. 完成 H2 端到端查询与级联保存测试后，再考虑移除旧兼容 API。

当前分支是“演进方案已经建立完整骨架并落地第一阶段”，不是宣称所有 DML/级联能力已经完成。
