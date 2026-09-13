package com.example.schoolquery.permission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionRegistryTest {

    @Test
    void tableWithNoRuleHasNoScopeConfigured() {
        PermissionRegistry registry = new PermissionRegistry(List.of());
        assertTrue(registry.scopeRuleFor("student").isEmpty());
    }

    @Test
    void scopeRuleForReturnsConfiguredRule() {
        SysDataScopeRule rule = new SysDataScopeRule("student", "id", "currentUserId");
        PermissionRegistry registry = new PermissionRegistry(List.of(rule));

        assertTrue(registry.scopeRuleFor("student").isPresent());
        assertEquals("id", registry.scopeRuleFor("student").get().scopeColumn());
        assertEquals("currentUserId", registry.scopeRuleFor("student").get().contextKey());
        assertTrue(registry.scopeRuleFor("clazz").isEmpty());
    }

    @Test
    void duplicateScopeRuleForSameTableRejected() {
        List<SysDataScopeRule> rules = List.of(
                new SysDataScopeRule("student", "id", "currentUserId"),
                new SysDataScopeRule("student", "clazz_id", "allowedClazzIds")
        );
        assertThrows(IllegalArgumentException.class, () -> new PermissionRegistry(rules));
    }

    @Test
    void missingScopeValueInContextThrowsAClearError() {
        PermissionContext ctx = PermissionContext.builder().build(); // 没有提供 currentUserId
        assertThrows(IllegalStateException.class, () -> ctx.scopeValues("currentUserId"));
    }

    @Test
    void scopeSelfWrapsASingleValueIntoASingletonCollection() {
        PermissionContext ctx = PermissionContext.builder().scopeSelf("currentUserId", 1L).build();
        assertEquals(List.of(1L), ctx.scopeValues("currentUserId"));
    }

    @Test
    void scopeListKeepsTheGivenCollectionAsIs() {
        PermissionContext ctx = PermissionContext.builder()
                .scopeList("allowedClazzIds", List.of(1L, 2L, 3L))
                .build();
        assertEquals(List.of(1L, 2L, 3L), ctx.scopeValues("allowedClazzIds"));
    }
}
