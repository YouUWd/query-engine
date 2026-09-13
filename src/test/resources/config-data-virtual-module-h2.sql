-- 专门给虚拟模块测试用的小配置集：1(学生,真实根) -> 2(虚拟分组) -> 3(荣誉,真实子模块)。
-- 用的是 config-schema-h2.sql 同一套表结构，装进一个单独的 H2 库里，
-- 不和主配置集（8个真实模块）混在一起，避免互相干扰。

INSERT INTO sys_module (id, module_code, module_name, primary_table, parent_id) VALUES
    (1, 'MOD-ROOT', '学生', 'student', 0),
    (2, 'MOD-VIRTUAL', '荣誉相关', NULL, 1),
    (3, 'MOD-CHILD', '荣誉', 'student_award', 2);

INSERT INTO sys_module_field (id, module_id, table_name, column_name, display_name, sort_order) VALUES
    (10, 1, 'student', 'name', '姓名', 1),
    (11, 3, 'student_award', 'award_name', '荣誉名称', 1);

INSERT INTO sys_table_relation (id, main_table, main_field, join_table, join_field, relation_type) VALUES
    (1, 'student', 'id', 'student_award', 'student_id', '1:N');
