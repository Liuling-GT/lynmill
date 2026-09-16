package com.tinyquery.api;

import com.tinyquery.executor.ColumnStore;
import com.tinyquery.executor.QueryEngine;
import com.tinyquery.executor.QueryResult;
import com.tinyquery.parser.QueryStatement;
import com.tinyquery.parser.SQLParser;
import com.tinyquery.datasource.DataSourceManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "Query API", description = "Execute SQL Query")
@RestController
@RequestMapping("/api")
public class QueryController {

    // 导出时每多少行 flush 一次输出流。太小会频繁写盘，太大会让首字节延迟变高
    private static final int EXPORT_FLUSH_INTERVAL = 1000;

    // 分页的默认与上限。上限是服务端硬约束，防止前端传个极大的 pageSize 把浏览器打崩
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 1000;

    private final DataSourceManager manager;
    private final SQLParser parser = new SQLParser();

    public QueryController(DataSourceManager manager) {
        this.manager = manager;
    }

    @Operation(summary = "Execute SQL Query", description = "Pass in a SQL statement and return the query resul")
    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest request) {
        try {
            long start = System.currentTimeMillis();

            QueryStatement stmt = parser.parse(request.sql());

            ColumnStore store = manager.getByTableName(stmt.tableName());
            QueryEngine engine = new QueryEngine(store);

            // page 缺省为第 1 页，因此不传分页参数也能正常查询。
            // 行数上限由 pageSize 的规范化兜底，不再有「不分页」这条单独路径
            int page = request.page() == null ? 1 : Math.max(1, request.page());
            int pageSize = normalizePageSize(request.pageSize());

            QueryResult result = engine.executePage(stmt, page, pageSize);

            long elapsed = System.currentTimeMillis() - start;

            return new QueryResponse(0, "success", result.columns(), result.rows(),
                    result.hasMore(), result.totalRows(), page, pageSize, elapsed);
        } catch (Exception e) {
            return new QueryResponse(1, e.getMessage(), null, null, false, 0, 0, 0, 0);
        }
    }

    // 把请求里的 pageSize 收进合法区间。非法值一律退回默认值，不报错
    private int normalizePageSize(Integer raw) {
        if (raw == null || raw <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(raw, MAX_PAGE_SIZE);
    }

    /**
     * 导出查询结果为 CSV。
     *
     * <p>与 /query 的关键差别：不走分页也不截断，而是逐行投影、逐行写入响应流，
     * 因此内存占用与总行数无关，百万行也不会撑爆服务端。</p>
     *
     * <p>注意：本方法自己把异常写进响应体，不交给 GlobalExceptionHandler ——
     * 因为响应头一旦发出就无法改回 JSON 错误体，全局处理器此时无能为力。</p>
     */
    @Operation(summary = "Export Query Result as CSV",
            description = "Stream the full result set as CSV without row limit")
    @PostMapping("/query/export")
    public void export(@RequestBody QueryRequest request, HttpServletResponse response) throws IOException {
        QueryStatement stmt;
        QueryEngine engine;
        List<Integer> indexes;
        QueryEngine.ProjectionColumns cols;

        // 先完成解析与过滤。此阶段可能失败，失败时响应头还没发出，可以正常返回 JSON 错误
        try {
            stmt = parser.parse(request.sql());

            if (stmt.isAggregate()) {
                writeError(response, "聚合查询不支持导出，请导出明细数据");
                return;
            }

            ColumnStore store = manager.getByTableName(stmt.tableName());
            engine = new QueryEngine(store);
            cols = engine.prepareColumns(stmt);
            // 用 listRows 而不是单纯的过滤：导出必须与查询保持同样的行序
            indexes = engine.listRows(stmt);
        } catch (Exception e) {
            writeError(response, e.getMessage());
            return;
        }

        String fileName = exportFileName(stmt.tableName());

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''"
                        + URLEncoder.encode(fileName, StandardCharsets.UTF_8));

        PrintWriter writer = response.getWriter();

        // UTF-8 BOM：不加的话 Excel 打开中文列名会乱码
        writer.write('\uFEFF');
        writeCsvRow(writer, cols.names());

        int written = 0;
        for (int idx : indexes) {
            writeCsvRow(writer, engine.projectRow(stmt, cols, idx));

            // 定期 flush，让浏览器尽早开始接收，避免全部攒在缓冲区
            if (++written % EXPORT_FLUSH_INTERVAL == 0) {
                writer.flush();
            }
        }

        writer.flush();
    }

    // 把一行拼成 CSV 并写出。值中含逗号、引号或换行时按 RFC 4180 用引号包裹
    private void writeCsvRow(PrintWriter writer, String[] row) {
        for (int i = 0; i < row.length; i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escapeCsv(row[i]));
        }
        writer.write("\r\n");   // CRLF 是 CSV 的标准换行
    }

    // CSV 字段转义：含特殊字符时用引号包裹，内部引号翻倍
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;

        if (!needQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    // 导出文件名：数据源名 + 时间戳，避免多次导出互相覆盖
    private String exportFileName(String tableName) {
        String base = tableName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        return base + "_" + stamp + ".csv";
    }

    // 写出 JSON 错误体。仅在响应头尚未发出时可用
    private void writeError(HttpServletResponse response, String message) throws IOException {
        response.setContentType("application/json; charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);
        // 与 QueryResponse 的失败形态保持一致，前端拦截器可统一处理
        response.getWriter().write("{\"code\":1,\"message\":\""
                + escapeJson(message)
                + "\",\"columns\":null,\"data\":null,\"hasMore\":false,\"totalRows\":0,\"page\":0,\"pageSize\":0,\"elapsed\":0}");
        response.getWriter().flush();
    }

    // 转义 JSON 字符串中的引号与反斜杠
    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}