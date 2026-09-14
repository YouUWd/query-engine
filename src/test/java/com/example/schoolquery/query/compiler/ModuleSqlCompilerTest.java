package com.example.schoolquery.query.compiler;
import com.example.schoolquery.query.model.*;
import com.example.schoolquery.query.model.*;


import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.metadata.SysModule;
import com.example.schoolquery.metadata.SysModuleField;
import com.example.schoolquery.mutation.compiler.MutationCompiler;
import com.example.schoolquery.mutation.model.MutationPlan;
import com.example.schoolquery.query.model.QueryPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleSqlCompilerTest {
    private MetadataRegistry registry() {
        return new MetadataRegistry(
                List.of(new SysModule(1, "student", "学生", "student", 0)),
                List.of(new SysModuleField(101, 1, "student", "id", "ID", 1),
                        new SysModuleField(102, 1, "student", "name", "姓名", 2),
                        new SysModuleField(103, 1, "student", "age", "年龄", 3)),
                List.of());
    }

    @Test
    void keepsNestedBooleanFilterTree() {
        QueryPlan plan = new ModuleQueryCompiler(registry()).compile(
                "select f101, f102 from student where f102 = 'Alice' or f103 >= 18 limit 20 offset 20");
        assertEquals(1L, plan.rootModuleId());
        assertEquals(2, plan.projections().size());
        assertInstanceOf(com.example.schoolquery.query.model.FilterExpressionPlan.Or.class, plan.filterExpression());
        assertEquals(2, plan.filters().size());
        assertEquals(2, plan.pagination().pageNo());
        assertEquals(20, plan.pagination().offset());
    }

    @Test
    void supportsModuleFunctionSourceAndNonPageAlignedOffset() {
        QueryPlan plan = new ModuleQueryCompiler(registry()).compile(
                "select f101 from module(1) where f103 >= 18 limit 10 offset 25");
        assertEquals(1L, plan.rootModuleId());
        assertEquals(10, plan.pagination().pageSize());
        assertEquals(25, plan.pagination().offset());
        assertEquals(3, plan.pagination().pageNo());
    }

    @Test
    void preservesProjectionAliases() {
        QueryPlan plan = new ModuleQueryCompiler(registry()).compile(
                "select f101 as studentId, f102 name from module(1)");
        assertEquals(List.of("studentId", "name"), plan.projectionAliases());
        assertEquals(List.of(101L, 102L), plan.projections().stream().map(r -> r.fieldId()).toList());
    }

    @Test
    void compilesScalarInsert() {
        MutationPlan plan = new MutationCompiler(registry()).compile(
                "insert into student (id, name, age) values (1, 'Alice', 18)");
        assertEquals(MutationPlan.Operation.INSERT, plan.operation());
        assertEquals(1L, plan.rootModuleId());
        assertEquals(3, plan.assignments().size());
        assertEquals("Alice", plan.assignments().get(1).value());
    }
}
