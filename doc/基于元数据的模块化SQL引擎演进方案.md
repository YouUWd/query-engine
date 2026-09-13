# 基于 Module 的 SQL 虚拟执行引擎演进方案

## 1. 核心战略与定位调整

### 1.1 业务去敏与平台化
彻底移除 `PagedResult`、`HeaderNode` 等与特定业务/UI紧密耦合的临时模型，将项目重塑为一个**纯粹的“基于 Module 的 SQL 虚拟执行引擎平台”**。
- 业务系统无需关心多表如何 JOIN、1:N 嵌套如何查询、物理表外键如何维护。
- 平台以**标准 SQL 文本**作为交互媒介，接受面向业务模块的 SQL，内部自动推导关系树并下发执行。

### 1.2 阶段规划（KISS 原则）
- **第一步（当前阶段）**：**聚焦于“Module SQL 执行引擎”**。提供标准、纯粹的 Java API（如 `ModuleSqlEngine.executeQuery(sql)`），输入模块级 SQL，输出标准的元数据与数据结果。不急于引入复杂的 JDBC Driver 规范模板代码。
- **第二步**：当引擎核心稳定后，外部可极其轻松地在其上封装一层标准的 JDBC Driver（`java.sql.Driver`），实现零代码改动对接任何第三方数据工具。

---

## 2. 平台通用契约设计（取代 PagedResult & HeaderNode）

引擎的输入和输出彻底通用化，不依赖任何特定前端视图概念：

### 2.1 列元数据 `ColumnMeta`（取代 HeaderNode）
表示查询结果中每个字段的标准元信息：
```java
public record ColumnMeta(
    long fieldId,             // 字段 ID
    long moduleId,            // 所属模块 ID
    String moduleName,        // 所属模块名称
    String tableName,         // 底层物理表名
    String columnName,        // 底层物理列名
    String columnAlias,       // SQL 别名（如 SELECT f3 AS name 中的 "name"）
    SysFieldType dataType     // 字段数据类型（STRING, NUMBER, DATETIME 等）
) {}
```
> **UI 表头解耦说明**：前端若需要多级折叠/分组树形表头，由前端或业务 BFF 层根据 `ColumnMeta` 携带的 `moduleId` 与系统已有的模块树结构自行组装，引擎平台核心杜绝任何 UI 视图字段（如 `dataIndex`、`label` 等）。

### 2.2 查询结果集 `ModuleQueryResult`（标准结果集，纯粹 ResultSet 语义）
严格恪守标准 SQL 规范，`ModuleQueryResult` 不强行绑定业务分页的 `totalCount`，只承载当前游标返回的列与数据行：
```java
public record ModuleQueryResult(
    List<ColumnMeta> columns,         // 列元数据描述（类似 ResultSetMetaData）
    List<Map<String, Object>> rows    // 结构化行数据（形式 A 层次化对象列表）
) {}
```
> **关于总数（COUNT）的标准规范**：
> 标准 SQL 结果集严禁掺杂隐式 `totalCount`。若需要总行数，遵循标准 SQL 发起独立的聚合查询：
> ```sql
> SELECT COUNT(*) FROM `101` WHERE `3` LIKE '张%'
> ```
> 引擎解析器将 `COUNT(*)` 直接编译为根物理表的计数 SQL，返回包含单行单列的标准 `ModuleQueryResult`。这不仅完全对齐标准 SQL 规范，更能让流式滚动加载、无分页查询等场景免于执行昂贵的无谓 count 扫描。

### 2.3 变更执行结果 `ModuleUpdateResult`（用于 DML）
```java
public record ModuleUpdateResult(
    int affectedRows,                 // 受影响行数
    List<Object> generatedKeys        // 插入生成的主键列表
) {}
```

---

## 3. 模块级 SQL 语法规范与语义

### 3.1 DQL 查询语法规范 (SELECT)
```sql
SELECT f3, f49, f32 
FROM 101 
WHERE f3 LIKE '张%' AND f32 = '高等数学' 
ORDER BY f3 DESC 
LIMIT 0, 10
```
- **字段标识符规范（Field Identifier）**：
  在底层元数据中，字段唯一物理主键为 `field_id (Long)`，针对纯数字标识符及重名冲突，引擎支持三种表达模式：
  1. **反引号主键模式（推荐，机器与 UI 首选，0 歧义）**：
     标准 SQL 将纯数字视为数值常量，使用标准反引号括起：
     ```sql
     SELECT `3`, `49`, `32` FROM `101` WHERE `3` LIKE '张%' LIMIT 0, 10
     ```
  2. **`f` 前缀别名模式（手写与轻量解析首选，0 歧义）**：
     直接以 `f` 加 Long 主键表达，便于手写且天然是合法 SQL 标识符：
     ```sql
     SELECT f3, f49, f32 FROM 101 WHERE f3 LIKE '张%' LIMIT 0, 10
     ```
  3. **层级限定名模式（人类可读，解决跨模块/表重名冲突）**：
     > **注意**：多个模块下完全可能出现同名表和同名字段。因此不能简单使用 `table.column`，而是通过 **`module.table.column`（三段式）** 或在当前根模块上下文内的 **`sub_module.table.column`** 唯一定位底层唯一的 `fieldId`：
     ```sql
     SELECT 
         base_info.student.name, 
         study_info.clazz.clazz_name 
     FROM 101 
     WHERE base_info.student.name = '张三'
     ```
     若字段名在当前根模块及其子树中全局唯一，允许简写为 `table.column` 或 `column`；一旦出现重名歧义，引擎解析期抛出 `AmbiguousColumnException` 并明确提示其使用三段式全路径或反引号 `fieldId`。
- **SELECT 投影**：
  - 支持上述字段标识（反引号 `` `3` ``、`f3`、`module.table.col`）；
  - 支持别名：`f3 AS student_name` 或 `` `3` AS student_name ``；
  - 支持通配符：`SELECT *`（自动展开该根模块树下所有可见字段）。
- **FROM 驱动源**：
  - 指定根模块 ID，例如 `FROM 101`、``FROM `101` ``（或根模块语义编码 `FROM student_module`）。
- **WHERE 过滤条件**：
  - 支持运算符：`=, !=, >, >=, <, <=, LIKE, IN, IS NULL, IS NOT NULL, AND, OR`；
  - **自动语义穿透**：落在 1:N 嵌套模块的过滤条件，自动编译为根表的 `EXISTS` 子查询及子表筛选。
- **ORDER BY 排序**：
  - 支持在根物理组及平铺组字段上排序。
- **LIMIT / OFFSET 分页**：
  - `LIMIT 10 OFFSET 0` 或 `LIMIT 0, 10`，驱动方案 B 轻量级分页。

### 3.2 DML 变更语法规范 (定制化扩展：方案 A 扩展 JSON Payload)

传统 ANSI SQL 仅支持扁平标量列，天然无法表达 **1:N 嵌套列表、树形结构及跨表聚合根**。为了完美契合前端复杂富表单提交与聚合实体保存，DML 采用 **方案 A（扩展 JSON Payload 规范）**，兼顾简单标量与复杂树形级联。

---

#### 1. UPDATE 定制化语法规范

##### ① 模式 1：标准标量更新（用于当前根表及 1:1 平铺表）
```sql
UPDATE `101` 
SET `3` = '李四', `49` = '计科2602班' 
WHERE `2` = 'S2026001'
```
- **物理表自动分桶**：自动识别 `3`（属于 student 表）与 `49`（属于 student_ext 平铺表），在单一事务中拆解为多条底层单表 UPDATE 语句原子提交。

##### ② 模式 2：声明式 1:N 级联同步更新（方案 A：富表单整树同步）
当需要同时修改主信息并级联增/删/改 1:N 子表时，通过直接赋值子模块 JSON 数组实现声明式同步：
```sql
UPDATE `101` 
SET 
    `3` = '张三丰',
    `105` = [                           -- 105 为选课子模块(1:N)
        { `id`: 12, `33`: 98 },         -- 带有子实体主键 -> 执行 UPDATE
        { `31`: 'C003', `32`: '物理', `33`: 90 } -- 无主键 -> 执行 INSERT，自动注入父外键
        -- 若数据库中原有的 id=11 不在此列表中 -> 自动触发 Orphan Removal 执行 DELETE
    ]
WHERE `2` = 'S2026001'
```
- **生命周期自动化**：引擎内部自动比对数据库现有快照与传入列表的差量（Diff），在单一物理事务中自动调度子表的新增、更新以及孤儿数据物理删除。

---

#### 2. INSERT 定制化语法规范

##### ① 模式 1：标准扁平插入（简单单条记录）
```sql
INSERT INTO `101` (`2`, `3`, `49`) 
VALUES ('S2026006', '王五', '计科2601班')
```

##### ② 模式 2：整树 Payload 级联插入（方案 A：聚合根一键保存）
极度契合前端富表单（Form）一次性提交完整复杂实体的场景：
```sql
INSERT INTO `101` 
VALUES {
    `2`: 'S2026008',
    `3`: '赵六',                       -- 学生姓名 (主物理表)
    `102`: { `10`: '北京市海淀区' },    -- 1:1 平铺扩展模块
    `105`: [                          -- 1:N 选课子模块列表
        { `31`: 'C001', `32`: '高等数学', `33`: 95 },
        { `31`: 'C002', `32`: '大学英语', `33`: 88 }
    ]
}
```
- **事务自上而下（Top-Down）下发**：
  1. 优先插入主表并捕获生成的主键（Generated Key）；
  2. 自动回填外键至 1:1 平铺扩展表及 1:N 子表各行并批量插入；
  3. 全程由单一本地/分布式数据库事务包裹。

---

#### 3. DELETE 语法规范
```sql
DELETE FROM `101` WHERE `2` = 'S2026001'
```
- 针对业务主实体执行删除，引擎自动触发 3.3 节定义的自底向上 1:N 拓扑级联删除。

---

### 3.3 核心处理机制：1:N 级联更新与关联删除 (Cascade Lifecycle)

在真实业务场景中，`1:N` 绝大多数是“聚合附属（Composition）”关系。因为许多物理数据库严禁配置物理外键级联（`ON DELETE CASCADE`），**级联删除与生命周期维护必须由本虚拟引擎在事务中显式管理**。

#### 1. 显式 DELETE：拓扑逆序（Bottom-Up）自底向上级联删除
当调用方执行 `DELETE FROM 101 WHERE ...` 时，引擎防止外键约束报错和孤儿数据残留：
1. **锁定根记录主键**：查出匹配条件的根实体主键集合（如 `student_id IN (1)`）；
2. **推导多层级依赖拓扑**：
   - 依赖链：`student(101)` -> `student_course(105)` -> `student_course_score_item(108)`；
   - 依赖链：`student(101)` -> `student_award(104)`；
3. **在单事务中，严格按“自底向上”逆序执行物理删除**：
   ```sql
   -- 第一步：先删除最底层的孙子表
   DELETE FROM student_course_score_item 
   WHERE student_course_id IN (SELECT id FROM student_course WHERE student_id = 1);

   -- 第二步：再删除子表
   DELETE FROM student_course WHERE student_id = 1;
   DELETE FROM student_award WHERE student_id = 1;

   -- 第三步：最后安全删除主表记录
   DELETE FROM student WHERE id = 1;
   ```
4. **软删除支持**：若模块配置了逻辑删除标记列（如 `is_deleted`），自动将物理 `DELETE` 转为逆序联动 `UPDATE ... SET is_deleted = 1`。

#### 2. UPDATE 中的 1:N 关联删除（孤儿清理 Orphan Removal）
当业务进行包含子表的富表单整体更新时，前端通常提交子列表的新状态。
- **Diff 差量检测**：比对数据库已存子 ID 集合（`OldList`）与提交集合（`NewList`）；
- **级联删除孤儿**：存在于 `OldList` 但不在 `NewList` 中的记录（被用户在界面删除的行），自动触发级联清理；
- **同步更新/插入**：保留的行执行 `UPDATE`，新行执行带父级外键的 `INSERT`。

#### 3. 1:N 精准更新的最佳实践
- **修改主实体**：直接 `UPDATE 101 SET ... WHERE ...`；
- **修改某条子记录**：以子模块为根执行精确修改 `UPDATE 105 SET f33 = 95 WHERE id = 1001`，语义完全无歧义。

---

## 4. 引擎核心处理流水线 (Pipeline)

```
                    输入 SQL 文本: "SELECT f3, f49 FROM 101 WHERE f3 LIKE '张%' LIMIT 10"
                                                │
                                                ▼
       ┌─────────────────────────────────────────────────────────────────┐
       │                 1. 语法解析层 (ModuleSqlParser)                  │
       │  - 基于 JSqlParser 构建 AST 抽象语法树                           │
       │  - 语法校验，提取 rootModuleId、投影列、WHERE 表达式、LIMIT/ORDER │
       └────────────────────────────────┬────────────────────────────────┘
                                        │
                                        ▼
       ┌─────────────────────────────────────────────────────────────────┐
       │                2. 语义编译层 (ModuleQueryCompiler)                │
       │  - 校验 fieldId 属于 rootModuleId 及其合法子孙模块                │
       │  - 构造 List<ColumnMeta> 列定义                                  │
       │  - 将 WHERE 表达式树转为 jOOQ Condition 与 EXISTS 穿透过滤        │
       │  - 产出编译后的执行规格 (ExecutionPlan)                          │
       └────────────────────────────────┬────────────────────────────────┘
                                        │
                                        ▼
       ┌─────────────────────────────────────────────────────────────────┐
       │                 3. 关系规划层 (QueryTreeBuilder)                 │
       │  - 模块树骨架计算、SAME_ENTITY 平铺合并、1:N 嵌套分组推导          │
       │  - 产出 FlatGroup 树                                            │
       └────────────────────────────────┬────────────────────────────────┘
                                        │
                                        ▼
       ┌─────────────────────────────────────────────────────────────────┐
       │        4. 物理执行与内存组装 (FlatGroupSqlBuilder + 方案 B 加载)   │
       │  - 轻量级 Count 物理查询（无 MULTISET）                           │
       │  - 分页加载根表数据                                              │
       │  - 批量 IN 递归加载子级数据并内存装配                             │
       │  - RecordRenderer 渲染为层次化 Map（形式 A）                     │
       └────────────────────────────────┬────────────────────────────────┘
                                        │
                                        ▼
             输出标准结果: ModuleQueryResult(columns, rows, totalCount)
```

---

## 5. 平台公开服务接口定义 (`ModuleSqlEngine`)

平台的核心入口极度纯粹明晰：

```java
package com.example.schoolquery.engine;

import org.jooq.DSLContext;
import java.util.List;

public interface ModuleSqlEngine {

    /**
     * 执行基于 Module 的 DQL 查询 SQL
     *
     * @param dsl 业务库 DSL 上下文
     * @param sql 模块级 SELECT 语句
     * @return 包含列元数据、数据行与总条数的标准结果集
     */
    ModuleQueryResult executeQuery(DSLContext dsl, String sql);

    /**
     * 仅获取 SQL 结果的列元数据（不执行真实数据查询，毫秒级纯元数据推导）
     *
     * @param sql 模块级 SELECT 语句
     * @return 列描述列表
     */
    List<ColumnMeta> getMetadata(String sql);

    /**
     * 执行基于 Module 的 DML 变更 SQL (UPDATE / INSERT / DELETE)
     *
     * @param dsl 业务库 DSL 上下文
     * @param sql 模块级 DML 语句
     * @return 受影响行数与自增主键信息
     */
    ModuleUpdateResult executeUpdate(DSLContext dsl, String sql);
}
```

---

## 6. 第一步实施路线与里程碑

1. **步骤 1：引入标准模型与依赖**
   - 在 `pom.xml` 中引入轻量依赖 `com.github.jsqlparser:jsqlparser:4.9`；
   - 建立 `ColumnMeta` 与 `ModuleQueryResult`，彻底标记废弃并移除 `PagedResult` 和 `HeaderNode`。
2. **步骤 2：实现 `ModuleSqlParser` 与编译管道**
   - 解析 `SELECT` 子句为 `fieldIds`；
   - 解析 `FROM` 子句为 `rootModuleId`；
   - 解析 `WHERE` 表达式为 jOOQ `Condition`（并对接穿透 EXISTS）；
   - 解析 `ORDER BY` 与 `LIMIT`。
3. **步骤 3：组装 `ModuleSqlEngine` 并对接底层引擎**
   - 对接已稳固落地的【方案 B 批量加载】与【形式 A 渲染器】；
   - 编写单元测试与端到端 SQL 查询集成测试。
4. **后续演进储备（未来按需开启）**：
   - 完善 UPDATE / INSERT 多表分发；
   - 封装标准 JDBC Driver（`java.sql.Driver`）。
