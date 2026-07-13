package com.kama.jchatmind.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataBaseToolsTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private DataBaseTools dataBaseTools;

    @BeforeEach
    void setUp() {
        dataBaseTools = new DataBaseTools(jdbcTemplate);
    }

    @Test
    void shouldRejectMultiStatementSql() {
        String result = dataBaseTools.query("SELECT * FROM users; DELETE FROM users");
        assertEquals("错误：不允许执行多条 SQL 语句。", result);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldRejectInsertStatement() {
        String result = dataBaseTools.query("INSERT INTO users VALUES (1, 'test')");
        assertEquals("错误：仅支持 SELECT 查询，禁止 INSERT/UPDATE/DELETE/DROP 等操作。", result);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldRejectUpdateStatement() {
        String result = dataBaseTools.query("UPDATE users SET name = 'test' WHERE id = 1");
        assertTrue(result.contains("仅支持 SELECT 查询"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldRejectDeleteStatement() {
        String result = dataBaseTools.query("DELETE FROM users WHERE id = 1");
        assertTrue(result.contains("仅支持 SELECT 查询"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldRejectDropStatement() {
        String result = dataBaseTools.query("DROP TABLE users");
        assertTrue(result.contains("仅支持 SELECT 查询"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldRejectAlterStatement() {
        String result = dataBaseTools.query("ALTER TABLE users ADD COLUMN age INT");
        assertTrue(result.contains("仅支持 SELECT 查询"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldRejectTruncateStatement() {
        String result = dataBaseTools.query("TRUNCATE TABLE users");
        assertTrue(result.contains("仅支持 SELECT 查询"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldAcceptValidSelectStatement() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(2);
        when(metaData.getColumnName(1)).thenReturn("id");
        when(metaData.getColumnName(2)).thenReturn("name");
        when(rs.getMetaData()).thenReturn(metaData);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2);
        when(rs.getObject(2)).thenReturn("Alice", "Bob");

        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    return extractor.extractData(rs);
                });

        String result = dataBaseTools.query("SELECT id, name FROM users");
        assertTrue(result.contains("Alice"));
        assertTrue(result.contains("Bob"));
        assertTrue(result.contains("id"));
        assertTrue(result.contains("name"));
    }

    @Test
    void shouldAcceptWithStatement() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnName(1)).thenReturn("count");
        when(rs.getMetaData()).thenReturn(metaData);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(42);

        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    return extractor.extractData(rs);
                });

        String result = dataBaseTools.query("WITH cte AS (SELECT 1) SELECT * FROM cte");
        assertTrue(result.contains("42"));
    }

    @Test
    void shouldHandleSqlExceptionGracefully() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class)))
                .thenThrow(new RuntimeException("relation users does not exist"));

        String result = dataBaseTools.query("SELECT * FROM non_existent_table");
        assertTrue(result.contains("错误"));
        assertTrue(result.contains("non_existent_table"));
    }

    @Test
    void shouldHandleEmptyResult() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnName(1)).thenReturn("id");
        when(rs.getMetaData()).thenReturn(metaData);
        when(rs.next()).thenReturn(false);

        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    return extractor.extractData(rs);
                });

        String result = dataBaseTools.query("SELECT id FROM users WHERE 1=0");
        assertTrue(result.contains("(无数据)"));
    }

    @Test
    void shouldRejectCaseInsensitiveForbiddenKeywords() {
        String result = dataBaseTools.query("insert into users values (1)");
        assertTrue(result.contains("仅支持 SELECT 查询"));

        result = dataBaseTools.query("DROP table users");
        assertTrue(result.contains("仅支持 SELECT 查询"));
    }
}
