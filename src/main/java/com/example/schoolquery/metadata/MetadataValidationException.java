package com.example.schoolquery.metadata;

/**
 * sys_module / sys_module_field / sys_table_relation 三张配置表本身存在问题时抛出——
 * 区别于调用方传参错误（IllegalArgumentException），这类异常意味着需要去修数据，
 * 而不是修调用代码。
 */
public class MetadataValidationException extends RuntimeException {
    public MetadataValidationException(String message) {
        super(message);
    }
}
