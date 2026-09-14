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
- `ResolvedTableJoinPlan` 成为模块内部跨物理表 JOIN 的预解析结果，并保留来源 ModuleId / 目标 ModuleId 上下文。
- `QueryTreeBuilder.resolveTableJoins(...)` 以已构建的模块树和字段所属 Module 为关系解析上下文，只针对本次请求实际需要的字段预解析物理表 JOIN。
- 同一 FlatGroup 中如果不同逻辑 Module 对同一物理表产生不同 JOIN 键，直接判定模块树对应的物理 JOIN 不自洽并失败，不再静默选择一个关系。
- `FlatGroupSqlBuilder` 已切换为只消费 `FlatGroup.tableJoins()`；SQL renderer 不再通过 `tableA/tableB` 调用 `RelationResolver.relationOf(...)` 推断模块内部 JOIN。
- 新增 `QueryPlanExecutor`：直接执行 `QueryPlan`，不再依赖 `PagedFieldDrivenQueryService` 的 LIMIT/OR 兼容限制。
- `DefaultModuleSqlEngine`：公开 DQL 入口已经切换到 `ModuleSqlParser -> ModuleQueryCompiler -> QueryPlanExecutor`；标量 DML 使用独立 Mutation Pipeline，并提供结构化 Aggregate Mutation 入口。
- `MutationCompiler`：标准 INSERT / UPDATE / DELETE -> `MutationPlan`，WHERE 保留 AND/OR 布尔结构。
- `MutationExecutor`：基于 jOOQ 执行标量 DML，并按 Mutation WHERE 表达式递归生成 `Condition`。
- 标量 UPDATE 支持同一逻辑 Module 的直接 1:1 次表写入；次表更新使用根表关系列子查询，并在根表更新前执行，避免 JDBC 类型推断和根主键修改造成的关联键失效。
- `AggregateMutation`：独立于标准 SQL 的聚合保存模型，显式表达 PATCH / FULL_SYNC / orphanRemoval，并支持 `clientKey` 作为临时节点与生成主键之间的关联元数据。
- `AggregateMutationResult`：返回逻辑影响行数、生成主键列表以及 `generatedKeysByClientKey`，其中 `clientKey` 永不写入数据库。
- `AggregateMutationExecutor`：单数据源事务、父先子后 INSERT、子先父后 DELETE、FULL_SYNC orphan removal。
- 虚拟模块继续作为 QueryTree 的结构节点保留，不把其错误地当成物理表。
- `PagedResult` / `HeaderNode` 标记 deprecated，作为兼容层保留。
- Maven CI 验证工作流。
- H2/JUnit 已覆盖 Boolean Filter Tree、module()、精确 OFFSET、标量 INSERT，以及直接 Module SQL 执行等核心路径。

## 当前架构原则

### 1. ModuleId 是模块树入口，FieldId 是字段唯一身份

`ModuleId` 用于确定查询的 Root Module，并快速定位需要构建的 Module Tree。`FieldId` 是全局唯一的逻辑字段身份；一个 FieldId 固定属于一个 Module，业务含义和 `table.column` 物理映射均固定。因此两个不同 Module 即使映射到相同的物理 `table.column`，仍然是两个完全独立的逻辑字段，不能通过物理表列反向合并。

```text
rootModuleId
    ↓
Module Tree
    ↓
ModuleId / Module context
    ↓
FieldId
    ↓
固定的 table.column
```

### 2. 字段输出别名以 FieldId 为默认稳定标识

默认投影别名采用 `f{FieldId}`，例如 `f102`。由于 FieldId 是逻辑字段的唯一身份，即使 `f102` 和 `f104` 同时映射到相同的 `student.name`，输出仍保持为两个独立字段。也允许显式 `AS xxx`，显式别名必须在同一 SELECT 投影中唯一；同一 FieldId 可以出现多次，只要每次使用不同的显式别名。

`module_table_column` 同样可以作为上层生成器的唯一别名策略，但它不是逻辑字段身份；引擎内部仍以 FieldId 识别字段。

### 3. 模块树是关系解析的上下文，表关系不能脱离模块树独立决定

模块树不是 SQL renderer 的辅助信息，而是确定关系语义的核心上下文。完整关系解析遵循：

```text
Module SQL
    ↓
逻辑 Module / Field
    ↓
构建模块树（父子路径、SAME_ENTITY、1:N、虚拟节点）
    ↓
在具体 Module 节点上下文中解析其字段涉及的物理表
    ↓
得到 ResolvedRelationPlan / ResolvedTableJoinPlan
    ↓
SQL renderer 只渲染已解析关系
```

因此：

- 父 Module -> 子 Module 的关系必须通过模块树路径确定，而不是只看两张物理表。
- 模块内部字段引用其它物理表时，必须以“字段所属 Module + 该 Module 的 primary_table”为解析上下文。
- 同一物理表对不能作为 JOIN 的唯一身份；ModuleId 是 JOIN 语义的来源上下文。
- 一个 FlatGroup 最终只能形成自洽的物理行。如果不同逻辑 Module 对同一目标表要求不同 JOIN 键，不能猜测，必须报元数据不一致。
- SQL renderer 不拥有关系推断能力，只消费 `ResolvedTableJoinPlan` / `ResolvedRelationPlan`。

### 4. 权限在 Engine 之前完成字段裁剪，数据权限就是 WHERE Predicate

FieldId 字段权限属于 Engine 上游的配置/权限层。进入 Engine 前即可得到本次请求允许使用的 FieldId 集合；Engine 不负责重新计算角色字段权限。

数据权限同样不建立独立执行体系。对于“表名 + 字段名 + IN 值集合”这类数据权限，最终只是附加的 `WHERE` Predicate，例如：

```sql
WHERE school_id IN (1, 2, 3)
  AND status IN ('NORMAL', 'GRADUATED')
```

因此权限、业务过滤和后续 Count/DML 过滤最终都可以统一落到 Condition / FilterExpression 上，不需要 `PermissionPlan`、特殊分页器或独立权限执行器。

### 5. 1:N 后代过滤使用 EXISTS

例如：

```sql
SELECT f101, f102
FROM module(101)
WHERE f201 = 'Java'
```

当 `f201` 位于 1:N 子模块时，语义是“根实体存在满足条件的子实体”，而不是把根查询直接 JOIN 成重复行。因此过滤条件编译为相关 `EXISTS` 链。

### 6. 权限与分页不建立特殊执行体系

权限本质上是附加 `WHERE` 条件；分页本质上是 `LIMIT/OFFSET`。它们不再被设计成独立的特殊执行器或特殊根分页抽象。

### 7. QueryTree / FlatGroup / jOOQ 是内部实现细节

公开查询 API 不暴露 QueryTree、FlatGroup、ResolvedTableJoinPlan 等实现模型。调用方只面对 Module SQL 和标准结果：

```java
ModuleQueryResult executeQuery(DSLContext dsl, String sql);
List<ColumnMeta> getMetadata(String sql);
ModuleUpdateResult executeUpdate(DSLContext dsl, String sql);
AggregateMutationResult executeAggregate(DSLContext dsl, AggregateMutation mutation);
```

其中标准 SQL 覆盖普通单行 INSERT / UPDATE / DELETE；涉及 1:N 层级写入、级联删除、FULL_SYNC、orphanRemoval 等生命周期语义时，使用结构化 `AggregateMutation`，避免把业务级级联语义伪装成普通 SQL。

### 8. 标量 DML 的安全边界

标量 DML 的语义边界是“单个逻辑 Module 的单行/集合修改”，不是任意物理表 SQL：

- Module SQL 明确指定 ModuleId / ModuleCode，避免共享 primary table 时发生模块猜测。
- 同一逻辑 Module 的直接 1:1 次表允许随根表一起 INSERT / UPDATE，并保持同一数据库事务。
- 1:N 写入必须进入 Aggregate Mutation。
- 标量跨物理表 DELETE 当前拒绝，使用 Aggregate Mutation 表达完整生命周期。
- 标量次表 UPDATE 的 WHERE 必须只引用根 Module primary table 字段；需要按次表条件筛选并级联修改时使用 Aggregate Mutation。
- 标量 affectedRows 表示逻辑根行数，不累加多个物理表的影响行数。

### 9. Aggregate Mutation 的职责

Aggregate Mutation 是模块树保存的结构化业务协议，而不是 QueryPlan 的另一个 SQL 变体：

```text
AggregateMutation
    ↓
模块节点 values
    ↓
父子 Module Tree
    ↓
INSERT / UPDATE / DELETE
    ↓
1:N FK 传播 / FULL_SYNC / orphanRemoval
    ↓
单事务提交
```

`PATCH` 表示只处理请求中出现的子节点；`FULL_SYNC + orphanRemoval` 时，由 `fullSyncChildModules` 明确声明哪些子集合是权威集合，因此可以区分“未提交该集合”和“明确提交空集合”。`clientKey` 仅用于客户端临时节点与数据库生成主键的回填关联，不参与持久化。

## 仍在演进

1. **CI 稳定化**：继续以 Maven CI 为最终验证；当前标量 1:1 UPDATE 修复正在验证。
2. **1:N 局部过滤**：`compileLocal(...)` 已能把过滤表达式投影到子树；复杂跨层 OR 仍需要继续明确其安全语义和结果集语义。
3. **SELECT ***：当前方向是将 `SELECT *` 定义为“当前根模块子树内所有可见 FieldId 的投影展开”，而不是物理 `table.*`。
4. **ProjectionPlan**：目前投影别名已经可以进入 QueryPlan、结果和 `ColumnMeta`；下一步应把“投影 occurrence”提升为独立模型，以支持更复杂的重复投影表达。这里的 occurrence 只解决 SQL SELECT 列表重复引用，不改变 FieldId 的唯一语义。
5. **DQL 性能路径**：当前正确性实现仍使用 jOOQ MULTISET。后续在语义稳定后，再评估 Root Page -> Batch Child Load -> ResultAssembler，以解决大分页和高基数 1:N 场景。
6. **标量 DML 完整性**：AND/OR、比较、IN/NOT IN、BETWEEN、NULL、LIKE 已进入当前语义模型；后续继续补齐批量 VALUES、表达式赋值、类型转换等边界。
7. **Aggregate Mutation**：继续接入乐观锁、软删除以及 Association / Composition 等元数据语义；同时可以在稳定 DTO 边界后接入 HTTP/JSON 层。
8. **E2E 覆盖**：扩展到根查询、1:1、1:N、同表多 Module、虚拟 Module、模块树决定的模块内多物理表 JOIN、后代 EXISTS、AND/OR、SELECT *、别名、LIMIT/OFFSET、上游权限裁剪、数据权限 Condition、标量 DML 和级联保存。

## 目标架构

```text
上游权限层
   |
   +--> FieldId 可见集合
   +--> Data Permission Condition
   |
   v
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
   +--> Module Query Tree
   |       |
   |       +--> ResolvedRelationPlan
   |       +--> ResolvedTableJoinPlan
   |
   v
QueryPlanExecutor / MutationExecutor / AggregateMutationExecutor
   |
   v
jOOQ SQL Builder
   |
   v
DB
   |
   v
ModuleQueryResult / ModuleUpdateResult / AggregateMutationResult
```

## 当前里程碑

**Module SQL 已经成为公开执行入口，语义层已经从“按物理表拼 SQL”进一步演进为“以 ModuleId 定位模块树，以 FieldId 标识逻辑字段，沿模块树确定关系上下文，先完成 Module/Field/Relation 解析，再渲染 SQL”。**

同时，层级写入已经从普通 DML 中分离出来：普通 SQL 保持数据库式的 INSERT / UPDATE / DELETE 语义；模块树级联保存通过 Aggregate Mutation 明确表达，返回结果可以通过 `clientKey -> generatedKey` 完成前端临时节点回填。memcite

当前最重要的架构边界：

```text
上游完成 Field Permission
    ↓
ModuleId 定位 Root Module Tree
    ↓
FieldId 解析逻辑字段
    ↓
沿模块树确定关系上下文
    ↓
实际需要的物理表 JOIN 预解析
    ↓
业务 Filter + Data Permission → WHERE / EXISTS
    ↓
LIMIT / OFFSET
    ↓
jOOQ 渲染
```

下一阶段继续以这个边界推进：关系的唯一性和自洽性属于语义/元数据阶段，权限属于上游字段裁剪或附加 Predicate，renderer 不再承担任何业务关系猜测。