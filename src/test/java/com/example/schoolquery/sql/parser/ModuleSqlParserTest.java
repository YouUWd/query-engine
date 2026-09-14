package com.example.schoolquery.sql.parser;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModuleSqlParserTest {
    @Test void parsesModuleSelect(){
        ModuleSqlAst ast=new ModuleSqlParser().parseSelect("SELECT f3, f49 FROM 101 WHERE f3 LIKE '张%' AND f32 = '高等数学' ORDER BY f3 DESC LIMIT 10 OFFSET 20");
        assertEquals("101",ast.rootModuleToken());
        assertEquals(2,ast.projectionTokens().size());
        assertNotNull(ast.where());
        assertEquals(1,ast.sorts().size());
        assertEquals(10,ast.limit());
        assertEquals(20,ast.offset());
    }
    @Test void rejectsNonSelect(){assertThrows(IllegalArgumentException.class,()->new ModuleSqlParser().parseSelect("DELETE FROM 101"));}
}
