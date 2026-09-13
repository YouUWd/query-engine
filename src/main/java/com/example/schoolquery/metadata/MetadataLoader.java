package com.example.schoolquery.metadata;

import com.example.schoolquery.model.RelationType;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import com.example.schoolquery.model.SysTableRelation;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.ArrayList;
import java.util.List;

import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * 从数据库表（sys_module / sys_module_field / sys_table_relation）加载 MetadataRegistry。
 *
 * 数据的一次性导出快照，适合做迁移/初始化工具，但应用运行时（包括测试）应该直接对着
 * 配置库查，而不是依赖一份可能已经过期的 CSV 文件。
 *
 * dsl 应该指向配置库自己的连接——和业务数据的 school 库是两个不同的 DataSource/DSLContext，
 * 对应“CSV 只是 config 库的导出快照，业务数据在 school 库”这套多库架构。
 */
public final class MetadataLoader {

    private MetadataLoader() {}

    public static MetadataRegistry load(DSLContext dsl) {
        List<SysModule> modules = new ArrayList<>();
        for (Record r : dsl.selectFrom(table(name("sys_module"))).fetch()) {
            modules.add(new SysModule(
                    r.get("id", Long.class),
                    r.get("module_code", String.class),
                    r.get("module_name", String.class),
                    r.get("primary_table", String.class), // 虚拟模块这一列是 NULL
                    r.get("parent_id", Long.class)
            ));
        }

        List<SysModuleField> fields = new ArrayList<>();
        for (Record r : dsl.selectFrom(table(name("sys_module_field"))).fetch()) {
            fields.add(new SysModuleField(
                    r.get("id", Long.class),
                    r.get("module_id", Long.class),
                    r.get("table_name", String.class),
                    r.get("column_name", String.class),
                    r.get("display_name", String.class),
                    r.get("sort_order", Integer.class)
            ));
        }

        List<SysTableRelation> relations = new ArrayList<>();
        for (Record r : dsl.selectFrom(table(name("sys_table_relation"))).fetch()) {
            relations.add(new SysTableRelation(
                    r.get("id", Long.class),
                    r.get("main_table", String.class),
                    r.get("main_field", String.class),
                    r.get("join_table", String.class),
                    r.get("join_field", String.class),
                    RelationType.fromCode(r.get("relation_type", String.class))
            ));
        }

        return new MetadataRegistry(modules, fields, relations);
    }
}
