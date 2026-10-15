package com.tinyquery.api;

import com.tinyquery.executor.ChartResult;
import com.tinyquery.executor.ColumnStore;
import com.tinyquery.executor.QueryEngine;
import com.tinyquery.executor.QueryResult;
import com.tinyquery.export.XlsxWriter;
import com.tinyquery.parser.NameSuggester;
import com.tinyquery.parser.QueryHintException;
import com.tinyquery.parser.QueryStatement;
import com.tinyquery.parser.SQLParser;
import com.tinyquery.datasource.DataSourceManager;
import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.i18n.Messages;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Tag(name = "Query API", description = "Execute SQL Query")
@RestController
@RequestMapping("/api")
public class QueryController {

    // 导出时每多少行 flush 一次输出流。太小会频繁写盘，太大会让首字节延迟变高
    private static final int EXPORT_FLUSH_INTERVAL = 1000;

    // 分页的默认与上限。上限是服务端硬约束，防止前端传个极大的 pageSize 把浏览器打崩
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 1000;

    // 图表分组的服务端硬上限。柱状图 50 根还能看，再多就必须靠「其他」合并
    private static final int MAX_CHART_GROUPS = 50;

    // 图表最多支持的度量个数。三种图里两个系列还能分清，
    // 再多柱子会挤成一团，双 Y 轴也没法分
    private static final int MAX_CHART_MEASURES = 2;

    private final DataSourceManager manager;
    private final SQLParser parser = new SQLParser();

    public QueryController(DataSourceManager manager) {
        this.manager = manager;
    }

    @Operation(summary = "Execute SQL Query", description = "Pass in a SQL statement and return the query result")
    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest request) {
        try {
            QueryTimer timer = QueryTimer.start();

            QueryStatement stmt = parser.parse(request.sql());

            ColumnStore store = manager.getByTableName(stmt.tableName());
            QueryEngine engine = new QueryEngine(store);

            // page 缺省为第 1 页，因此不传分页参数也能正常查询。
            // 行数上限由 pageSize 的规范化兜底，不再有「不分页」这条单独路径
            int page = request.page() == null ? 1 : Math.max(1, request.page());
            int pageSize = normalizePageSize(request.pageSize());

            QueryResult result = engine.executePage(stmt, page, pageSize);

            // 用 nanoTime 而不是 currentTimeMillis：查询常在毫秒级，
            // 墙上时间测不准、还会报 0（见 QueryTimer 的说明）
            long elapsed = timer.elapsedMillis();

            return new QueryResponse(0, "success", null, result.columns(), result.columnTypes(), result.rows(),
                    result.hasMore(), result.totalRows(), page, pageSize, elapsed);
        } catch (QueryHintException e) {
            // 业务失败，但顺带有一句给用户的提示（紫色并排展示）
            return new QueryResponse(1, e.getMessage(), e.getHint(), null, null, null, false, 0, 0, 0, 0);
        } catch (Exception e) {
            String hint = null;
            // 数据源名只差大小写时给一句提示。列名那类候选在 ColumnStore 里就已经带上了，
            // 这里只管数据源名 —— 表名解析发生在拿 ColumnStore 之前，那边查不到数据源名单
            //
            // 靠错误码判断而不是 message 前缀：message 会随界面语言变，
            // 用中文前缀匹配的写法在英文模式下会静默失效（切了英文就没提示了）
            if (e instanceof DataSourceException dse && dse.is("error.datasourceNotFound")) {
                Object[] args = dse.errorArgs();
                if (args.length > 0) {
                    String requested = String.valueOf(args[0]).trim();
                    hint = NameSuggester.describe(requested,
                            NameSuggester.suggest(requested, manager.listNames()));
                }
            }
            return new QueryResponse(1, e.getMessage(), hint, null, null, null, false, 0, 0, 0, 0);
        }
    }

    /**
     * 按维度与度量聚合，供图表使用。
     *
     * <p>与 /query 的区别：这里不返回明细行，只返回绘图所需的数据。
     * 分组在全部命中行上完成，与分页无关 —— 图表看到的是完整数据，
     * 不会随表格翻页而变。</p>
     */
    @Operation(summary = "Aggregate for Chart",
            description = "Group by a dimension and aggregate up to two measures, for chart rendering")
    @PostMapping("/query/chart")
    public ChartResponse chart(@RequestBody ChartRequest request) {
        try {
            QueryTimer timer = QueryTimer.start();

            String aggregation = normalizeAggregation(request.aggregation());

            // 度量个数收进上限。前端切到饼图时会主动只传一个，
            // 这里是服务端硬约束，防止绕过
            List<String> measures = normalizeMeasures(request.measures());

            // count 不需要度量列，其余必须给。
            // 前端在自动重绘，这条失败会静默发生，所以错误信息要能说清原因
            if (!"count".equals(aggregation) && measures.isEmpty()) {
                return new ChartResponse(1, Messages.get("error.measureRequired"), null, null, 0, false, false, 0);
            }

            QueryStatement stmt = parser.parse(request.sql());

            ColumnStore store = manager.getByTableName(stmt.tableName());
            QueryEngine engine = new QueryEngine(store);

            ChartResult result = engine.aggregateForChart(stmt, request.dimension(), measures,
                    aggregation, normalizeChartTopN(request.topN()));

            // 同上：图表聚合耗时同样常在毫秒级
            long elapsed = timer.elapsedMillis();

            return new ChartResponse(0, "success", result.categories(), result.series(),
                    result.totalGroups(), result.truncated(), result.bucketed(), elapsed);
        } catch (Exception e) {
            return new ChartResponse(1, e.getMessage(), null, null, 0, false, false, 0);
        }
    }

    // 度量列清洗：去空、去重、截到上限
    private List<String> normalizeMeasures(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        // 用 LinkedHashSet 保序去重：同一个度量选两次会让两条系列完全重合，
        // 而且系列名一样，图例分不出来
        Set<String> unique = new LinkedHashSet<>();
        for (String name : raw) {
            if (name != null && !name.isBlank()) {
                unique.add(name.trim());
            }
        }

        List<String> result = new ArrayList<>(unique);
        if (result.size() > MAX_CHART_MEASURES) {
            return result.subList(0, MAX_CHART_MEASURES);
        }
        return result;
    }

    // 把请求里的 pageSize 收进合法区间。非法值一律退回默认值，不报错
    private int normalizePageSize(Integer raw) {
        if (raw == null || raw <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(raw, MAX_PAGE_SIZE);
    }

    // 聚合方式白名单。不认识的写法一律当求和，避免前端传错值导致空图
    private String normalizeAggregation(String raw) {
        if (raw == null) {
            return "sum";
        }
        return switch (raw) {
            case "avg", "count", "max", "min" -> raw;
            default -> "sum";
        };
    }

    // 图表分组数收进合法区间。上限是服务端硬约束，
    // 防止前端传个大值绕过「其他」合并
    private Integer normalizeChartTopN(Integer raw) {
        if (raw == null || raw <= 0) {
            return null;    // 交给 QueryEngine 取默认值
        }
        return Math.min(raw, MAX_CHART_GROUPS);
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
        // 先完成解析与过滤。此阶段可能失败，失败时响应头还没发出，可以正常返回 JSON 错误
        ExportPlan plan = planExport(request, response);
        if (plan == null) {
            return;
        }

        String fileName = exportFileName(plan.tableName(), "csv");

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv; charset=UTF-8");
        setDownloadHeaders(response, fileName);

        PrintWriter writer = response.getWriter();

        // UTF-8 BOM：不加的话 Excel 打开中文列名会乱码
        writer.write('\uFEFF');
        writeCsvRow(writer, plan.cols().names());

        int written = 0;
        for (int idx : plan.indexes()) {
            writeCsvRow(writer, plan.engine().projectRow(plan.stmt(), plan.cols(), idx));

            // 定期 flush，让浏览器尽早开始接收，避免全部攒在缓冲区
            if (++written % EXPORT_FLUSH_INTERVAL == 0) {
                writer.flush();
            }
        }

        writer.flush();
    }

    /**
     * 导出查询结果为 Excel（.xlsx）。
     *
     * <p>与 CSV 导出的两处关键差别：</p>
     *
     * <ul>
     *   <li><b>保留类型</b>：数字与日期按原生类型写出，Excel 里可以直接求和、排序、
     *       画图。CSV 里一切都是文本，这正是用户抱怨「导出来没法用」的根因。</li>
     *   <li><b>表头加粗并冻结首行</b>：数据一多，滚动后看不见列名是最常见的抱怨。</li>
     * </ul>
     *
     * <p>同样不走分页也不截断，逐行写出，内存占用与总行数无关。</p>
     */
    @Operation(summary = "Export Query Result as Excel",
            description = "Stream the full result set as .xlsx with native number and date types")
    @PostMapping("/query/export/xlsx")
    public void exportXlsx(@RequestBody QueryRequest request, HttpServletResponse response) throws IOException {
        ExportPlan plan = planExport(request, response);
        if (plan == null) {
            return;
        }

        String fileName = exportFileName(plan.tableName(), "xlsx");

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        setDownloadHeaders(response, fileName);

        try (XlsxWriter writer = new XlsxWriter(response.getOutputStream(), plan.tableName())) {
            writer.writeHeader(plan.cols().names());
            for (int idx : plan.indexes()) {
                writer.writeRow(plan.engine().projectRowTyped(plan.stmt(), plan.cols(), idx));
            }
        }
    }

    /**
     * 导出前的公共准备：解析、取列、算行序。
     *
     * <p>CSV 与 Excel 两个导出共用，避免两处各写一遍解析逻辑而慢慢长歪。
     * 失败时本方法已经把 JSON 错误写进响应并返回 {@code null}，调用方直接 return 即可。</p>
     */
    private ExportPlan planExport(QueryRequest request, HttpServletResponse response) throws IOException {
        try {
            QueryStatement stmt = parser.parse(request.sql());

            if (stmt.isAggregate()) {
                writeError(response, Messages.get("error.exportNotSupported"));
                return null;
            }

            ColumnStore store = manager.getByTableName(stmt.tableName());
            QueryEngine engine = new QueryEngine(store);
            QueryEngine.ProjectionColumns cols = engine.prepareColumns(stmt);
            // 用 listRows 而不是单纯的过滤：导出必须与查询保持同样的行序
            List<Integer> indexes = engine.listRows(stmt);

            return new ExportPlan(stmt, engine, cols, indexes, stmt.tableName());
        } catch (Exception e) {
            writeError(response, e.getMessage());
            return null;
        }
    }

    // 两个导出共用的中间结果
    private record ExportPlan(QueryStatement stmt, QueryEngine engine,
                              QueryEngine.ProjectionColumns cols,
                              List<Integer> indexes, String tableName) {
    }

    // 下载响应头。文件名同时给 ASCII 与 UTF-8 两种写法，兼容老浏览器
    private void setDownloadHeaders(HttpServletResponse response, String fileName) {
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''"
                        + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
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
    private String exportFileName(String tableName, String ext) {
        String base = tableName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        return base + "_" + stamp + "." + ext;
    }

    // 写出 JSON 错误体。仅在响应头尚未发出时可用
    private void writeError(HttpServletResponse response, String message) throws IOException {
        response.setContentType("application/json; charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);
        // 与 QueryResponse 的失败形态保持一致，前端拦截器可统一处理
        response.getWriter().write("{\"code\":1,\"message\":\""
                + escapeJson(message)
                + "\",\"columns\":null,\"columnTypes\":null,\"data\":null,\"hasMore\":false,"
                + "\"totalRows\":0,\"page\":0,\"pageSize\":0,\"elapsed\":0}");
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