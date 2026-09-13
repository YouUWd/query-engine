package com.example.schoolquery.permission;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前调用方的行级权限上下文：各个 scope key 对应"允许的值集合"，用于 表+字段 IN 过滤。
 *
 * 字段级权限（哪些列能选）不是这个引擎的职责——调用方在组装 fieldIds 之前就应该
 * 已经按权限过滤过了，传进来的 fieldIds 本身就是"这个人能看的字段"。
 *
 * 传 null 给 FlatGroupSqlBuilder/FieldDrivenQueryService 表示完全不做行级过滤
 * （比如系统内部调用、管理端），不是"传一个空 context"。
 */
public final class PermissionContext {

    private final Map<String, Collection<?>> scopeValues;

    private PermissionContext(Map<String, Collection<?>> scopeValues) {
        this.scopeValues = scopeValues;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 取某个 contextKey 对应的允许值集合，供 IN 过滤使用。 */
    public Collection<?> scopeValues(String contextKey) {
        Collection<?> v = scopeValues.get(contextKey);
        if (v == null) {
            throw new IllegalStateException(
                    "权限上下文里没有提供 contextKey=" + contextKey + " 对应的值（数据权限规则需要它）");
        }
        return v;
    }

    public static final class Builder {
        private final Map<String, Collection<?>> scopeValues = new LinkedHashMap<>();

        /** 只允许一个值时用这个——比如"只能看自己"：contextKey -> List.of(自己的 id)。 */
        public Builder scopeSelf(String contextKey, Object value) {
            scopeValues.put(contextKey, List.of(value));
            return this;
        }

        /** 允许一批值时用这个——比如"只能看自己带的班级"：contextKey -> 一批允许的 id。 */
        public Builder scopeList(String contextKey, Collection<?> values) {
            scopeValues.put(contextKey, values);
            return this;
        }

        public PermissionContext build() {
            return new PermissionContext(Map.copyOf(scopeValues));
        }
    }
}
