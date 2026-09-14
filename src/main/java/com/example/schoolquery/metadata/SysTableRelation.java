package com.example.schoolquery.metadata;

/**
 * 对应 sys_table_relation 表的一行配置：mainTable(1) -> joinTable(1 或 N)。
 * mainField/joinField 分别是两张表上用于关联的列名。
 */
public record SysTableRelation(
        long id,
        String mainTable,
        String mainField,
        String joinTable,
        String joinField,
        RelationType type
) {
    /** 关系里是否涉及给定的两张表（不关心谁是 main）。 */
    public boolean connects(String tableA, String tableB) {
        return (mainTable.equals(tableA) && joinTable.equals(tableB))
                || (mainTable.equals(tableB) && joinTable.equals(tableA));
    }
}
