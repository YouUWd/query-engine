package com.example.schoolquery.query.renderer;

import com.example.schoolquery.query.model.FilterOperator;
import org.jooq.Condition;
import org.jooq.Field;

import java.util.List;

/**
 * 把一个已经解析到具体列的过滤条件（operator + value）转成 jOOQ Condition。
 *
 * field 统一是 {@link Field}{@code <Object>}（见 {@link com.example.schoolquery.sql.DynamicFields}
 * 那唯一一处 cast）——正因为是 Field&lt;Object&gt;，下面 eq/ne/gt/lt/like/in/between
 * 这些操作符方法可以直接调用，不需要再对着每个分支单独 cast 一次。
 *
 * dataType 可选：传了就先用 {@link FieldValueConverter} 把原始值（通常是 JSON
 * 反序列化出来的 String/Number/List）转成对应的 Java 类型再比较，避免数字/日期
 * 被当成字符串处理；不传（null）就按原始值直接传给 jOOQ，兼容不需要这层的调用方。
 */
public final class FilterConditionBuilder {

    private FilterConditionBuilder() {}

    public static Condition toCondition(Field<Object> field, FilterOperator operator, Object rawValue) {
        return toCondition(field, operator, rawValue, null);
    }

    public static Condition toCondition(Field<Object> field, FilterOperator operator, Object rawValue,
                                         FieldDataType dataType) {
        Object value = FieldValueConverter.convert(dataType, rawValue);

        return switch (operator) {
            case EQ -> field.eq(value);
            case NE -> field.ne(value);
            case GT -> field.greaterThan(value);
            case GTE -> field.greaterOrEqual(value);
            case LT -> field.lessThan(value);
            case LTE -> field.lessOrEqual(value);
            case LIKE -> field.like("%" + value + "%");
            case IN -> field.in((List<?>) value);
            case IS_NULL -> field.isNull();
            case IS_NOT_NULL -> field.isNotNull();
            case BETWEEN -> {
                List<?> range = (List<?>) value;
                if (range.size() != 2) {
                    throw new IllegalArgumentException("BETWEEN 需要两个值，实际传了 " + range.size() + " 个");
                }
                yield field.between(range.get(0), range.get(1));
            }
        };
    }
}
