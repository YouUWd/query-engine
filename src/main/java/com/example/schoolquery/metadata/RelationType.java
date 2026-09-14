package com.example.schoolquery.metadata;

/** sys_table_relation.relation_type 的取值：1:1 或 1:N（不存在 N:N，junction 表按两条 1:N 建模）。 */
public enum RelationType {
    ONE_TO_ONE,
    ONE_TO_MANY;

    public static RelationType fromCode(String code) {
        return switch (code) {
            case "1:1" -> ONE_TO_ONE;
            case "1:N" -> ONE_TO_MANY;
            default -> throw new IllegalArgumentException("Unknown relation_type: " + code);
        };
    }
}
