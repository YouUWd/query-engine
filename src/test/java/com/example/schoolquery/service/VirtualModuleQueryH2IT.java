package com.example.schoolquery.service;

import com.example.schoolquery.TestDatabases;
import com.example.schoolquery.metadata.MetadataLoader;
import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.query.model.HeaderNode;
import com.example.schoolquery.result.PagedResult;
import com.example.schoolquery.query.model.QueryRequest;
import com.example.schoolquery.query.resolver.RelationResolver;
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

/**
 * 虚拟模块的端到端测试：真的跑一次分页查询，而不只是验证 QueryTreeBuilder/HeaderTreeBuilder
 * 产出的树形状对不对。
 *
 * 配置库用的是专门的虚拟模块场景（1=学生/真实根，2=虚拟分组，3=荣誉/真实子模块，
 * 2 夹在 1、3 中间且没有 primary_table）；业务数据复用已有的 school H2 库
 * （student、student_award 两张表本来就在里面，带着真实样例数据），
 * 这样就能验证虚拟模块被跳过之后，3 依然能正确关联回 1 的 student 表，
 * 整条链路（建树 -> 生成 SQL -> 渲染结果 -> 生成表头）都是对的。
 */
class VirtualModuleQueryH2IT {

    private static Connection schoolConnection;
    private static Connection virtualConfigConnection;
    private static DSLContext dsl;
    private static PagedFieldDrivenQueryService service;

    @BeforeAll
    static void setUp() throws Exception {
        schoolConnection = TestDatabases.openSchoolDb("virtual_module_it_school");
        virtualConfigConnection = TestDatabases.openVirtualModuleConfigDb("virtual_module_it_config");

        dsl = DSL.using(schoolConnection, SQLDialect.H2);

        MetadataRegistry registry = MetadataLoader.load(DSL.using(virtualConfigConnection, SQLDialect.H2));
        RelationResolver resolver = new RelationResolver(registry);
        service = new PagedFieldDrivenQueryService(registry, resolver);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (schoolConnection != null) schoolConnection.close();
        if (virtualConfigConnection != null) virtualConfigConnection.close();
    }

    @Test
    void queryThroughVirtualModuleSkipsItInDataButKeepsItInHeader() {
        QueryRequest request = new QueryRequest(
                1L, 1, 10,
                List.of(10L, 11L), // 1: student.name, 3(挂在虚拟模块2下面): student_award.award_name
                List.of(), List.of(), true);

        PagedResult result = service.execute(dsl, request);

        assertEquals(5, result.total()); // school H2 库里 5 个学生，虚拟模块不影响行数

        // ---- 数据结构：形式 A：虚拟模块 2 作为一层 Map 对象包裹真实子模块 3 ----
        Map<String, Object> zhangSan = findByName(result, "张三");
        assertTrue(zhangSan.containsKey("2"), "虚拟模块应该在数据结构里作为 Map 对象包裹其子模块");
        @SuppressWarnings("unchecked")
        Map<String, Object> zhangSanVirtualGroup = (Map<String, Object>) zhangSan.get("2");
        assertNotNull(zhangSanVirtualGroup);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> zhangSanAwards = (List<Map<String, Object>>) zhangSanVirtualGroup.get("3");
        assertEquals(1, zhangSanAwards.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> awardBucket = (Map<String, Object>) zhangSanAwards.get(0).get("student_award");
        assertEquals("国家奖学金", awardBucket.get("award_name"));

        Map<String, Object> zhaoLiu = findByName(result, "赵六"); // 学生4，没有任何荣誉记录
        @SuppressWarnings("unchecked")
        Map<String, Object> zhaoLiuVirtualGroup = (Map<String, Object>) zhaoLiu.get("2");
        assertNotNull(zhaoLiuVirtualGroup);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> zhaoLiuAwards = (List<Map<String, Object>>) zhaoLiuVirtualGroup.get("3");
        assertTrue(zhaoLiuAwards.isEmpty());

        // ---- 表头：虚拟模块2应该保留成一个分组节点 ----
        HeaderNode root = result.header();
        assertEquals(1L, root.moduleId());
        assertTrue(root.children().stream().anyMatch(c -> c.isLeaf() && c.fieldId() == 10L));

        HeaderNode virtualGroup = root.children().stream()
                .filter(c -> !c.isLeaf() && c.moduleId() == 2L)
                .findFirst().orElseThrow();
        assertEquals("荣誉相关", virtualGroup.label());

        HeaderNode childModule = virtualGroup.children().get(0);
        assertEquals(3L, childModule.moduleId());
        assertEquals(11L, childModule.children().get(0).fieldId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findByName(PagedResult result, String name) {
        return result.records().stream()
                .map(r -> (Map<String, Object>) r.get("1"))
                .filter(root -> name.equals(((Map<String, Object>) root.get("student")).get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record found for name " + name));
    }
}
