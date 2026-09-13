package com.example.schoolquery.service;

import com.example.schoolquery.TestDatabases;
import com.example.schoolquery.metadata.MetadataLoader;
import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.query.*;
import com.example.schoolquery.relation.RelationResolver;
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
 * 端到端复现给定的请求/响应形状：
 *   请求 {moduleId, pageNo, pageSize, fields, filters, sorts, withHeader}
 *   响应 {pageNo, pageSize, total, records（按 table/moduleId 分层嵌套）, header}
 *
 * 配置库（sys_module 等）和业务库（school 数据）各起一个 H2，都真的跑 SQL——
 * 不含虚拟模块的场景（虚拟模块的端到端查询见 VirtualModuleQueryH2IT）。
 */
class PagedFieldDrivenQueryServiceH2IT {

    private static Connection schoolConnection;
    private static Connection configConnection;
    private static DSLContext dsl;
    private static PagedFieldDrivenQueryService service;

    @BeforeAll
    static void setUp() throws Exception {
        schoolConnection = TestDatabases.openSchoolDb("paged_it_school");
        configConnection = TestDatabases.openConfigDb("paged_it_config");

        dsl = DSL.using(schoolConnection, SQLDialect.H2);

        MetadataRegistry registry = MetadataLoader.load(DSL.using(configConnection, SQLDialect.H2));
        RelationResolver resolver = new RelationResolver(registry);
        resolver.validateAllModules();
        service = new PagedFieldDrivenQueryService(registry, resolver);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (schoolConnection != null) schoolConnection.close();
        if (configConnection != null) configConnection.close();
    }

    /**
     * 对应给定例子的核心场景：根字段 + 同表合并字段 + 嵌套字段一起请求，
     * 一个过滤在根节点自己的表上（姓名模糊搜索），一个过滤在嵌套子级的表上（课程名精确匹配）——
     * 后者应该同时做到"只返回有匹配课程的学生"和"嵌套数组里只留匹配的课程"两件事。
     */
    @Test
    void execute_appliesRootAndNestedFiltersAndRendersNestedShape() {
        QueryRequest request = new QueryRequest(
                101L, 1, 10,
                List.of(3L, 49L, 32L, 33L), // 101:name, 103:clazz_name(合并), 105:course_name/score(嵌套)
                List.of(
                        new FilterCriterion(3L, FilterOperator.LIKE, "张"),   // 根节点字段：姓名模糊
                        new FilterCriterion(32L, FilterOperator.EQ, "高等数学") // 嵌套字段：课程名精确
                ),
                List.of(),
                true
        );

        PagedResult result = service.execute(dsl, request);
        System.out.println(result);
        assertEquals(1, result.pageNo());
        assertEquals(10, result.pageSize());
        assertEquals(1, result.total()); // 全校姓名带"张"的只有张三，且只有他选了高等数学
        assertEquals(1, result.records().size());

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) result.records().get(0).get("101");
        assertNotNull(root, "记录应该按根 moduleId=101 包一层");

        @SuppressWarnings("unchecked")
        Map<String, Object> studentBucket = (Map<String, Object>) root.get("student");
        assertEquals("张三", studentBucket.get("name"));

        @SuppressWarnings("unchecked")
        Map<String, Object> clazzBucket = (Map<String, Object>) root.get("clazz");
        assertEquals("计科2601班", clazzBucket.get("clazz_name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> courses = (List<Map<String, Object>>) root.get("105");
        assertEquals(1, courses.size(), "嵌套数组应该只剩匹配课程名的那一条，不是学生1的全部3门课");

        @SuppressWarnings("unchecked")
        Map<String, Object> courseBucket = (Map<String, Object>) courses.get(0).get("student_course");
        assertEquals("高等数学", courseBucket.get("course_name"));

        // ---- header ----
        assertNotNull(result.header());
        assertEquals(101L, result.header().moduleId());
        assertEquals("学生列表", result.header().label());

        List<HeaderNode> children = result.header().children();
        assertTrue(children.stream().anyMatch(c -> c.isLeaf() && c.fieldId() == 3L && "student.name".equals(c.dataIndex())));
        assertTrue(children.stream().anyMatch(c -> c.isLeaf() && c.fieldId() == 49L && "clazz.clazz_name".equals(c.dataIndex())));

        HeaderNode courseGroup = children.stream()
                .filter(c -> !c.isLeaf() && c.moduleId() == 105L)
                .findFirst().orElseThrow();
        assertEquals("选课与成绩管理", courseGroup.label());
        assertEquals(2, courseGroup.children().size()); // course_name + score
    }

    /** 分页 + 排序：只在根节点自己的字段上排序，用学号（纯 ASCII）避免中文排序结果不确定。 */
    @Test
    void execute_sortsAndPaginatesOnRootLevelField() {
        QueryRequest page1 = new QueryRequest(101L, 1, 2, List.of(2L), List.of(),
                List.of(new SortCriterion(2L, SortDirection.DESC)), false);
        QueryRequest page2 = new QueryRequest(101L, 2, 2, List.of(2L), List.of(),
                List.of(new SortCriterion(2L, SortDirection.DESC)), false);

        PagedResult firstPage = service.execute(dsl, page1);
        PagedResult secondPage = service.execute(dsl, page2);

        assertEquals(5, firstPage.total());
        assertEquals(2, firstPage.records().size());
        assertEquals(2, secondPage.records().size());
        assertNull(firstPage.header()); // withHeader=false

        assertEquals("S2026005", studentNo(firstPage, 0));
        assertEquals("S2026004", studentNo(firstPage, 1));
        assertEquals("S2026003", studentNo(secondPage, 0));
        assertEquals("S2026002", studentNo(secondPage, 1));
    }

    /** 排序字段落在嵌套子级上时应该直接报错，而不是产出一个语义不明的结果。 */
    @Test
    void execute_rejectsSortOnNestedField() {
        QueryRequest request = new QueryRequest(101L, 1, 10, List.of(3L, 32L), List.of(),
                List.of(new SortCriterion(32L, SortDirection.ASC)), false); // 32 是嵌套的 105 的字段
        assertThrows(IllegalArgumentException.class, () -> service.execute(dsl, request));
    }

    @SuppressWarnings("unchecked")
    private static String studentNo(PagedResult result, int index) {
        Map<String, Object> root = (Map<String, Object>) result.records().get(index).get("101");
        Map<String, Object> studentBucket = (Map<String, Object>) root.get("student");
        return (String) studentBucket.get("student_no");
    }

    /**
     * 【方案 B 深度验证】多层 1:N 嵌套场景（101 学生 -> 105 选课 -> 108 成绩分项）。
     * 验证批量 IN 查询与内存组装在深层嵌套下的一致性与准确性。
     */
    @Test
    void execute_twoLevelNesting_batchLoadedAndAssembledCorrectly() {
        QueryRequest request = new QueryRequest(
                101L, 1, 10,
                List.of(
                        3L,   // 101: student.name
                        49L,  // 103: clazz.clazz_name
                        32L,  // 105: student_course.course_name
                        33L,  // 105: student_course.score
                        59L,  // 108: student_course_score_item.item_name
                        61L   // 108: student_course_score_item.score
                ),
                List.of(new FilterCriterion(3L, FilterOperator.EQ, "张三")),
                List.of(),
                false
        );

        PagedResult result = service.execute(dsl, request);

        assertEquals(1, result.total());
        assertEquals(1, result.records().size());

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) result.records().get(0).get("101");
        @SuppressWarnings("unchecked")
        Map<String, Object> studentBucket = (Map<String, Object>) root.get("student");
        assertEquals("张三", studentBucket.get("name"));

        @SuppressWarnings("unchecked")
        Map<String, Object> clazzBucket = (Map<String, Object>) root.get("clazz");
        assertEquals("计科2601班", clazzBucket.get("clazz_name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> courses = (List<Map<String, Object>>) root.get("105");
        assertEquals(3, courses.size(), "张三应该有 3 门选课记录");

        int totalScoreItems = 0;
        for (Map<String, Object> course : courses) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) course.get("108");
            assertNotNull(items, "每门选课应该挂载 108 成绩分项列表");
            totalScoreItems += items.size();
        }
        assertEquals(8, totalScoreItems, "张三的 3 门课程在 108 分项表下共计 8 条分项评分");
    }
}
