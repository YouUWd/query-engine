package com.example.schoolquery.engine;

import com.example.schoolquery.metadata.MetadataRegistry;
import com.example.schoolquery.model.SysModule;
import com.example.schoolquery.model.SysModuleField;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModuleSqlEngineTest {
    private MetadataRegistry registry(){return new MetadataRegistry(List.of(new SysModule(1,"student","学生","student",0)),List.of(new SysModuleField(101,1,"student","id","ID",1),new SysModuleField(102,1,"student","name","姓名",2),new SysModuleField(103,1,"student","age","年龄",3)),List.of());}
    private DSLContext dsl() throws Exception {Connection connection=DriverManager.getConnection("jdbc:h2:mem:module_sql;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1");connection.createStatement().execute("create table if not exists student (id int primary key, name varchar(100), age int)");connection.createStatement().execute("delete from student");connection.createStatement().execute("insert into student values (1, 'Alice', 17), (2, 'Bob', 20), (3, 'Carol', 30)");return DSL.using(connection,SQLDialect.H2);}
    @Test void executesModuleSqlWithoutLimitAndWithOr() throws Exception {ModuleQueryResult result=new DefaultModuleSqlEngine(registry()).executeQuery(dsl(),"select f101, f102 from module(1) where f102 = 'Alice' or f103 >= 30 order by f101");assertEquals(2,result.rows().size());Map<?,?> root=(Map<?,?>)result.rows().get(0).get("1");assertEquals("Alice",((Map<?,?>)root.get("student")).get("name"));root=(Map<?,?>)result.rows().get(1).get("1");assertEquals("Carol",((Map<?,?>)root.get("student")).get("name"));}
    @Test void executesExactOffset() throws Exception {ModuleQueryResult result=new DefaultModuleSqlEngine(registry()).executeQuery(dsl(),"select f101, f102 from module(1) order by f101 limit 1 offset 1");assertEquals(1,result.rows().size());Map<?,?> root=(Map<?,?>)result.rows().get(0).get("1");assertEquals(2,((Map<?,?>)root.get("student")).get("id"));}
}
