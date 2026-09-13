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
        FlatGroup tree = builder.buildFromRoot(101L, Set.of(103L));
        assertEquals("student", tree.primaryTable());
        assertEquals(Set.of(101L, 103L), Set.copyOf(tree.mergedModuleIds()));
        assertTrue(tree.nestedChildren().isEmpty());
    }

    @Test
    void oneToManyChildBecomesNestedGroup() {
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
        FlatGroup tree = builder.buildFromRoot(101L, Set.of(105L, 104L));
        assertEquals(2, tree.nestedChildren().size());
        Set<String> nestedTables = Set.of(
                tree.nestedChildren().get(0).group().primaryTable(),
                tree.nestedChildren().get(1).group().primaryTable());
        assertEquals(Set.of("student_course", "student_award"), nestedTables);
    }

    @Test
    void autoRootPicksLowestCommonAncestorWhenBothAreDescendants() {
        FlatGroup tree = builder.buildAutoRoot(Set.of(104L, 106L));
        assertEquals("student_award", tree.primaryTable());
        assertEquals(1, tree.nestedChildren().size());
        assertEquals("student_award_detail", tree.nestedChildren().get(0).group().primaryTable());
    }

    @Test
    void autoRootWalksUpHigherWhenModulesAreOnDifferentBranches() {
        FlatGroup tree = builder.buildAutoRoot(Set.of(101L, 104L));
        assertEquals("student", tree.primaryTable());
        assertEquals(1, tree.nestedChildren().size());
        assertEquals("student_award", tree.nestedChildren().get(0).group().primaryTable());
    }

    @Test
    void buildFromRootRejectsModuleOutsideTheGivenSubtree() {
        assertThrows(IllegalArgumentException.class, () -> builder.buildFromRoot(101L, Set.of(102L)));
    }

    @Test
    void buildAutoRootRejectsEmptyFieldSet() {
        assertThrows(IllegalArgumentException.class, () -> builder.buildAutoRoot(Set.of()));
    }

    @Test
    void resolvesInternalTableJoinsFromFieldOwningModuleContext() {
        FlatGroup tree = builder.buildFromRoot(103L, Set.of(103L));
        Map<Long, java.util.List<com.example.schoolquery.model.SysModuleField>> requested =
                Map.of(103L, registry.fieldsGroupedByTable(103L).values().stream()
                        .flatMap(java.util.Collection::stream).collect(Collectors.toList()));

        FlatGroup resolved = builder.resolveTableJoins(tree, requested);

        assertEquals(2, resolved.tableJoins().size());
        assertTrue(resolved.tableJoins().stream().allMatch(j ->
                j.ownerModuleId() == 103L && j.resolvedModulePath().equals(java.util.List.of(103L))));
        assertTrue(resolved.tableJoins().stream().anyMatch(j ->
                j.primaryTable().equals("student")
                        && j.otherTable().equals("clazz")
                        && j.primaryColumn().equals("clazz_id")
                        && j.otherColumn().equals("id")));
        assertTrue(resolved.tableJoins().stream().anyMatch(j ->
                j.primaryTable().equals("student")
                        && j.otherTable().equals("student_profile")
                        && j.primaryColumn().equals("id")
                        && j.otherColumn().equals("student_id")));
    }

    @Test
    void relationResolverRequiresModuleContextForInternalJoin() {
        var moduleRelation = resolver.relationOfModule(103L, "clazz");
        assertEquals("student", moduleRelation.mainTable());
        assertEquals("clazz", moduleRelation.joinTable());
        assertEquals("clazz_id", moduleRelation.mainField());
        assertEquals("id", moduleRelation.joinField());
    }

    @Test
    void internalJoinContextRejectsVirtualOwnerModule() throws Exception {
        try (Connection conn = TestDatabases.openVirtualModuleConfigDb("query_tree_builder_test_virtual_relation_owner")) {
            DSLContext dsl = DSL.using(conn, SQLDialect.H2);
            MetadataRegistry virtualRegistry = MetadataLoader.load(dsl);
            RelationResolver virtualResolver = new RelationResolver(virtualRegistry);
            assertThrows(RuntimeException.class, () -> virtualResolver.relationOfModule(2L, "student_award"));
        }
    }

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

            NestedGroup virtualNested = tree.nestedChildren().get(0);
            assertEquals(2L, virtualNested.childModuleId());
            assertTrue(virtualNested.group().isVirtual());
            assertNull(virtualNested.group().primaryTable());

            assertEquals(1, virtualNested.group().nestedChildren().size());
            NestedGroup childAwardNested = virtualNested.group().nestedChildren().get(0);
            assertEquals(3L, childAwardNested.childModuleId());
            assertEquals("student_award", childAwardNested.group().primaryTable());
            assertEquals("student", childAwardNested.relation().mainTable());
            assertEquals("student_award", childAwardNested.relation().joinTable());
        }
    }

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

    @Test
    void autoRootPromotesToNearestRealAncestorWhenLcaIsVirtual() throws Exception {
        try (Connection conn = TestDatabases.openVirtualModuleConfigDb("query_tree_builder_test_virtual_auto_root")) {
            DSLContext dsl = DSL.using(conn, SQLDialect.H2);
            MetadataRegistry virtualRegistry = MetadataLoader.load(dsl);
            RelationResolver virtualResolver = new RelationResolver(virtualRegistry);
            QueryTreeBuilder virtualBuilder = new QueryTreeBuilder(virtualRegistry, virtualResolver);
            FlatGroup tree = virtualBuilder.buildAutoRoot(Set.of(3L));
            assertEquals("student_award", tree.primaryTable());
        }
    }
}
