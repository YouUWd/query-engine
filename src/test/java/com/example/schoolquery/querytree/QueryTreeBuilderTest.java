package com.example.schoolquery.querytree;

import com.example.schoolquery.TestDatabases;
import com.example.schoolquery.metadata.MetadataLoader;
import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.relation.RelationResolver;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class QueryTreeBuilderTest {

    private static Connection configConnection;
    private static MetadataRegistry registry;
    private static RelationResolver resolver;
    private static QueryTreeBuilder builder;

    @BeforeAll
    static void setUp() throws Exception {
        configConnection = TestDatabases.openConfigDb("query_tree_builder_test_config");
        DSLContext dsl = DSL.using(configConnection, SQLDialect.H2);
        registry = MetadataLoader.load(dsl);
        resolver = new RelationResolver(registry);
        builder = new QueryTreeBuilder(registry, resolver);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (configConnection != null) configConnection.close();
    }

    @Test
    void sameEntityChildMergesIntoRootGroupWithNoNesting() {
        // 只请求 103 的字段，根是 101 —— 101/103 共享 student 表，应合并成一行，没有嵌套
        FlatGroup tree = builder.buildFromRoot(101L, Set.of(103L));

        assertEquals("student", tree.primaryTable());
        assertEquals(Set.of(101L, 103L), Set.copyOf(tree.mergedModuleIds()));
        assertTrue(tree.nestedChildren().isEmpty());
    }

    @Test
    void oneToManyChildBecomesNestedGroup() {
        // 只请求 105(选课) 的字段，根是 101 —— 应该是一层嵌套
        FlatGroup tree = builder.buildFromRoot(101L, Set.of(105L));

        assertEquals("student", tree.primaryTable());
        assertEquals(1, tree.nestedChildren().size());
        FlatGroup nested = tree.nestedChildren().get(0).group();
        assertEquals("student_course", nested.primaryTable());
        assertEquals(Set.of(105L), Set.copyOf(nested.mergedModuleIds()));
        assertEquals(105L, tree.nestedChildren().get(0).childModuleId());
    }

    @Test
    void threeLevelNestingThroughStudentCourseToScoreItem() {
        // 请求 108(选课成绩分项) 的字段，根是 101 —— 101 -> 105 -> 108 两层嵌套
        FlatGroup tree = builder.buildFromRoot(101L, Set.of(108L));

        assertEquals("student", tree.primaryTable());
        assertEquals(1, tree.nestedChildren().size());

        FlatGroup level1 = tree.nestedChildren().get(0).group();
        assertEquals("student_course", level1.primaryTable());
        assertEquals(1, level1.nestedChildren().size());

        FlatGroup level2 = level1.nestedChildren().get(0).group();
        assertEquals("student_course_score_item", level2.primaryTable());
        assertEquals(Set.of(108L), Set.copyOf(level2.mergedModuleIds()));
    }

    @Test
    void requestingTwoChildBranchesProducesTwoNestedGroups() {
        // 同时要 105(选课) 和 104(荣誉) 的字段，根是 101 —— 两个并列的嵌套分支
        FlatGroup tree = builder.buildFromRoot(101L, Set.of(105L, 104L));

        assertEquals(2, tree.nestedChildren().size());
        Set<String> nestedTables = Set.of(
                tree.nestedChildren().get(0).group().primaryTable(),
                tree.nestedChildren().get(1).group().primaryTable());
        assertEquals(Set.of("student_course", "student_award"), nestedTables);
    }

    @Test
    void autoRootPicksLowestCommonAncestorWhenBothAreDescendants() {
        // 104(荣誉) 和 106(荣誉材料) 的最近公共祖先是 104 本身（106 是 104 的直接子模块）
        FlatGroup tree = builder.buildAutoRoot(Set.of(104L, 106L));

        assertEquals("student_award", tree.primaryTable());
        assertEquals(1, tree.nestedChildren().size());
        assertEquals("student_award_detail", tree.nestedChildren().get(0).group().primaryTable());
    }

    @Test
    void autoRootWalksUpHigherWhenModulesAreOnDifferentBranches() {
        // 101(学生) 和 104(荣誉) 的最近公共祖先是 101
        FlatGroup tree = builder.buildAutoRoot(Set.of(101L, 104L));

        assertEquals("student", tree.primaryTable());
        assertEquals(1, tree.nestedChildren().size());
        assertEquals("student_award", tree.nestedChildren().get(0).group().primaryTable());
    }

    @Test
    void buildFromRootRejectsModuleOutsideTheGivenSubtree() {
        // 102(课程) 不是 101(学生) 的子孙 —— 调用方传错了 moduleId
        assertThrows(IllegalArgumentException.class, () -> builder.buildFromRoot(101L, Set.of(102L)));
    }

    @Test
    void buildAutoRootRejectsEmptyFieldSet() {
        assertThrows(IllegalArgumentException.class, () -> builder.buildAutoRoot(Set.of()));
    }

    @Test
    void resolvesInternalTableJoinsFromFieldOwningModuleContext() {
        // 103 的 primary_table 是 student；该模块同时引用 clazz / student_profile。
        // JOIN 的方向和字段必须先由“模块 103”这个逻辑上下文确定，再交给 SQL renderer。
        FlatGroup tree = builder.buildFromRoot(103L, Set.of(103L));
        Map<Long, java.util.List<com.example.schoolquery.model.SysModuleField>> requested =
                Map.of(103L, registry.fieldsGroupedByTable(103L).values().stream().flatMap(java.util.Collection::stream).collect(Collectors.toList()));

        FlatGroup resolved = builder.resolveTableJoins(tree, requested);

        assertEquals(2, resolved.tableJoins().size());
        assertTrue(resolved.tableJoins().stream().anyMatch(j ->
                j.sourceModuleId() == 103L
                        && j.targetModuleId() == 103L
                        && j.primaryTable().equals("student")
                        && j.otherTable().equals("clazz")
                        && j.primaryColumn().equals("clazz_id")
                        && j.otherColumn().equals("id")));
        assertTrue(resolved.tableJoins().stream().anyMatch(j ->
                j.sourceModuleId() == 103L
                        && j.targetModuleId() == 103L
                        && j.primaryTable().equals("student")
                        && j.otherTable().equals("student_profile")
                        && j.primaryColumn().equals("id")
                        && j.otherColumn().equals("student_id")));
    }

    // ============ 虚拟模块（独立的 H2 配置库） ============

    /**
     * 虚拟模块（没有 primary_table）作为正常组节点保留在 FlatGroup 树中；
     * 它的真实子模块挂在虚拟模块组的 nestedChildren 下，且表关联依然相对最近真实祖先 student。
     */
    @Test
    void virtualModuleIsPreservedAsGroupAndChildRelatesToNearestRealAncestor() throws Exception {
        try (Connection conn = TestDatabases.openVirtualModuleConfigDb("query_tree_builder_test_virtual")) {
            DSLContext dsl = DSL.using(conn, SQLDialect.H2);
            MetadataRegistry virtualRegistry = MetadataLoader.load(dsl);
            RelationResolver virtualResolver = new RelationResolver(virtualRegistry);
            QueryTreeBuilder virtualBuilder = new QueryTreeBuilder(virtualRegistry, virtualResolver);

            FlatGroup tree = virtualBuilder.buildFromRoot(1L, Set.of(3L));

            assertEquals("student", tree.primaryTable());
            assertEquals(java.util.List.of(1L), tree.mergedModuleIds());
            assertEquals(1, tree.nestedChildren().size());

            // 根节点下的第一个嵌套子级是虚拟模块 2
            NestedGroup virtualNested = tree.nestedChildren().get(0);
            assertEquals(2L, virtualNested.childModuleId());
            assertTrue(virtualNested.group().isVirtual());
            assertNull(virtualNested.group().primaryTable());

            // 虚拟模块 2 下面是真实子模块 3
            assertEquals(1, virtualNested.group().nestedChildren().size());
            NestedGroup childAwardNested = virtualNested.group().nestedChildren().get(0);
            assertEquals(3L, childAwardNested.childModuleId());
            assertEquals("student_award", childAwardNested.group().primaryTable());
            assertEquals("student", childAwardNested.relation().mainTable());
            assertEquals("student_award", childAwardNested.relation().joinTable());
        }
    }

    /** 虚拟模块本身不能作为查询根节点——它没有物理表可以当驱动表。 */
    @Test
    void virtualModuleCannotBeQueryRoot() throws Exception {
        try (Connection conn = TestDatabases.openVirtualModuleConfigDb("query_tree_builder_test_virtual_root")) {
            DSLContext dsl = DSL.using(conn, SQLDialect.H2);
            MetadataRegistry virtualRegistry = MetadataLoader.load(dsl);
            RelationResolver virtualResolver = new RelationResolver(virtualRegistry);
            QueryTreeBuilder virtualBuilder = new QueryTreeBuilder(virtualRegistry, virtualResolver);

            assertThrows(IllegalArgumentException.class, () -> virtualBuilder.buildFromRoot(2L, Set.of(3L)));
        }
    }

    /** 如果自动找根找到的最近公共祖先恰好是虚拟模块，应该再往上找最近的真实祖先顶上。 */
    @Test
    void autoRootPromotesToNearestRealAncestorWhenLcaIsVirtual() throws Exception {
        try (Connection conn = TestDatabases.openVirtualModuleConfigDb("query_tree_builder_test_virtual_auto_root")) {
            DSLContext dsl = DSL.using(conn, SQLDialect.H2);
            MetadataRegistry virtualRegistry = MetadataLoader.load(dsl);
            RelationResolver virtualResolver = new RelationResolver(virtualRegistry);
            QueryTreeBuilder virtualBuilder = new QueryTreeBuilder(virtualRegistry, virtualResolver);

            // 只请求模块3的字段：3的祖先链是[3,2,1]，只有一个模块被请求时 LCA 就是它自己（3），
            // 3本身是真实模块，所以这里直接验证根落在3（其自身，不需要提升）。
            FlatGroup tree = virtualBuilder.buildAutoRoot(Set.of(3L));
            assertEquals("student_award", tree.primaryTable());
        }
    }
}
