package com.tinyquery.api;

import com.tinyquery.executor.ChartResult;
import com.tinyquery.executor.ChartSeries;
import com.tinyquery.executor.ColumnStore;
import com.tinyquery.executor.QueryEngine;
import com.tinyquery.executor.QueryResult;
import com.tinyquery.export.PdfChart;
import com.tinyquery.export.PdfFonts;
import com.tinyquery.export.PdfLayout;
import com.tinyquery.export.PdfWriter;
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

    // 版面预估里最多回报几个「会被截断的列名」。
    // 全给出来弹框会被撑爆，而用户只需要知道大概有多少列有问题，
    // 具体是哪几列看导出结果比看列表直观
    private static final int TRUNCATED_NAMES_LIMIT = 6;

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
     * 导出查询结果为 PDF。
     *
     * <p>与 CSV / Excel 导出的区别：PDF 是「给人看的」，所以还会带上标题与
     * 「数据源 / 行数 / 导出时间」这行元信息，并自动分页、每页重复表头。</p>
     *
     * <p>中文字体内嵌在 jar 里（见 resources/fonts），换台电脑打开不会缺字 ——
     * 这是「导出报告发给别人」场景的硬要求。</p>
     */
    @Operation(summary = "Export Query Result as PDF",
            description = "Render the full result set as a paginated A4 PDF with embedded CJK font")
    @PostMapping("/query/export/pdf")
    public void exportPdf(@RequestBody QueryRequest request, HttpServletResponse response) throws IOException {
        ExportPlan plan = planExport(request, response);
        if (plan == null) {
            return;
        }

        String fileName = exportFileName(plan.tableName(), "pdf");

        response.setContentType("application/pdf");
        setDownloadHeaders(response, fileName);

        // 判断每列是否数值：决定 PDF 里左对齐还是右对齐。
        // 只采样前若干行即可 —— 一列里混着数字和文本时，按多数决定
        List<String[]> rows = new ArrayList<>();
        for (int idx : plan.indexes()) {
            rows.add(toStrings(plan.engine().projectRowTyped(plan.stmt(), plan.cols(), idx)));
        }
        boolean[] numeric = detectNumericColumns(plan.cols().names().length, rows);

        try (PdfWriter writer = new PdfWriter(PdfFonts.open())) {
            // 先定方向与字号，再写任何内容 —— 页码与分页位置都依赖页高，
            // 中途改会让已经放上页的内容错位
            writer.setLandscape(request.isLandscape());
            writer.setScale(request.scaleOrDefault());

            writer.setHeader(plan.tableName(),
                    Messages.get("pdf.subtitle", rows.size(), plan.columnCount()));
            writer.writeTable(plan.cols().names(), numeric, rows);
            response.getOutputStream().write(writer.finish());
        }
    }

    /**
     * 导出前的版面预估：这张表导出来会不会被截断。
     *
     * <p>为什么要有这个接口：A4 纵向可用宽度 515 点，16 列每列只分到 32 点
     * （约 3.6 个中文字），于是每列被截成「帖… 发… 内…」，连列名都认不出来。
     * 用户导完打开才发现没法看，只能换一次设置重导 —— 而换什么设置本来是可以
     * 提前算出来的。</p>
     *
     * <p>这里把 6 个档位（纵向/横向 × 3 档字号）一次全算出来给前端，
     * 由前端标出推荐档位。一次算完而不是让前端逐档来问，是因为每次请求都要
     * 重新取数与度量，来回六次纯属浪费。</p>
     *
     * <p>不返回数据本身，只返回「放不放得下」—— 弹框里不需要看数据，
     * 只需要一个明确的结论。</p>
     */
    @Operation(summary = "Preview PDF Layout",
            description = "Estimate whether the result set fits on the page, for each orientation and font size")
    @PostMapping("/query/export/pdf-preview")
    public PdfLayoutResponse previewPdf(@RequestBody QueryRequest request) {
        try {
            ExportPlan plan = planExport(request, null);
            if (plan == null) {
                // planExport 在 response 为 null 时无法回写错误，直接给一个空结果，
                // 前端据此不显示预览而不是显示一个假结论
                return new PdfLayoutResponse(0, 0, List.of(), -1);
            }

            List<String[]> rows = new ArrayList<>();
            for (int idx : plan.indexes()) {
                rows.add(toStrings(plan.engine().projectRowTyped(plan.stmt(), plan.cols(), idx)));
            }

            String[] names = plan.cols().names();
            List<PdfLayoutResponse.Option> options = new ArrayList<>();
            int best = -1;

            // 一次量完 6 个档位。度量器用的是 PDF 里真实嵌入的那套字形宽度，
            // 所以预估与最终版面对得上 —— 两边各写一套的话，
            // 预估说放得下、实际却截断了，比不预估还糟
            try (PdfWriter meter = new PdfWriter(PdfFonts.open())) {
                for (PdfLayout.Estimate e : PdfLayout.estimateAll(meter::measure, names, rows)) {
                    List<String> truncatedNames = new ArrayList<>();
                    for (PdfLayout.ColumnFit fit : e.truncated()) {
                        if (truncatedNames.size() < TRUNCATED_NAMES_LIMIT) {
                            truncatedNames.add(fit.columnName());
                        }
                    }
                    options.add(new PdfLayoutResponse.Option(
                            e.landscape(), e.scale(), e.totalWidth(),
                            e.truncatedCount(), truncatedNames, e.allFit()));
                }
            }

            // 推荐档位：优先挑「能全部放下」里代价最小的那个（列表本身就是
            // 按代价从低到高排的，所以第一个全放下的就是最优解）。
            // 一个都放不下时，退而在横向档位里挑放不下最少的 ——
            // 与其推荐一个纵向的残缺方案，不如推荐横向里最好的那个
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).fits()) {
                    best = i;
                    break;
                }
            }
            if (best < 0) {
                int fewest = Integer.MAX_VALUE;
                for (int i = 0; i < options.size(); i++) {
                    PdfLayoutResponse.Option o = options.get(i);
                    if (o.landscape() && o.truncatedCount() < fewest) {
                        fewest = o.truncatedCount();
                        best = i;
                    }
                }
            }

            return new PdfLayoutResponse(names.length, rows.size(), options, best);
        } catch (Exception e) {
            // 预估失败不该挡住导出 —— 它只是「提前知情」的辅助，
            // 前端拿不到结论就照常导出即可
            return new PdfLayoutResponse(0, 0, List.of(), -1);
        }
    }

    /**
     * 图表导出为 PDF。
     *
     * <p>图表用<b>矢量</b>方式画进 PDF，而不是把界面上那张 canvas 截图塞进去：
     * PDF 的价值就在于放大不糊，一张报表可能被投到会议室大屏或打印成 A3，
     * 塞位图进去放大就是马赛克，那还不如直接发 PNG。</p>
     *
     * <p>数据来自与界面完全相同的那次聚合（同一套 normalize 规则），
     * 所以导出的图与屏幕上看到的一致。默认还会附上底下的数据表 ——
     * 光有图看不出精确值，报表里图和数都要有。</p>
     */
    @PostMapping("/query/export/chart-pdf")
    public void exportChartPdf(@RequestBody ChartPdfRequest request, HttpServletResponse response)
            throws IOException {
        ChartResponse chart;
        try {
            chart = chart(new ChartRequest(request.sql(), request.dimension(),
                    request.measures(), request.aggregation(), request.topN()));
        } catch (Exception e) {
            writeJsonError(response, e.getMessage());
            return;
        }

        if (chart.code() != 0) {
            writeJsonError(response, chart.message());
            return;
        }

        String fileName = exportFileName(request.tableName(), "pdf");
        response.setContentType("application/pdf");
        setDownloadHeaders(response, fileName);

        List<PdfChart.Series> series = toChartSeries(chart.series());
        List<String> categories = chart.categories() == null ? List.of() : chart.categories();
        String chartType = normalizeChartType(request.chartType());

        try (PdfWriter writer = new PdfWriter(PdfFonts.open())) {
            writer.setLandscape(request.isLandscape());
            writer.setScale(request.scaleOrDefault());

            String title = request.tableName() == null || request.tableName().isBlank()
                    ? Messages.get("chart.exportTitle")
                    : request.tableName();
            writer.setHeader(title, Messages.get("pdf.chartSubtitle",
                    categories.size(), series.size()));
            // 先把标题块写出来。writeTable 自己也会调它，但「只要图不要表」时
            // 没有 writeTable 可依赖，标题就永远不会画出来，
            // 报表顶端会是一片空白。重复调用是安全的
            writer.writeHeader();

            // 图表占页面上部；高度按 16:9 左右给，太扁的图看不出趋势
            float blockH = 330f;
            PdfChart c = writer.chartBlock(blockH, chartHeadline(request, series));
            // 把界面上的配色、高亮、双轴、精度原样带上 ——
            // 少带任何一项，导出的图就不是用户屏幕上那张
            c.setStyle(new PdfChart.Style(
                    seriesColors(request, series.size()),
                    request.sectorColors(),
                    request.isHighlightMode(),
                    request.highlighted() == null ? List.of() : request.highlighted(),
                    request.isUnifyScale(),
                    request.isKeepFullPrecision(),
                    request.axisRanges()));
            c.draw(chartType, categories, series);

            // 附上数据表。图看趋势、表看精确值，两者都要 ——
            // 而且只给图的话，用户想核对数字还得回去再查一次
            if (request.isIncludeTable()) {
                writer.gap(12f);
                writer.separator();
                writer.gap(10f);

                String[] names = new String[1 + series.size()];
                boolean[] numeric = new boolean[names.length];
                names[0] = request.dimension() == null ? "" : request.dimension();
                numeric[0] = false;
                for (int i = 0; i < series.size(); i++) {
                    names[i + 1] = series.get(i).name();
                    numeric[i + 1] = true;
                }

                List<String[]> rows = new ArrayList<>();
                for (int r = 0; r < categories.size(); r++) {
                    String[] row = new String[names.length];
                    row[0] = categories.get(r);
                    for (int s = 0; s < series.size(); s++) {
                        List<Double> vals = series.get(s).values();
                        Double v = r < vals.size() ? vals.get(r) : null;
                        // 表里的精度跟着图上的开关走，否则同一份 PDF 里
                        // 图上写 59043.49、表里写 59043.4873，自相矛盾
                        row[s + 1] = v == null ? ""
                                : PdfChart.formatValue(v, request.isKeepFullPrecision());
                    }
                    rows.add(row);
                }
                writer.writeTable(names, numeric, rows);
            }

            response.getOutputStream().write(writer.finish());
        }
    }

    /**
     * 取出每个系列在界面上的主色。
     *
     * <p>界面把派生好的整排颜色发过来（一排 10 个），柱状/折线只用每排的第 0 个 ——
     * 与界面 {@code paletteOf(i)[0]} 的用法一致。饼图用整排，由 PdfChart 自己按下标取模。</p>
     */
    private static List<String> seriesColors(ChartPdfRequest request, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // colorAt(i, 0) 取第 i 排的第 0 个；没给就放 null，由 PdfChart 用默认色
            out.add(request.colorAt(i, 0));
        }
        return out;
    }

    // BigDecimal → double。图表只需要画图用的精度，转成 double 后
    // 绘图代码不必到处处理 BigDecimal
    private static List<PdfChart.Series> toChartSeries(List<ChartSeries> raw) {
        List<PdfChart.Series> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (ChartSeries s : raw) {
            List<Double> vals = new ArrayList<>();
            if (s.values() != null) {
                for (java.math.BigDecimal v : s.values()) {
                    vals.add(v == null ? null : v.doubleValue());
                }
            }
            out.add(new PdfChart.Series(s.name(), vals));
        }
        return out;
    }

    // 只认三种图，其余一律按柱状处理 —— 与前端下拉框的可选值保持一致
    private static String normalizeChartType(String raw) {
        if ("line".equals(raw) || "pie".equals(raw)) {
            return raw;
        }
        return "bar";
    }

    /**
     * 图上方那行小标题。
     *
     * <p>前端会传「维度 — 聚合(度量)」，但接口不该依赖前端一定传 ——
     * 直接调接口（脚本、将来的报告功能）时不传标题，图上方就会空着一块，
     * 看图的人不知道这根柱子是什么。所以这里用维度与系列名兜一个出来。</p>
     */
    private static String chartHeadline(ChartPdfRequest request, List<PdfChart.Series> series) {
        if (request.chartTitle() != null && !request.chartTitle().isBlank()) {
            return request.chartTitle();
        }
        String dim = request.dimension() == null || request.dimension().isBlank()
                ? Messages.get("chart.exportTitle")
                : request.dimension();
        if (series.isEmpty()) {
            return dim;
        }
        // 系列名就是聚合表达式（如 sum(销售额)），拿它拼一个能自解释的标题
        return dim + " — " + series.get(0).name();
    }

    // 导出失败时把原因当 JSON 写回去。
    // 不能只设 HTTP 状态码：前端拿到的响应体若不是 JSON，就没法把话说清楚
    private void writeJsonError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        String safe = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"code\":1,\"message\":\"" + safe + "\"}");
    }

    // Object[] 转 String[]，供 PDF 使用（PDF 里一切都是文本）
    private static String[] toStrings(Object[] row) {
        String[] out = new String[row.length];
        for (int i = 0; i < row.length; i++) {
            out[i] = row[i] == null ? "" : String.valueOf(row[i]);
        }
        return out;
    }

    // 采样判断每列是否为数值列。数字右对齐才看得清量级
    private static boolean[] detectNumericColumns(int columns, List<String[]> rows) {
        boolean[] numeric = new boolean[columns];
        int sample = Math.min(rows.size(), 50);
        for (int c = 0; c < columns; c++) {
            int total = 0;
            int num = 0;
            for (int r = 0; r < sample; r++) {
                String[] row = rows.get(r);
                if (c >= row.length) {
                    continue;
                }
                String v = row[c];
                if (v == null || v.isEmpty()) {
                    continue;
                }
                total++;
                try {
                    Double.parseDouble(v.trim());
                    num++;
                } catch (NumberFormatException ignored) {
                    // 不是数字
                }
            }
            numeric[c] = total > 0 && num * 2 >= total;
        }
        return numeric;
    }

    /**
     * 导出前的公共准备：解析、取列、算行序。
     *
     * <p>CSV / Excel / PDF 三个导出共用，避免各处各写一遍解析逻辑而慢慢长歪。
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

        /** 列数。PDF 的元信息行要用 */
        int columnCount() {
            return cols.names().length;
        }
    }

    /**
     * 下载响应头。
     *
     * <p>文件名要同时给两种写法，兼容不同浏览器：</p>
     * <ul>
     *   <li>{@code filename=} 只能是 ASCII。HTTP 头是 ISO-8859-1 编码的，
     *       直接塞中文会让 Tomcat 判定为非法头并<b>把整个头丢掉</b> ——
     *       表现是前端拿不到文件名，只能退化成默认名。所以这里对非 ASCII
     *       做百分号转义，并把空格转成 %20 而不是 +（{@code URLEncoder} 在
     *       form 语义下会把空格编成 +，那是错的）</li>
     *   <li>{@code filename*=UTF-8''} 是 RFC 5987 的写法，现代浏览器优先用它，
     *       这里能保留完整中文名</li>
     * </ul>
     */
    private void setDownloadHeaders(HttpServletResponse response, String fileName) {
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + asciiFileName(fileName) + "\""
                        + "; filename*=UTF-8''" + rfc5987Encode(fileName));
    }

    // 把文件名压成纯 ASCII：非 ASCII 字符按 UTF-8 逐字节做百分号转义。
    // 这样中文名在只认 ASCII 的浏览器里至少是个可读的转义串，而不是把响应头弄丢
    private String asciiFileName(String fileName) {
        StringBuilder sb = new StringBuilder(fileName.length());
        for (byte b : fileName.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c < 0x80 && c != '"' && c != '\\' && c >= 0x20) {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append("%20");
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    // RFC 5987 的 attr-char 允许集比普通 URL 编码更严：空格必须写成 %20
    private String rfc5987Encode(String fileName) {
        return URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
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
        // 预估接口没有响应对象可写（它走的是返回值而不是直接写流），
        // 传 null 表示「不用回写」—— 调用方拿到 null 自己决定怎么表达失败
        if (response == null) {
            return;
        }
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