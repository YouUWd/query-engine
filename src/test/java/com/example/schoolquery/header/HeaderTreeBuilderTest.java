package com.example.schoolquery.header;

import com.example.schoolquery.TestDatabases;
import com.example.schoolquery.metadata.MetadataLoader;
import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.query.HeaderNode;
import com.example.schoolquery.relation.RelationResolver;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeaderTreeBuilderTest {

    /**
     * 虚拟模块在数据树里会被跳过，但在表头树里应该保留成一个只有 label、没有对应
     * 字段的分组节点——它存在的意义就是给前端提供一层导航分组。用的是专门的
     * 虚拟模块配置库（H2），不是手造的 Java 对象。
     */
    @Test
    void virtualModuleKeepsItsOwnGroupingNodeInHeader() throws Exception {
        try (Connection conn = TestDatabases.openVirtualModuleConfigDb("header_tree_builder_test_virtual")) {
            DSLContext dsl = DSL.using(conn, SQLDialect.H2);
            MetadataRegistry registry = MetadataLoader.load(dsl);
            RelationResolver resolver = new RelationResolver(registry);
            HeaderTreeBuilder headerBuilder = new HeaderTreeBuilder(registry, resolver);

            Map<Long, List<SysModuleField>> requestedByModule = Map.of(
                    1L, List.of(registry.field(10L)),
                    3L, List.of(registry.field(11L))
            );

            HeaderNode root = headerBuilder.build(1L, requestedByModule);

            assertEquals(1L, root.moduleId());
            assertEquals(2, root.children().size());

            HeaderNode nameLeaf = root.children().get(0);
            assertTrue(nameLeaf.isLeaf());
            assertEquals(10L, nameLeaf.fieldId());
            assertEquals("student.name", nameLeaf.dataIndex());

            HeaderNode virtualGroup = root.children().get(1);
            assertFalse(virtualGroup.isLeaf());
            assertEquals(2L, virtualGroup.moduleId());
            assertEquals("荣誉相关", virtualGroup.label());
            assertEquals(1, virtualGroup.children().size());

            HeaderNode childModule = virtualGroup.children().get(0);
            assertEquals(3L, childModule.moduleId());
            assertEquals(1, childModule.children().size());
            assertEquals(11L, childModule.children().get(0).fieldId());
        }
    }
}
