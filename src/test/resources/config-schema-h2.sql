-- 配置库（config库）的表结构——sys_module / sys_module_field / sys_table_relation。
-- 只保留 MetadataDbLoader 需要的列；真实项目里这几张表还有 project_no、created_by
-- 这些审计字段，测试不需要就没建。

CREATE TABLE sys_module (
    id BIGINT PRIMARY KEY,
    module_code VARCHAR(64) NOT NULL,
    module_name VARCHAR(64) NOT NULL,
    primary_table VARCHAR(64),      -- NULL = 虚拟模块，不对应物理表
    parent_id BIGINT NOT NULL       -- 0 = 根模块
);

CREATE TABLE sys_module_field (
    id BIGINT PRIMARY KEY,
    module_id BIGINT NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    column_name VARCHAR(64) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL
);

CREATE TABLE sys_table_relation (
    id BIGINT PRIMARY KEY,
    main_table VARCHAR(64) NOT NULL,
    main_field VARCHAR(64) NOT NULL,
    join_table VARCHAR(64) NOT NULL,
    join_field VARCHAR(64) NOT NULL,
    relation_type VARCHAR(8) NOT NULL   -- '1:1' 或 '1:N'，和 CSV 导出格式保持一致
);
