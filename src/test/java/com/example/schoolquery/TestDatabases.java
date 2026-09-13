package com.example.schoolquery;

import com.example.schoolquery.metadata.MetadataLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 所有测试共用：起 H2 数据库，分别对应两套"库"——
 *   - config 库：sys_module / sys_module_field / sys_table_relation（配置库，
 *     种子数据由 config-data-h2.sql 提供，那份 SQL 是从真实的 sys_*.csv 导出
 *     程序生成的，不是手抄的）
 *   - school 库：student / course / ... 这些业务表（数据库，种子数据来自
 *     上传的 school.sql 里的真实样例行）
 *
 * 测试不再直接读 CSV——CSV 只是 config 库数据的一次性导出快照，用来生成
 * config-data-h2.sql；测试运行时统一通过 {@link MetadataLoader}
 * 对着 config 库的 H2 连接查，跟生产环境的加载方式一致。
 */
public final class TestDatabases {

    private TestDatabases() {}

    /** 完整的配置库：8 个真实模块 + 全部字段 + 全部关系（对应真实的 sys_*.csv 导出）。 */
    public static Connection openConfigDb(String memName) throws SQLException {
        Connection conn = h2(memName);
        runScript(conn, "/config-schema-h2.sql");
        runScript(conn, "/config-data-h2.sql");
        return conn;
    }

    /** 只建表、不灌数据的配置库——给需要自己插入小规模场景数据（比如某个特定的反例）的测试用。 */
    public static Connection openEmptyConfigDb(String memName) throws SQLException {
        Connection conn = h2(memName);
        runScript(conn, "/config-schema-h2.sql");
        return conn;
    }

    /** 专门给虚拟模块场景用的配置库：1(学生,真实根) -> 2(虚拟分组) -> 3(荣誉,真实子模块)。 */
    public static Connection openVirtualModuleConfigDb(String memName) throws SQLException {
        Connection conn = h2(memName);
        runScript(conn, "/config-schema-h2.sql");
        runScript(conn, "/config-data-virtual-module-h2.sql");
        return conn;
    }

    /** school 业务库：student/course/... 这些表 + school.sql 里的真实样例数据。 */
    public static Connection openSchoolDb(String memName) throws SQLException {
        Connection conn = h2(memName);
        runScript(conn, "/schema-h2.sql");
        runScript(conn, "/data-h2.sql");
        return conn;
    }

    private static Connection h2(String memName) throws SQLException {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return DriverManager.getConnection(
                "jdbc:h2:mem:" + memName + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1");
    }

    private static void runScript(Connection connection, String classpathResource) throws SQLException {
        try (InputStream in = TestDatabases.class.getResourceAsStream(classpathResource)) {
            if (in == null) throw new IllegalStateException("Missing test resource: " + classpathResource);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement st = connection.createStatement()) {
                for (String statement : sql.split(";")) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        st.execute(trimmed);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
