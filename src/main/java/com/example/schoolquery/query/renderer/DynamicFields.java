package com.example.schoolquery.query.renderer;

import static org.jooq.impl.DSL.name;

import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;



/**
 * 动态字段访问的唯一入口。
 *
 * 这里故意不用 {@code Table<?>.field(String)}——那个方法只会在“这张 Table 对象自己
 * 知道有哪些字段”时才能找到东西（代码生成出来的表，或者显式用 DSL.table(Name,
 * Field...) 声明过字段列表的表）。我们的表全部是 {@code table(name(tableName))}
 * 这种纯字符串构造出来的“空表”，压根没有字段列表可查，调用 {@code .field(String)}
 * 会直接返回 {@code null}——不是类型不安全，是运行时必然拿到 null，调用方一 .as()
 * 就是空指针。
 *
 * 正确的做法是用 {@code DSL.field(Name)} 直接构造一个"表名.列名"的限定字段引用，
 * 它不依赖表对象是否知道这个字段，只是在渲染 SQL 时原样拼出 "table"."column"，
 * 而且这个方法本身就声明返回 {@code Field<Object>}，不需要任何 cast。
 */
public final class DynamicFields {

    private DynamicFields() {}

    public static Field<Object> field(Table<?> table, String columnName) {
        return DSL.field(name(table.getName(), columnName));
    }
}
