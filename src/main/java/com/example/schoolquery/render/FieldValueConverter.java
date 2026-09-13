package com.example.schoolquery.render;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

/**
 * 把请求里过滤条件的原始值（通常是 JSON 反序列化出来的 String/Number/List）
 * 转成 dataType 对应的 Java 类型，再交给 jOOQ 绑定参数——不然像
 * {@code {"operator":"GT","value":"90"}} 这种数字过滤条件，如果 "90" 一直
 * 以 String 形式传下去，会被当成字符串比较而不是数值比较；日期同理。
 *
 * 对 IN / BETWEEN 这种"值本身是个集合"的情况，会对集合里每个元素分别转换。
 */
public final class FieldValueConverter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATETIME_FMT_T = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter DATETIME_FMT_SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private FieldValueConverter() {}

    /** value 可能是单个值，也可能是 List（IN / BETWEEN 的场景）——两种情况都处理。 */
    public static Object convert(FieldDataType type, Object value) {
        if (type == null || value == null) return value;
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(v -> convertSingle(type, v)).toList();
        }
        return convertSingle(type, value);
    }

    private static Object convertSingle(FieldDataType type, Object raw) {
        try {
            return switch (type) {
                case STRING -> raw.toString();
                case INTEGER -> raw instanceof Integer i ? i : Integer.valueOf(raw.toString().trim());
                case LONG -> raw instanceof Long l ? l : Long.valueOf(raw.toString().trim());
                case DECIMAL -> raw instanceof BigDecimal d ? d : new BigDecimal(raw.toString().trim());
                case BOOLEAN -> convertBoolean(raw);
                case DATE -> raw instanceof LocalDate d ? d : LocalDate.parse(raw.toString().trim(), DATE_FMT);
                case DATETIME -> convertDateTime(raw);
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "值 \"" + raw + "\" 不能转换成 " + type + " 类型: " + e.getMessage(), e);
        }
    }

    private static Boolean convertBoolean(Object raw) {
        if (raw instanceof Boolean b) return b;
        String s = raw.toString().trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) return Boolean.FALSE;
        throw new IllegalArgumentException("无法识别的布尔值: " + s);
    }

    private static LocalDateTime convertDateTime(Object raw) {
        if (raw instanceof LocalDateTime dt) return dt;
        String s = raw.toString().trim();
        // 兼容 "2026-01-01T10:00:00" 和 "2026-01-01 10:00:00" 两种常见格式
        return s.contains("T") ? LocalDateTime.parse(s, DATETIME_FMT_T) : LocalDateTime.parse(s, DATETIME_FMT_SPACE);
    }
}
