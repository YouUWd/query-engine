package com.example.schoolquery.query;

import java.util.List;

/**
 * 表头树的一个节点。两种形态互斥（用哪个看是叶子字段还是模块分组）：
 *   - 叶子字段： fieldId + label + dataIndex 有值，moduleId/children 为 null
 *   - 模块分组： moduleId + label + children 有值，fieldId/dataIndex 为 null
 *     （虚拟模块也会以这种形态出现，纯粹用于分组，即便它自己不对应任何表）
 *
 * dataIndex 是"这个字段在 records 里的取值路径"，格式是 "table.column"，
 * 和 records 结构里 table 作为 key 的约定对应，前端按这个路径去 records 对应
 * 层级的对象里取值。
 */
public record HeaderNode(
        Long fieldId,
        Long moduleId,
        String label,
        String dataIndex,
        List<HeaderNode> children
) {
    public static HeaderNode leaf(long fieldId, String label, String dataIndex) {
        return new HeaderNode(fieldId, null, label, dataIndex, null);
    }

    public static HeaderNode module(long moduleId, String label, List<HeaderNode> children) {
        return new HeaderNode(null, moduleId, label, null, children);
    }

    public boolean isLeaf() {
        return fieldId != null;
    }
}
