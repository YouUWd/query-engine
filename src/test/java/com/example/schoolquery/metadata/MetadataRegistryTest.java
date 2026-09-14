package com.example.schoolquery.metadata;

import com.example.schoolquery.TestDatabases;
import com.example.schoolquery.metadata.SysModule;
import com.example.schoolquery.metadata.SysModuleField;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 从 H2 配置库加载 MetadataRegistry（不再读 CSV），验证索引/查询逻辑。 */
class MetadataRegistryTest {

    private static Connection configConnection;
    private static MetadataRegistry registry;

    @BeforeAll
    static void setUp() throws Exception {
        configConnection = TestDatabases.openConfigDb("metadata_registry_test_config");
        DSLContext dsl = DSL.using(configConnection, SQLDialect.H2);
        registry = MetadataLoader.load(dsl);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (configConnection != null) configConnection.close();
    }

    @Test
    void loadsAllEightModules() {
        assertEquals(8, registry.allModules().size());
    }

    @Test
    void moduleTreeStructureMatchesTheSchoolDomain() {
        // 101(学生列表) 的子模块应该是 103/104/105
        List<Long> childIds = registry.children(101L).stream().map(SysModule::id).sorted().toList();
        assertEquals(List.of(103L, 104L, 105L), childIds);

        // 106 挂在 104(荣誉) 下面，107 挂在 102(课程) 下面，108 挂在 105(选课) 下面
        assertEquals(104L, registry.module(106L).parentId());
        assertEquals(102L, registry.module(107L).parentId());
        assertEquals(105L, registry.module(108L).parentId());
    }

    @Test
    void rootModulesHaveNoParent() {
        assertTrue(registry.module(101L).isRoot());
        assertTrue(registry.module(102L).isRoot());
        assertFalse(registry.module(103L).isRoot());
    }

    @Test
    void noModuleInRealMetadataIsVirtual() {
        // 真实的 8 个模块目前都对应物理表；虚拟模块的行为单独用 openVirtualModuleConfigDb() 测
        assertTrue(registry.allModules().stream().noneMatch(SysModule::isVirtual));
    }

    @Test
    void ancestorChainWalksUpToRoot() {
        // 108(选课成绩分项) -> 105(选课) -> 101(学生列表)
        assertEquals(List.of(108L, 105L, 101L), registry.ancestorChain(108L));
        assertEquals(List.of(101L), registry.ancestorChain(101L)); // 根模块自身
    }

    @Test
    void fieldsGroupedByTableForModule103ContainsThreeTables() {
        // 103(学生核心基本档案) = student + clazz(N:1) + student_profile(1:1)
        Map<String, List<SysModuleField>> grouped = registry.fieldsGroupedByTable(103L);
        assertEquals(3, grouped.size());
        assertTrue(grouped.containsKey("student"));
        assertTrue(grouped.containsKey("clazz"));
        assertTrue(grouped.containsKey("student_profile"));
    }

    @Test
    void fieldLookupByIdReturnsCorrectModuleAndTable() {
        // sys_module_field id=39 = student_award.award_name (module 104)
        SysModuleField f = registry.field(39L);
        assertEquals(104L, f.moduleId());
        assertEquals("student_award", f.tableName());
        assertEquals("award_name", f.columnName());
    }

    @Test
    void unknownModuleIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> registry.module(9999L));
    }

    @Test
    void unknownFieldIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> registry.field(9999L));
    }

    /** 虚拟模块场景走的是另一个独立的 H2 配置库，不和上面 8 个真实模块的库混在一起。 */
    @Test
    void virtualModuleConfigLoadsWithNullPrimaryTable() throws Exception {
        try (Connection virtualConn = TestDatabases.openVirtualModuleConfigDb("metadata_registry_test_virtual")) {
            DSLContext virtualDsl = DSL.using(virtualConn, SQLDialect.H2);
            MetadataRegistry virtualRegistry = MetadataLoader.load(virtualDsl);

            assertTrue(virtualRegistry.module(2L).isVirtual());
            assertNull(virtualRegistry.module(2L).primaryTable());
            assertFalse(virtualRegistry.module(1L).isVirtual());
            assertEquals(1L, virtualRegistry.nearestRealAncestor(2L).id()); // 虚拟模块自己往上找到真实祖先
            assertEquals(3L, virtualRegistry.nearestRealAncestor(3L).id()); // 3自己就是真实的
        }
    }
}
