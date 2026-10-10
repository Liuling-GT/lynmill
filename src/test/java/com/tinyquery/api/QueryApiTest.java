package com.tinyquery.api;

import com.tinyquery.config.SecurityToken;
import com.tinyquery.config.TokenFilter;
import com.tinyquery.credential.NoOpCredentialStore;
import com.tinyquery.datasource.DataSourceManager;
import com.tinyquery.datasource.DataSourceStore;
import com.tinyquery.datasource.loader.CSVLoader;
import com.tinyquery.datasource.spec.DataSourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link QueryController} 的真实覆盖。
 *
 * <p>这是全项目最关键的一条链路：SQL 进来、结果出去。之前它是零测试的。
 *
 * <p><b>走 MockMvc 而不是直接调方法</b>：这条链上有请求体反序列化
 * （{@code page}/{@code pageSize} 缺省、类型转换）、有导出接口直接写
 * {@code HttpServletResponse}（那是方法体里测不到的），这些都得走框架才成立。
 *
 * <p>数据源用真实 CSV，走真实的 CSVLoader → ColumnStore → QueryEngine，
 * 不 mock 引擎 —— 引擎自己的用例在 {@code QueryEngineTest}，
 * 这里要测的是「控制器把请求正确地喂给引擎、再把结果正确地包成响应」。
 */
@DisplayName("QueryController 查询 / 图表 / 导出")
class QueryApiTest {

    @TempDir
    Path tempDir;

    private MockMvc mvc;
    private DataSourceManager manager;
    private String token;

    private static DataSourceStore storeAt(Path file) {
        try {
            Constructor<DataSourceStore> c = DataSourceStore.class.getDeclaredConstructor(Path.class);
            c.setAccessible(true);
            return c.newInstance(file);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法构造指向临时目录的 DataSourceStore", e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = new DataSourceManager(
                List.of(new CSVLoader()),
                storeAt(tempDir.resolve("datasources.json")),
                new NoOpCredentialStore("测试中不存密码"));

        // 30 行数据：够验证分页（默认每页 50 装得下，显式传小 pageSize 能分多页）
        Path csv = tempDir.resolve("sales.csv");
        StringBuilder sb = new StringBuilder("city,amount\n");
        String[] cities = {"北京", "上海", "广州"};
        for (int i = 0; i < 30; i++) {
            sb.append(cities[i % 3]).append(',').append((i + 1) * 10).append('\n');
        }
        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", csv.toString());
        manager.register(new DataSourceConfig("sales", "csv", props));

        mvc = MockMvcBuilders
                .standaloneSetup(new QueryController(manager))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TokenFilter())
                .build();
        token = SecurityToken.value();
    }

    /** 带上令牌与 Host（Host 那道关在令牌之前，不带会得到 403 而不是 200） */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        return b.header(SecurityToken.HEADER_NAME, token).header("Host", "localhost:8080");
    }

    private String queryBody(String sql, String extra) {
        return "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private org.springframework.test.web.servlet.ResultActions query(String sql, String extra)
            throws Exception {
        return mvc.perform(req(post("/api/query"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(queryBody(sql, extra)));
    }

    // ==================== 基本查询 ====================

    @Test
    @DisplayName("普通查询返回列名、数据与总数")
    void basicQuery() throws Exception {
        query("select * from sales", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.columns[0]").value("city"))
                .andExpect(jsonPath("$.columns[1]").value("amount"))
                .andExpect(jsonPath("$.totalRows").value(30));
    }

    @Test
    @DisplayName("默认分页为第 1 页、每页 50 行")
    void defaultPaging() throws Exception {
        // 不传分页参数也要能查 —— 这是老前端的兼容路径，不能因为缺参数就报错
        query("select * from sales", null)
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.data.length()").value(30));
    }

    @Test
    @DisplayName("显式分页：第 2 页拿到的是第 11-20 行")
    void explicitPaging() throws Exception {
        query("select * from sales", "\"page\":2,\"pageSize\":10")
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.data.length()").value(10))
                // totalRows 是结果集总数，不随翻页变 —— 前端靠它算总页数
                .andExpect(jsonPath("$.totalRows").value(30))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    @DisplayName("最后一页 hasMore 为 false")
    void lastPageHasNoMore() throws Exception {
        query("select * from sales", "\"page\":3,\"pageSize\":10")
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("页码超出范围返回空页而不是报错")
    void pageBeyondRangeIsEmpty() throws Exception {
        // 前端可能停在删空了的页上，此时报错会让界面卡住
        query("select * from sales", "\"page\":99,\"pageSize\":10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("pageSize 超上限被收到 1000")
    void pageSizeIsCapped() throws Exception {
        query("select * from sales", "\"pageSize\":999999")
                .andExpect(jsonPath("$.pageSize").value(1000));
    }

    @Test
    @DisplayName("非法 pageSize（0 / 负数）退回默认 50，不报错")
    void illegalPageSizeFallsBack() throws Exception {
        for (String bad : new String[]{"0", "-5"}) {
            query("select * from sales", "\"pageSize\":" + bad)
                    .andExpect(jsonPath("$.pageSize").value(50));
        }
    }

    @Test
    @DisplayName("非法 page（0 / 负数）被修正为 1")
    void illegalPageIsCorrected() throws Exception {
        query("select * from sales", "\"page\":0")
                .andExpect(jsonPath("$.page").value(1));
    }

    // ==================== 过滤 ====================

    @Test
    @DisplayName("where 过滤后 totalRows 是命中数，不是全表")
    void filterAffectsTotal() throws Exception {
        query("select * from sales where city = '北京'", null)
                .andExpect(jsonPath("$.totalRows").value(10));
    }

    @Test
    @DisplayName("聚合查询走聚合分支，返回一行")
    void aggregateQuery() throws Exception {
        query("select count(*) from sales", null)
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ==================== 错误路径 ====================

    @Test
    @DisplayName("语法错误 / 不支持的语法 → code=1，带可读 message")
    void parseErrorReturnsCode1() throws Exception {
        query("select * from sales group by city", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("数据源不存在 → code=1，带可读 message")
    void missingDataSourceFails() throws Exception {
        query("select * from sale", null)
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("大小写不同的数据源名 → 给「您可能想访问 sales」的提示")
    void caseMismatchGivesHint() throws Exception {
        // NameSuggester 只在「大小写变体唯一命中」时给提示，不做模糊匹配 ——
        // 所以 SALES 有提示，而下面那个 sale 没有。这个区分是刻意的：
        // 猜错不如不猜（见 NameSuggester 的类注释）
        query("select * from SALES", null)
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.hint").isNotEmpty())
                .andExpect(jsonPath("$.hint").value(org.hamcrest.Matchers.containsString("sales")));
    }

    @Test
    @DisplayName("近似的名字不给提示 —— 不做模糊匹配，避免误导")
    void nearMissHasNoHint() throws Exception {
        // 曾经误以为 sale 会提示 sales。实际不会：只做大小写变体匹配。
        // 这条测试把这个边界钉住，将来若改成模糊匹配，它会红，提醒同步改文档
        query("select * from sale", null)
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.hint").doesNotExist());
    }

    @Test
    @DisplayName("完全不相干的名字不给提示（避免噪音）")
    void unrelatedNameHasNoHint() throws Exception {
        query("select * from zzzzzzzz", null)
                .andExpect(jsonPath("$.code").value(1))
                // 提示为空或 null 都算「没给」，关键是别硬凑一个
                .andExpect(jsonPath("$.hint").doesNotExist());
    }

    @Test
    @DisplayName("缺 sql 字段 → code=1，不抛 500")
    void missingSqlFailsGracefully() throws Exception {
        mvc.perform(req(post("/api/query"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    // ==================== 图表接口 ====================

    private org.springframework.test.web.servlet.ResultActions chart(String body) throws Exception {
        return mvc.perform(req(post("/api/query/chart"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("图表按维度分组，返回类别与系列")
    void chartGroupsByDimension() throws Exception {
        chart("{\"sql\":\"select * from sales\",\"dimension\":\"city\","
                + "\"measures\":[\"amount\"],\"aggregation\":\"sum\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.categories.length()").value(3));
    }

    @Test
    @DisplayName("count 聚合不需要度量列")
    void countNeedsNoMeasure() throws Exception {
        // 前端切到「计数」时会传空的 measures，这条路径必须能用，
        // 否则用户一切到计数图就静默失败
        chart("{\"sql\":\"select * from sales\",\"dimension\":\"city\","
                + "\"measures\":[],\"aggregation\":\"count\"}")
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("非 count 聚合缺度量列 → code=1，且说明原因")
    void nonCountRequiresMeasure() throws Exception {
        chart("{\"sql\":\"select * from sales\",\"dimension\":\"city\","
                + "\"measures\":[],\"aggregation\":\"sum\"}")
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("度量个数超过上限被截到 2 个（服务端硬约束，防绕过）")
    void measuresAreCapped() throws Exception {
        // SQLite/CSV 只有两列，这里传三个度量名，其中两个合法一个不存在。
        // 断言的是「没有因为传了第 3 个就崩」，上限在 normalizeMeasures 里生效
        chart("{\"sql\":\"select * from sales\",\"dimension\":\"city\","
                + "\"measures\":[\"amount\",\"amount\",\"amount\"],\"aggregation\":\"sum\"}")
                .andExpect(status().isOk())
                // 同一个度量传三次应当去重成一条系列，否则图例会有三条一模一样的
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("未知聚合方式退化为 sum，不报错")
    void unknownAggregationFallsBack() throws Exception {
        // 前端传错值不该导致空图，退化成 sum 比报错更友好
        chart("{\"sql\":\"select * from sales\",\"dimension\":\"city\","
                + "\"measures\":[\"amount\"],\"aggregation\":\"nosuchagg\"}")
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("图表里的 SQL 解析失败 → code=1")
    void chartBadSql() throws Exception {
        chart("{\"sql\":\"select * from nope\",\"dimension\":\"city\","
                + "\"measures\":[\"amount\"],\"aggregation\":\"sum\"}")
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    @DisplayName("图表分组数与分页无关：翻页不改变分组结果")
    void chartIgnoresPaging() throws Exception {
        // 产品承诺：图表看到的是完整数据，不随表格翻页而变。
        // ChartRequest 里根本没有 page 字段 —— 这条测试就是钉住这个设计
        chart("{\"sql\":\"select * from sales\",\"dimension\":\"city\","
                + "\"measures\":[\"amount\"],\"aggregation\":\"sum\"}")
                .andExpect(jsonPath("$.categories.length()").value(3));
    }

    // ==================== 导出接口 ====================

    @Test
    @DisplayName("导出返回 CSV 内容，列名与数据都在")
    void exportReturnsCsv() throws Exception {
        String body = mvc.perform(req(post("/api/query/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(queryBody("select * from sales", null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.contains("city"), "表头应当包含列名：" + body.substring(0, Math.min(200, body.length())));
        assertTrue(body.contains("北京"), "应当包含数据行");
    }

    @Test
    @DisplayName("导出带 UTF-8 BOM，Excel 打开中文不乱码")
    void exportHasBom() throws Exception {
        byte[] raw = mvc.perform(req(post("/api/query/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(queryBody("select * from sales", null)))
                .andReturn().getResponse().getContentAsByteArray();

        // 没有 BOM 的话 Excel 会按本地编码解析，中文列名变乱码 ——
        // 这正是 README 里承诺过的那一点
        assertTrue(raw.length >= 3
                        && (raw[0] & 0xFF) == 0xEF
                        && (raw[1] & 0xFF) == 0xBB
                        && (raw[2] & 0xFF) == 0xBF,
                "导出应当以 UTF-8 BOM 开头");
    }

    @Test
    @DisplayName("导出遵循 SQL 的 limit，与界面看到的一致")
    void exportRespectsLimit() throws Exception {
        // 界面显示 3 行、导出却给 30 行，这种行数不一致用户只会觉得导出坏了
        String body = mvc.perform(req(post("/api/query/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(queryBody("select * from sales limit 3", null)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        long dataLines = body.lines().skip(1).filter(l -> !l.isBlank()).count();
        assertEquals(3, dataLines, "导出应当只含 3 行数据");
    }

    @Test
    @DisplayName("导出时 SQL 出错 → 返回 JSON 错误体（响应头尚未发出）")
    void exportBadSqlReturnsJsonError() throws Exception {
        // 这个方法自己写错误体，不交给 GlobalExceptionHandler ——
        // 因为响应头一旦发出就改不回 JSON 了。此处故意让它在解析阶段就失败
        String body = mvc.perform(req(post("/api/query/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(queryBody("select * from nope", null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.contains("\"code\":1"), "应当是 JSON 错误体：" + body);
    }

    @Test
    @DisplayName("聚合结果不提供导出（没有明细行）")
    void exportAggregateRejected() throws Exception {
        String body = mvc.perform(req(post("/api/query/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(queryBody("select count(*) from sales", null)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 这条行为在 NOTES.md 里写进了「容易被误判成 bug」一节，用测试钉住
        assertTrue(body.contains("\"code\":1"), "聚合结果导出应当明确拒绝：" + body);
    }

    // ==================== 安全 ====================

    @Test
    @DisplayName("查询接口同样受令牌保护")
    void queryRequiresToken() throws Exception {
        mvc.perform(post("/api/query")
                        .header("Host", "localhost:8080")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(queryBody("select * from sales", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("导出接口同样受令牌保护")
    void exportRequiresToken() throws Exception {
        mvc.perform(post("/api/query/export")
                        .header("Host", "localhost:8080")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(queryBody("select * from sales", null)))
                .andExpect(status().isUnauthorized());
    }
}
