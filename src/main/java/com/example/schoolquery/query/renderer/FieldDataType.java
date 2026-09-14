package com.example.schoolquery.query.renderer;

/**
 * 过滤条件用的字段数据类型——只服务于 FilterConditionBuilder 的值转换，
 * 不是 SysModuleField 本身的属性（元数据表没有这一列，这是一套平行的、
 * 可选的类型提示：不配置就按原始值直接传给 jOOQ，配置了就先转换成对应
 * 的 Java 类型再传，避免数字/日期被当成字符串比较）。
 */
public enum FieldDataType {
    STRING, INTEGER, LONG, DECIMAL, DATE, DATETIME, BOOLEAN
}
