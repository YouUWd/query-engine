package com.example.schoolquery.service;
import com.example.schoolquery.query.resolver.*;

import com.example.schoolquery.TestDatabases;
import com.example.schoolquery.metadata.MetadataLoader;
import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.permission.PermissionContext;
import com.example.schoolquery.permission.PermissionRegistry;
import com.example.schoolquery.permission.SysDataScopeRule;
import com.example.schoolquery.query.resolver.RelationResolver;
import com.example.schoolquery.query.renderer.FlatGroupSqlBuilder;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试：两个 H2 库都是真实跑起来的——一个是配置库（config库，
 * sys_module/sys_module_field/sys_table_relation，种子数据来自 config-data-h2.sql，
 * 那份 SQL 是从真实的 sys_*.csv 导出程序生成的），一个是业务库（school库，
 * 种子数据来自 school.sql 里的真实样例行）。测试本身不读 CSV，
 * 元数据是通过 {@link MetadataLoader} 对着配置库的 H2 连接查出来的，
 * 跟生产环境的加载方式一致。
 *
 * 字段用 {@link FlatGroupSqlBuilder#fieldAlias}/{@link FlatGroupSqlBuilder#nestedAlias}
 * 取值，而不是裸列名——这是 FlatGroupSqlBuilder 现在的别名约定（按 fieldId/moduleId
 * 生成，避免不同表同名列冲突）。想要更自然的"按表分组"的结果，用
 * {@link com.example.schoolquery.render.RecordRenderer}，见 PagedFieldDrivenQueryServiceH2IT。
 */
class FieldDrivenQueryServiceH2IT {

    private static Connection schoolConnection;
    private static Connection configConnection;
    private static DSLContext dsl;
    private static MetadataRegistry metadataRegistry;
    private static RelationResolver resolver;
    private static FieldDrivenQueryService service;

    @BeforeAll
    static void setUp() throws Exception {
        schoolConnection = TestDatabases.openSchoolDb("field_driven_it_school");
        configConnection = TestDatabases.openConfigDb("field_driven_it_config");

        dsl = DSL.using(schoolConnection, SQLDialect.H2);
        metadataRegistry = MetadataLoader.load(DSL.using(configConnection, SQLDialect.H2));

        resolver = new RelationResolver(metadataRegistry);
        resolver.validateAllModules(); // 起服务前先校验一遍元数据，等价于应用启动时的自检
        service = new FieldDrivenQueryService(metadataRegistry, resolver);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (schoolConnection != null) schoolConnection.close();
        if (configConnection != null) configConnection.close();
    }

    /**
     * 诉求1：moduleId=101(学生)，字段跨了 103(同表合并)、105(1:N)、108(105 之下的 1:N)。
     * 期望：一行学生记录，直接带上 103 的班级/身份证字段，外加一个嵌套的选课数组，
     * 每条选课再嵌套自己的成绩分项数组。
     */
    @Test
    void queryByModuleAndFields_mergesSameEntityAndNestsTwoLevels() {
        List<Long> fieldIds = List.of(
                3L,   // 101: student.name
                49L,  // 103: clazz.clazz_name  (N:1，合并进同一行)
                53L,  // 103: student_profile.id_card (1:1，合并进同一行)
                32L,  // 105: student_course.course_name
                33L,  // 105: student_course.score
                59L,  // 108: student_course_score_item.item_name
                61L   // 108: student_course_score_item.score
        );

        Result<Record> result = service.queryByModuleAndFields(
                dsl, 101L, fieldIds, DSL.field(DSL.name("student", "id")).eq(1L));
        System.out.println(result);
        assertEquals(1, result.size());
        Record row = result.get(0);

        assertEquals("张三", row.get(alias(3L)));
        assertEquals("计科2601班", row.get(alias(49L)));
        assertEquals("110101200501011234", row.get(alias(53L)));

        Result<Record> courses = nested(row, 105L);
        assertEquals(3, courses.size()); // 学生1选了3门课

        int totalScoreItems = 0;
        for (Record course : courses) {
            Result<Record> items = nested(course, 108L);
            totalScoreItems += items.size();
        }
        assertEquals(8, totalScoreItems); // 3(课程101) + 3(课程102) + 2(课程103)
    }

    /**
     * 诉求2：不指定 moduleId，只给 104(荣誉名称) + 106(佐证材料名称) 两个字段。
     * 自动推断的根应该是 104 本身（106 是它的直接子模块）。
     */
    @Test
    void queryByFieldsOnly_autoRootsAtStudentAward() {
        List<Long> fieldIds = List.of(
                39L,  // 104: student_award.award_name
                76L   // 106: student_award_detail.evidence_name
        );

        Result<Record> result = service.queryByFieldsOnly(dsl, fieldIds, DSL.trueCondition());

        assertEquals(4, result.size()); // h2 测试数据里 4 条荣誉记录

        Record nationalScholarship = result.stream()
                .filter(r -> "国家奖学金".equals(r.get(alias(39L))))
                .findFirst()
                .orElseThrow();
        Result<Record> evidences = nested(nationalScholarship, 106L);
        assertEquals(2, evidences.size());
        assertTrue(evidences.stream().anyMatch(e -> "教育部国家奖学金荣誉证书".equals(e.get(alias(76L)))));

        Record noEvidenceAward = result.stream()
                .filter(r -> "校级三好学生荣誉".equals(r.get(alias(39L))))
                .findFirst()
                .orElseThrow();
        assertTrue(nested(noEvidenceAward, 106L).isEmpty());
    }

    /** 诉求1的校验分支：传一个不属于 moduleId 子树的字段，应该直接报错而不是静默忽略。 */
    @Test
    void queryByModuleAndFields_rejectsFieldOutsideTheRootsSubtree() {
        List<Long> fieldIds = List.of(
                3L,   // 101: student.name  (在 101 子树内)
                21L   // 102: teacher.teacher_name (不在 101 子树内)
        );
        assertThrows(IllegalArgumentException.class,
                () -> service.queryByModuleAndFields(dsl, 101L, fieldIds, DSL.trueCondition()));
    }

    // ============ 行级数据权限（表+字段 IN 过滤） ============
    // 字段级权限不在这个引擎里测——按约定，调用方传进来的 fieldIds 本身就已经是
    // 权限过滤后的结果，这里只验证行级的 "column IN (允许值)"。

    /** 只给一个值（List.of(自己的id)）——效果就是"只能看自己"。 */
    @Test
    void dataScope_restrictsToOwnRowOnlyViaSingleValueIn() {
        PermissionRegistry permissions = new PermissionRegistry(
                List.of(new SysDataScopeRule("student", "id", "currentUserId")));
        FieldDrivenQueryService restricted = new FieldDrivenQueryService(metadataRegistry, resolver, permissions);

        PermissionContext asStudentOne = PermissionContext.builder().scopeSelf("currentUserId", 1L).build();

        // 不带任何过滤条件——h2 测试数据里有 5 个学生，但行级权限应该把结果收窄成只有学生1自己
        Result<Record> result = restricted.queryByModuleAndFields(
                dsl, 101L, List.of(3L), DSL.trueCondition(), asStudentOne);

        assertEquals(1, result.size());
        assertEquals("张三", result.get(0).get(alias(3L)));
    }

    /** 给一批值——效果就是"只能看这些班级"。 */
    @Test
    void dataScope_restrictsToAllowedClazzIdsViaMultiValueIn() {
        PermissionRegistry permissions = new PermissionRegistry(
                List.of(new SysDataScopeRule("student", "clazz_id", "allowedClazzIds")));
        FieldDrivenQueryService restricted = new FieldDrivenQueryService(metadataRegistry, resolver, permissions);

        // 只放开班级1、2（h2 测试数据里学生1/2在班级1，学生3/4在班级2，学生5在班级3）
        PermissionContext teacherScope = PermissionContext.builder()
                .scopeList("allowedClazzIds", List.of(1L, 2L))
                .build();

        Result<Record> result = restricted.queryByModuleAndFields(
                dsl, 101L, List.of(3L), DSL.trueCondition(), teacherScope);

        assertEquals(4, result.size()); // 学生1/2/3/4，学生5(班级3)被排除
        assertTrue(result.stream().noneMatch(r -> "孙七".equals(r.get(alias(3L)))));
    }

    /** 没有权限上下文（null）时，完全不受行级规则影响，和不启用权限功能时行为一致。 */
    @Test
    void dataScope_notAppliedWhenContextIsNull() {
        PermissionRegistry permissions = new PermissionRegistry(
                List.of(new SysDataScopeRule("student", "id", "currentUserId")));
        FieldDrivenQueryService restricted = new FieldDrivenQueryService(metadataRegistry, resolver, permissions);

        Result<Record> result = restricted.queryByModuleAndFields(
                dsl, 101L, List.of(3L), DSL.trueCondition(), null);

        assertEquals(5, result.size()); // 全部5个学生，行级规则没生效
    }

    /** 行级规则只作用于驱动表本身：101 的规则不影响其嵌套子级(105)的结果集大小。 */
    @Test
    void dataScope_onlyAppliesToTheGroupsOwnPrimaryTableNotJoinedTables() {
        PermissionRegistry permissions = new PermissionRegistry(
                List.of(new SysDataScopeRule("student", "id", "currentUserId")));
        FieldDrivenQueryService restricted = new FieldDrivenQueryService(metadataRegistry, resolver, permissions);

        PermissionContext asStudentOne = PermissionContext.builder().scopeSelf("currentUserId", 1L).build();

        Result<Record> result = restricted.queryByModuleAndFields(
                dsl, 101L, List.of(3L, 32L), DSL.trueCondition(), asStudentOne); // 101:name + 105:course_name

        assertEquals(1, result.size()); // 行级权限收窄到学生1
        assertEquals(3, nested(result.get(0), 105L).size()); // 学生1自己的3门课不受影响
    }

    private static String alias(long fieldId) {
        return FlatGroupSqlBuilder.fieldAlias(fieldId);
    }

    @SuppressWarnings("unchecked")
    private static Result<Record> nested(Record row, long childModuleId) {
        String alias = FlatGroupSqlBuilder.nestedAlias(childModuleId);
        Object raw = row.get(alias);
        assertNotNull(raw, "expected a nested multiset field named " + alias);
        return (Result<Record>) raw;
    }
}
