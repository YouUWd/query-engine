package com.example.schoolquery.relation;

import com.example.schoolquery.TestDatabases;
import com.example.schoolquery.metadata.MetadataLoader;
import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.metadata.MetadataValidationException;
import com.example.schoolquery.model.*;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class RelationResolverTest {

    private static Connection configConnection;
    private static MetadataRegistry registry;
    private static RelationResolver resolver;

    @BeforeAll
    static void setUp() throws Exception {
        configConnection = TestDatabases.openConfigDb("relation_resolver_test_config");
        DSLContext dsl = DSL.using(configConnection, SQLDialect.H2);
        registry = MetadataLoader.load(dsl);
        resolver = new RelationResolver(registry);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (configConnection != null) configConnection.close();
    }

    /**
     * 这就是之前用 Python 脚本手工跑的那次一致性校验，现在变成了一个会随代码一起跑的测试：
     * 真实的 sys_module / sys_module_field / sys_table_relation 数据必须满足新规则——
     * 模块内部只允许 1:1/N:1，不允许 1:N。
     */
    @Test
    void allModulesInRealMetadataObeyTheOneToOneOrManyToOneRule() {
        assertDoesNotThrow(resolver::validateAllModules);
    }

    @Test
    void parentChildBetween101And103IsSameEntity() {
        // 103(学生核心基本档案) 和 101(学生列表) 共享 primary_table = student
        ModuleRelationKind kind = resolver.resolveParentChild(registry.module(101L), registry.module(103L));
        assertEquals(ModuleRelationKind.SAME_ENTITY, kind);
    }

    @Test
    void parentChildBetween101And104IsChild() {
        // 104(荣誉) 相对 101(学生) 是 1:N
        ModuleRelationKind kind = resolver.resolveParentChild(registry.module(101L), registry.module(104L));
        assertEquals(ModuleRelationKind.CHILD, kind);
    }

    @Test
    void parentChildBetween104And106IsChild() {
        ModuleRelationKind kind = resolver.resolveParentChild(registry.module(104L), registry.module(106L));
        assertEquals(ModuleRelationKind.CHILD, kind);
    }

    @Test
    void relationOfLooksUpEitherDirection() {
        SysTableRelation r1 = resolver.relationOf("course", "teacher");
        SysTableRelation r2 = resolver.relationOf("teacher", "course");
        assertEquals(r1.id(), r2.id());
        assertEquals("teacher", r1.mainTable());
        assertEquals(RelationType.ONE_TO_MANY, r1.type());
    }

    @Test
    void relationOfThrowsWhenNoRelationConfigured() {
        assertThrows(MetadataValidationException.class, () -> resolver.relationOf("clazz", "course"));
    }

    /**
     * 反例：往一个空配置库里插一条违反新规则的数据（101 的字段里混进了一张真正 1:N 的表），
     * 验证 validateModule 真的会抓出来，而不是悄悄放过。用的是 H2，不是手造的 Java 对象。
     */
    @Test
    void validateModuleRejectsAOneToManyTableMixedIntoOneModule() throws Exception {
        try (Connection conn = TestDatabases.openEmptyConfigDb("relation_resolver_test_bad_module")) {
            DSLContext dsl = DSL.using(conn, SQLDialect.H2);
            dsl.execute("INSERT INTO sys_module (id, module_code, module_name, primary_table, parent_id) " +
                    "VALUES (1, 'MOD-A', '违规模块', 'student', 0)");
            dsl.execute("INSERT INTO sys_module_field (id, module_id, table_name, column_name, display_name, sort_order) " +
                    "VALUES (1, 1, 'student', 'id', '学生ID', 1)");
            // 违规：student_course 相对 student 是 1:N，却配置进了同一个模块
            dsl.execute("INSERT INTO sys_module_field (id, module_id, table_name, column_name, display_name, sort_order) " +
                    "VALUES (2, 1, 'student_course', 'score', '成绩', 2)");
            dsl.execute("INSERT INTO sys_table_relation (id, main_table, main_field, join_table, join_field, relation_type) " +
                    "VALUES (1, 'student', 'id', 'student_course', 'student_id', '1:N')");

            MetadataRegistry badRegistry = MetadataLoader.load(dsl);
            RelationResolver badResolver = new RelationResolver(badRegistry);

            assertThrows(MetadataValidationException.class,
                    () -> badResolver.validateModule(badRegistry.module(1)));
        }
    }

    /**
     * 反例：父子模块之间如果是 1:1（本该合并进同一模块），resolveParentChild 应该报错，
     * 而不是默默当成一种合法状态处理。
     */
    @Test
    void resolveParentChildRejectsOneToOneAcrossModules() throws Exception {
        try (Connection conn = TestDatabases.openEmptyConfigDb("relation_resolver_test_bad_parent_child")) {
            DSLContext dsl = DSL.using(conn, SQLDialect.H2);
            dsl.execute("INSERT INTO sys_module (id, module_code, module_name, primary_table, parent_id) " +
                    "VALUES (1, 'MOD-COURSE', '课程', 'course', 0)");
            dsl.execute("INSERT INTO sys_module (id, module_code, module_name, primary_table, parent_id) " +
                    "VALUES (2, 'MOD-SYLLABUS', '大纲（不该拆成子模块）', 'course_syllabus', 1)");
            dsl.execute("INSERT INTO sys_module_field (id, module_id, table_name, column_name, display_name, sort_order) " +
                    "VALUES (1, 1, 'course', 'id', '课程ID', 1)");
            dsl.execute("INSERT INTO sys_module_field (id, module_id, table_name, column_name, display_name, sort_order) " +
                    "VALUES (2, 2, 'course_syllabus', 'prerequisite', '先修要求', 1)");
            dsl.execute("INSERT INTO sys_table_relation (id, main_table, main_field, join_table, join_field, relation_type) " +
                    "VALUES (1, 'course', 'id', 'course_syllabus', 'course_id', '1:1')");

            MetadataRegistry badRegistry = MetadataLoader.load(dsl);
            RelationResolver badResolver = new RelationResolver(badRegistry);

            assertThrows(MetadataValidationException.class,
                    () -> badResolver.resolveParentChild(badRegistry.module(1), badRegistry.module(2)));
        }
    }

    /** 虚拟模块场景：resolveParentChild 不应该被拿来直接判断虚拟模块——它压根没有表可比较。 */
    @Test
    void virtualModuleHasNoPrimaryTableToCompare() throws Exception {
        try (Connection conn = TestDatabases.openVirtualModuleConfigDb("relation_resolver_test_virtual")) {
            DSLContext dsl = DSL.using(conn, SQLDialect.H2);
            MetadataRegistry virtualRegistry = MetadataLoader.load(dsl);
            RelationResolver virtualResolver = new RelationResolver(virtualRegistry);

            SysModule real = virtualRegistry.module(1L);
            SysModule virtual = virtualRegistry.module(2L);
            assertTrue(virtual.isVirtual());

            // 虚拟模块自己没有 primary_table，真要拿它去比较关系会直接报错——
            // 这也是为什么 QueryTreeBuilder 在遍历时要主动跳过虚拟模块，
            // 而不是把它当成普通模块传给 resolveParentChild。
            assertThrows(MetadataValidationException.class,
                    () -> virtualResolver.resolveParentChild(real, virtual));
        }
    }
}
