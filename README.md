# query-engine

按新规则（模块内部只允许 1:1/N:1，1:N 必须拆成子模块）重新组织的元数据驱动查询引擎。
取代了之前散在聊天里的 01~14 号代码片段——功能等价，但拆成了正常的多包 Maven 工程，
并配了真实可跑的测试。

## 模块结构

```
src/main/java/com/example/schoolquery/
├── model/        SysModule / SysModuleField / SysTableRelation / RelationType / ModuleRelationKind
├── metadata/      MetadataRegistry（索引+查询）、MetadataCsvLoader（从 sys_*.csv 加载）、
│                  MetadataValidationException（配置本身有问题时抛出）
├── relation/      RelationResolver —— 新规则的判断 + 校验都在这里
├── permission/    行级数据权限（表+字段 IN 过滤）：SysDataScopeRule（声明哪张表按
│                  哪一列做范围限制）、PermissionContext（调用方提供的允许值集合）、
│                  PermissionRegistry（索引 + 查找）
├── querytree/     FlatGroup / NestedGroup / QueryTreeBuilder —— 公用查询树构建方法（诉求3）
├── sql/           FlatGroupSqlBuilder —— 把查询树变成真正的 jOOQ 查询（含行级权限过滤）
└── service/       FieldDrivenQueryService —— 诉求1 queryByModuleAndFields / 诉求2 queryByFieldsOnly
```

对照关系（如果你想找之前某个片段挪到哪去了）：

| 旧文件 | 现在在哪 |
|---|---|
| 06-ModuleMetadataModel.java | model/*.java + metadata/MetadataRegistry.java（拆开了） |
| 07-RelationResolver.java | relation/RelationResolver.java（按新规则简化，去掉了 AGGREGATE_MULTI） |
| 08/09（SingleModuleQueryBuilder / ModuleTreeQueryBuilder） | 已废弃，被 querytree + sql 两层取代 |
| 10-SchoolMetadataDemo.java（硬编码字面量） | metadata/MetadataCsvLoader.java（直接读 CSV，不再手抄字段，避免转录错误） |
| 12/13/14 | querytree/QueryTreeBuilder.java、sql/FlatGroupSqlBuilder.java、service/FieldDrivenQueryService.java（原样迁移，路径变了） |

## 三个诉求怎么解决的

1. **`FieldDrivenQueryService.queryByModuleAndFields(dsl, moduleId, fieldIds, condition)`**
   字段可以来自 moduleId 自身或它的任意子孙模块；如果传了一个不在这棵子树里的字段会直接报错。
2. **`FieldDrivenQueryService.queryByFieldsOnly(dsl, fieldIds, condition)`**
   不传 moduleId，自动取这些字段所属模块在模块树里的最近公共祖先当根。
3. **`QueryTreeBuilder`** 就是公用方法本身——上面两个入口只是"根节点怎么给"不一样，
   其余（找骨架子树 → 分类 SAME_ENTITY / CHILD → 递归建 FlatGroup）完全复用。

## 数据权限（行级：表+字段 IN 过滤）

**字段级权限不是这个引擎的职责。** 调用方组装 `fieldIds` 之前应该已经按权限过滤过了——
传进来的字段列表本身就是"这个人能看的字段"，`FlatGroupSqlBuilder` 不会、也不应该再对字段做
一次权限判断。

行级权限统一成一种形式：**表 + 字段 IN 过滤**。`SysDataScopeRule(tableName, scopeColumn,
contextKey)` 声明"这张表按哪一列做范围限制"，实际允许的值由调用方通过 `PermissionContext`
在运行时提供。不区分"只能看自己"和"只能看一批"两种类型——前者就是传一个只有一个元素的集合，
语义上仍然是 `IN`：

```java
PermissionContext ctx = PermissionContext.builder()
        .scopeSelf("currentUserId", 1L)          // IN (1)
        // 或者 .scopeList("allowedClazzIds", List.of(1L, 2L))  // IN (1, 2)
        .build();

service.queryByModuleAndFields(dsl, 101L, fieldIds, condition, ctx);
```

只作用于每个 `FlatGroup` 自己的 `primary_table`（根节点 + 每一层嵌套子级的驱动表），不作用于
被平铺 JOIN 进来的参照表（`clazz`、`teacher` 这类——能看到学生这一行，就该能看到它平铺关联出来
的班级名称）。`permissionContext` 传 `null` 表示完全不启用行级过滤，两个查询入口、
`FlatGroupSqlBuilder.build()` 都保留了不带这个参数的老签名，现有调用方不用改代码。

`sys_data_scope_rule` 目前还没有对应的真实导出 CSV（你们平台上如果已经有类似的权限配置表，
直接照着 `MetadataCsvLoader` 的写法加一个 loader 方法就行；这里先给了能直接用 List 字面量
构造 `PermissionRegistry` 的构造器，方便先跑起来）。

## 分页查询接口（moduleId + fields + filters + sorts + header）

在 `query`（请求/响应 DTO）、`render`（过滤条件构建 + Record→嵌套Map渲染）、
`header`（表头树）三个包之上，`service/PagedFieldDrivenQueryService` 把整条链路串起来：

```java
QueryRequest request = new QueryRequest(
        101L, 1, 10,
        List.of(3L, 49L, 32L, 33L),                       // fields：按 sys_module_field.id
        List.of(new FilterCriterion(3L, FilterOperator.LIKE, "张"),
                new FilterCriterion(32L, FilterOperator.EQ, "高等数学")),
        List.of(new SortCriterion(2L, SortDirection.DESC)),
        true                                                // withHeader
);
PagedResult result = pagedService.execute(dsl, request);
```

### 输出结构

不再是一行拍平的 Record，而是按 `table_name` 分桶 + 按子模块 id 嵌套数组：

```json
{
  "101": {
    "student": { "name": "张三" },
    "clazz": { "clazz_name": "计科2601班" },
    "105": [
      { "student_course": { "course_name": "高等数学", "score": 95.0 } }
    ]
  }
}
```
一个 `FlatGroup`（不管是根节点还是某一层嵌套）自己的字段、以及 SAME_ENTITY 合并进来的
模块的字段，按 `table_name` 分桶成子 Map；每个 1:N 嵌套子级则是一个以 `childModuleId`
（字符串）为 key 的数组。最外层只有根节点包一层 `moduleId`，数组元素本身不再重复包一层
（父级的 key 已经说明了是哪个子模块）。

`FlatGroupSqlBuilder` 内部把每个字段用 `"f" + fieldId` 做别名、每个嵌套多值字段用
`"m" + childModuleId` 做别名（而不是拿原始列名当别名）——这是为了让两张表恰好同名的列
（两张表都有 `id`）不会互相冲突，`RecordRenderer` 也正是靠这套别名约定，从 `Record`
反查出每一列该归到哪张表、哪个字段。

### 过滤（filters）

- 落在**查询根节点自己组内**（根节点主表，或组内 SAME_ENTITY/平铺 JOIN 进来的字段所在表）
  的过滤条件，直接 AND 进根节点的 WHERE。
- 落在**某个嵌套子级自己的 primary_table** 上的过滤条件，会做两件事：① 在那一层自己的
  查询里把条件 AND 进去（嵌套数组本身就只剩匹配的行）；② 在根节点的 WHERE 里额外叠加
  一条 `EXISTS(...)`（用查询树里记录的关系链一路相关子查询下去），确保没有匹配子行的
  父行也会被整体排除，而不是返回一个空数组。
- **已知限制**：过滤字段如果是嵌套子级内部平铺 JOIN 进来的参照表（不是子级自己的
  `primary_table`），暂不支持，会报错——目前还没有实际场景覆盖到这种情况。

### 排序（sorts）

只支持根节点自己组内的字段——对嵌套 1:N 数组排序在语义上是"父行按什么顺序排"这个
问题本身没有唯一答案，所以选择了直接报错而不是猜一个行为。

### 分页与总数

`total` 的计算方式是把完整查询（含嵌套 MULTISET）当成一张派生表再 `COUNT(*)`——
实现简单、结果保证正确，但会比"只 COUNT 根表"多算一点嵌套聚合的开销；如果以后
性能有压力，可以把 COUNT 单独拆一条不含 MULTISET 字段的查询来优化，当前先用这个
更简单但更慢一点的版本。

### 虚拟模块

`SysModule.primaryTable` 可以是 `null`（或空字符串），代表这是一个纯分组用的"虚拟模块"，
不对应任何物理表：
- 在**查询树**（`FlatGroup`）里完全不出现——它的真实子模块跳过它，直接相对它最近的
  真实祖先做表关联判断，就像虚拟模块不存在一样。
- 在**表头树**（`HeaderNode`）里会保留成一个只有 `moduleId`/`label`/`children`、
  没有对应字段的分组节点——因为表头是给前端做导航分组用的，虚拟模块存在的意义就是
  提供这一层分组。
- 校验：`MetadataRegistry` 在加载时会拒绝"虚拟模块下面配置了字段"这种数据（虚拟模块
  没有表，不应该有字段）；`QueryTreeBuilder.buildFromRoot` 也会拒绝把虚拟模块当根节点。

## Field<?> / Field<Object> 的泛型处理

动态字段访问（表名/列名都是运行时字符串，没有代码生成类型）绕不开一个真实的编译期
问题：`Table<?>.field(String)` 返回 `Field<?>`，不能直接赋给 `Field<Object>`——
这两个是不兼容的参数化类型，必须显式 cast，之前散落在各处的 `Field<Object> x =
table.field(...)` 直接赋值其实是编译不过的。

处理方式是把这一次 cast 收敛到一个地方：`sql/DynamicFields.field(Table<?>, String)`。
拿到 `Field<Object>` 之后，`eq/ne/gt/lt/like/in/between` 这些操作符方法可以直接调用——
因为它们的参数类型要么是 `T`（此时 T=Object，任何值都能传），要么本来就是
`Collection<?>`（`in`）——不需要再对着每一个 jOOQ 泛型 API 单独补一次
`@SuppressWarnings` 或者转成裸类型 `Field`。`FilterConditionBuilder` 就是照这个
方式写的，全程用 `Field<Object>`，没有一处裸类型转换。

如果字段确实需要按类型比较（数字大小、日期范围，而不是字符串比较），在
`FilterConditionBuilder.toCondition(...)` 上多传一个 `FieldDataType`（配置在
`render/FieldTypeRegistry`，table+column -> 类型），请求里过滤条件的原始值会先经过
`FieldValueConverter` 转换成对应的 Java 类型（`BigDecimal`/`LocalDate`/`LocalDateTime`/
`Boolean`/...）再交给 jOOQ 绑定参数——不传就完全不转换，按原样传给 jOOQ，兼容不需要
这层的调用方。

## 测试全部走 H2，CSV 只是配置库数据的导出快照

`src/test/resources/config-data-h2.sql`（配置库种子数据）是从真实的 `sys_module.csv`
/ `sys_module_field.csv` / `sys_table_relation.csv` **程序生成**的，不是手抄的——生成
脚本见本次回复里贴的 Python 代码，逻辑很简单，就是把 CSV 每一行转成一条 INSERT。
之所以要这么做，是因为这三张表本身就是配置库（config库）的内容，CSV 只是它的一次性
导出快照；测试不应该依赖一份可能过期的 CSV 文件，而应该像生产环境一样，通过
`metadata/MetadataDbLoader` 对着配置库的连接查。

每个测试类起两个独立的 H2 内存库：

- **config 库**：`TestDatabases.openConfigDb(name)`（8个真实模块的完整配置）或
  `TestDatabases.openVirtualModuleConfigDb(name)`（虚拟模块场景的小配置）或
  `TestDatabases.openEmptyConfigDb(name)`（只建表，测试自己插入反例数据）
- **school 业务库**：`TestDatabases.openSchoolDb(name)`（student/course/... 这些表 +
  school.sql 里的真实样例数据）

`MetadataCsvLoader` 还留在 main 源码里（给一次性迁移/初始化工具用——比如把 CSV 导出
批量灌进正式的配置库），但测试全部改成走 `MetadataDbLoader` + H2，不再直接解析 CSV。

## 虚拟模块的测试覆盖

- `metadata/MetadataRegistryTest#virtualModuleConfigLoadsWithNullPrimaryTable` ——
  从 H2 加载出来的虚拟模块 `primaryTable` 确实是 null，`nearestRealAncestor` 能正确跳过它
- `relation/RelationResolverTest#virtualModuleHasNoPrimaryTableToCompare` —— 直接拿虚拟
  模块去 `resolveParentChild` 会报错（这正是为什么 QueryTreeBuilder 要主动跳过它）
- `querytree/QueryTreeBuilderTest` —— 虚拟模块在数据树里被跳过、不能作为查询根节点、
  自动找根时如果落在虚拟模块上会被提升到最近的真实祖先
- `header/HeaderTreeBuilderTest` —— 虚拟模块在表头树里保留成一个分组节点
- `service/VirtualModuleQueryH2IT` —— **端到端**：真的通过虚拟模块跑一次分页查询
  （建树 -> 生成 SQL -> 执行 -> 渲染结果 -> 生成表头全链路），而不只是验证树的形状

## 关于 java.lang.Record / org.jooq.Record 的编译错误

如果你在别的地方也写 `import org.jooq.*;` 又用到了 `Record`，会撞上 Java 16+ 内置的
`java.lang.Record`（record 关键字的基类，`java.lang` 包总是被隐式导入），编译器会报"对
'Record' 的引用不明确"。解决办法是加一行显式单类型导入：`import org.jooq.Record;`——单类型导入
的优先级高于通配符导入和 `java.lang` 的隐式导入，能解决歧义。`FlatGroupSqlBuilder.java` 已经
这么改了。

## 跑测试

```bash
mvn test
```

**重要说明**：这份代码是在一个只有 JRE、没有 JDK/Maven、也连不上 Maven 中央仓库的沙箱环境里写的，
所以我没法在这边实际编译、运行它。测试是照着 jOOQ 3.19 的真实 API 尽量写对的，逻辑也手工过了一遍，
但请你在本地跑一次 `mvn test` 确认；如果有编译错误或断言不对，把报错发给我，我照着改。

测试全部基于 H2（配置库 + school 业务库都真实建表跑 SQL，见上一节），分七类：

- `metadata/MetadataRegistryTest` —— 从 H2 配置库加载 + 索引是否正确，含虚拟模块加载
- `relation/RelationResolverTest` —— **把之前手工跑的那次 Python 一致性校验，变成了跟着代码一起跑的测试**
  （`allModulesInRealMetadataObeyTheOneToOneOrManyToOneRule`），另外几个反例往空配置库里插入违规数据验证
  校验逻辑真的会报错，含虚拟模块场景
- `querytree/QueryTreeBuilderTest` —— 查询树的形状（合并/嵌套/多层嵌套/自动找根/虚拟模块跳过/
  虚拟模块不能当根/自动找根提升到真实祖先）
- `header/HeaderTreeBuilderTest` —— 虚拟模块在表头树里保留成分组节点
- `permission/PermissionRegistryTest` —— 纯逻辑，行级规则的索引、`PermissionContext` 的取值行为
- `service/FieldDrivenQueryServiceH2IT` + `service/PagedFieldDrivenQueryServiceH2IT` —— 诉求1/诉求2两个
  入口 + 行级权限、完整的"请求 -> 分页响应"链路（根节点过滤、嵌套 EXISTS 过滤、排序、分页、表头）
- `service/VirtualModuleQueryH2IT` —— 虚拟模块的端到端分页查询

## 已知需要你确认的点

- `pom.xml` 里 jOOQ 用的是 `3.19.11`，如果你们内部锁定了别的具体版本号，改一下即可，API 兼容。
- CSV 加载器假设列名固定为 `id / module_code / module_name / primary_table / parent_id`
  （sys_module）等——如果你们导出格式后续有变，`MetadataCsvLoader` 需要跟着改。
- 所有表主键列名硬编码假设是 `id`（和 school.sql 一致）。
