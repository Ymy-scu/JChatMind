package com.kama.jchatmind.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@Slf4j
public class DataBaseTools implements Tool {

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "GRANT", "REVOKE", "MERGE", "REPLACE", "LOAD", "COPY",
            "INTO OUTFILE", "INTO DUMPFILE", "FOR UPDATE", "FOR SHARE",
            "EXEC", "EXECUTE", "CALL"
    );

    private static final Pattern CLEAN_SQL = Pattern.compile(
            "\\b(" + String.join("|", FORBIDDEN_KEYWORDS) + ")\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern MULTI_STATEMENT = Pattern.compile(";\\s*\\S");

    private final JdbcTemplate jdbcTemplate;

    public DataBaseTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getName() {
        return "dataBaseTool";
    }

    @Override
    public String getDescription() {
        return "一个用于执行数据库查询操作的工具，主要用于从 PostgreSQL 中读取数据。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "databaseQuery", description = "用于在 PostgreSQL 中执行只读查询（SELECT）。接收由模型生成的查询语句，并返回结构化数据结果。该工具仅用于检索数据，严禁任何写入或修改数据库的语句。")
    public String query(String sql) {
        try {
            String trimmedSql = sql.trim();

            if (MULTI_STATEMENT.matcher(trimmedSql).find()) {
                log.warn("拒绝执行多条语句: {}", sql);
                return "错误：不允许执行多条 SQL 语句。";
            }

            if (CLEAN_SQL.matcher(trimmedSql).find()) {
                log.warn("拒绝执行非 SELECT 查询: {}", sql);
                return "错误：仅支持 SELECT 查询，禁止 INSERT/UPDATE/DELETE/DROP 等操作。";
            }

            String upperSql = trimmedSql.toUpperCase();
            if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH") && !upperSql.startsWith("EXPLAIN")) {
                log.warn("拒绝执行非查询语句: {}", sql);
                return "错误：仅支持 SELECT/WITH/EXPLAIN 查询。";
            }

            // 执行查询
            List<String> rows = jdbcTemplate.query(sql, (ResultSet rs) -> {
                List<String> resultRows = new ArrayList<>();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                if (columnCount == 0) {
                    resultRows.add("查询结果为空（无列）");
                    return resultRows;
                }

                // 获取列名和计算每列的最大宽度
                List<String> columnNames = new ArrayList<>();
                List<Integer> columnWidths = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    columnNames.add(columnName);
                    columnWidths.add(columnName.length());
                }

                // 收集所有行数据并计算列宽
                List<List<String>> dataRows = new ArrayList<>();
                while (rs.next()) {
                    List<String> rowData = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        String valueStr = value == null ? "NULL" : value.toString();
                        rowData.add(valueStr);
                        // 更新列宽
                        int currentWidth = columnWidths.get(i - 1);
                        if (valueStr.length() > currentWidth) {
                            columnWidths.set(i - 1, valueStr.length());
                        }
                    }
                    dataRows.add(rowData);
                }

                // 格式化表头
                StringBuilder header = new StringBuilder();
                header.append("| ");
                for (int i = 0; i < columnCount; i++) {
                    String columnName = columnNames.get(i);
                    int width = columnWidths.get(i);
                    header.append(String.format("%-" + width + "s", columnName)).append(" | ");
                }
                resultRows.add(header.toString());

                // 添加分隔线
                StringBuilder separator = new StringBuilder();
                separator.append("|");
                for (int i = 0; i < columnCount; i++) {
                    int width = columnWidths.get(i);
                    separator.append("-".repeat(width + 2)).append("|");
                }
                resultRows.add(separator.toString());

                // 格式化数据行
                if (dataRows.isEmpty()) {
                    StringBuilder emptyRow = new StringBuilder();
                    emptyRow.append("| ");
                    int totalWidth = columnWidths.stream().mapToInt(w -> w + 3).sum() - 1;
                    emptyRow.append(String.format("%-" + (totalWidth - 2) + "s", "(无数据)"));
                    emptyRow.append(" |");
                    resultRows.add(emptyRow.toString());
                } else {
                    for (List<String> rowData : dataRows) {
                        StringBuilder row = new StringBuilder();
                        row.append("| ");
                        for (int i = 0; i < columnCount; i++) {
                            String value = rowData.get(i);
                            int width = columnWidths.get(i);
                            row.append(String.format("%-" + width + "s", value)).append(" | ");
                        }
                        resultRows.add(row.toString());
                    }
                }

                return resultRows;
            });

            int dataRowCount = rows.size() - 2; // 减去表头和分隔线
            if (rows.size() > 2 && rows.get(rows.size() - 1).contains("(无数据)")) {
                dataRowCount = 0;
            }

            log.info("成功执行 SQL 查询，返回 {} 行数据", dataRowCount);
            // 将结果格式化为字符串
            return "查询结果:\n" + String.join("\n", rows);
        } catch (Exception e) {
            log.error("未知错误: {}", e.getMessage(), e);
            return "错误：操作失败 - " + e.getMessage() + "\nSQL: " + sql;
        }
    }
}
