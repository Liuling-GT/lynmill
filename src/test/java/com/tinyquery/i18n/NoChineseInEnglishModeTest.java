package com.tinyquery.i18n;

import com.tinyquery.datasource.DataSourceManager;
import com.tinyquery.datasource.loader.CSVLoader;
import com.tinyquery.datasource.loader.MySQLLoader;
import com.tinyquery.datasource.loader.PostgreSQLLoader;
import com.tinyquery.datasource.loader.SQLiteLoader;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.datasource.spec.FieldSpec;
import com.tinyquery.parser.SQLParser;
import com.tinyquery.parser.QueryStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「英文模式下不许出现中文」的端到端守门测试。
 *
 * <p>为什么需要这个类：<b>上一轮真的漏了 4 条</b>，而且是靠人眼在界面上发现的。
 * 原来的自查脚本只匹配 {@code throw new XxxException("中文")} 这一种形态，
 * 于是这两类写法从它眼皮底下溜了过去 ——</p>
 *
 * <ul>
 *   <li><b>中文当参数传</b>：{@code requireIdentifier(database, "数据库名")}。
 *       它在中文下看着完全正常，只有切到英文才暴露。</li>
 *   <li><b>拼接串</b>：{@code "error:" + col + "。聚合结果…"}，
 *       不以引号开头，正则锚不上。</li>
 * </ul>
 *
 * <p>所以本类不检查「代码里有没有中文字面量」（那种检查会被注释淹没、
 * 也拦不住拼接），而是<b>真的去调一遍</b>那些会产生用户可见文案的入口，
 * 断言英文模式下拿到的字符串里没有 CJK 字符。这样无论用什么写法实现，
 * 只要结果是中文就会被抓住。</p>
 *
 * <p>允许出现中文的例外只有一处：语言选择器上的「中文」二字本身。</p>
 */
@DisplayName("英文模式下不得出现中文（守门）")
class NoChineseInEnglishModeTest {

    /** CJK 统一表意文字。够用 —— 界面文案不会用扩展区的罕用字 */
    private static final String CJK = ".*[\\u4e00-\\u9fff].*";

    private String savedDesktopLocale;

    /**
     * 本类里的用例大多用 {@code Messages.setCurrent(...)} 模拟「某个请求要什么语言」。
     * 但 {@code setCurrent} 之后线程就处于「请求上下文」状态，而桌面端语言是另一份全局状态 ——
     * 两个都钉住，用例之间才不会互相污染。
     */
    @BeforeEach
    void pinLocale() {
        savedDesktopLocale = Messages.desktopLocale();
        Messages.setDesktopLocale(Messages.ZH);
    }

    @AfterEach
    void tearDown() {
        // clear() 同时清 ThreadLocal 与「处于请求上下文」标记
        Messages.clear();
        Messages.setDesktopLocale(savedDesktopLocale);
    }

    /** 造一个配置，字段随便填，只为触发校验路径 */
    private static DataSourceConfig config(String type, Map<String, Object> props) {
        return new DataSourceConfig("t", type, props);
    }

    private static Map<String, Object> props(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 断言这段英文文案里没有中文 */
    private static void assertNoChinese(String what, String text) {
        if (text == null) {
            return;
        }
        assertFalse(text.matches(CJK),
                what + " 在英文模式下出现了中文：" + text);
    }

    // ==================== 一、数据源字段元数据 ====================

    @Test
    @DisplayName("各 Loader 下发的字段 label / description / placeholder 全为英文")
    void loaderFieldMetadataIsEnglish() {
        Messages.setCurrent(Messages.EN);

        List<com.tinyquery.datasource.spec.DataLoader> loaders = List.of(
                new CSVLoader(), new MySQLLoader(), new PostgreSQLLoader(), new SQLiteLoader());

        for (var loader : loaders) {
            assertNoChinese(loader.type() + " 的 type label", loader.label());

            for (FieldSpec f : loader.fields()) {
                String where = loader.type() + "." + f.name();
                assertNoChinese(where + " 的 label", f.label());
                assertNoChinese(where + " 的 description", f.description());
                assertNoChinese(where + " 的 placeholder", f.placeholder());
                // 选项也会被下发（如 CSV 的编码选项 "自动" / "Auto"）
                for (String opt : f.options()) {
                    assertNoChinese(where + " 的选项", opt);
                }
            }
        }
    }

    @Test
    @DisplayName("中文模式下同一批字段确实还是中文（防止把两边改成一个样）")
    void loaderFieldMetadataIsChineseInChineseMode() {
        Messages.setCurrent(Messages.ZH);
        CSVLoader csv = new CSVLoader();
        assertTrue(csv.fields().stream().anyMatch(f -> f.label().matches(CJK)),
                "中文模式下 CSV 字段 label 应当是中文");
    }

    // ==================== 二、数据源校验报错 ====================

    @Test
    @DisplayName("MySQL 数据库名非法 —— 报错里的字段名也必须是英文")
    void mysqlInvalidDatabaseIsEnglish() {
        Messages.setCurrent(Messages.EN);
        MySQLLoader loader = new MySQLLoader();
        DataSourceConfig cfg = config("mysql", props(
                "host", "localhost", "port", "3306",
                "database", "bad-name!", "table", "t",
                "username", "u", "password", "p"));

        DataSourceException e = assertThrows(DataSourceException.class, () -> loader.load(cfg));
        assertNoChinese("MySQL 数据库名非法", e.getMessage());
        assertTrue(e.getMessage().contains("Database"), e.getMessage());
    }

    @Test
    @DisplayName("PostgreSQL 模式名非法 —— 同上")
    void postgresInvalidSchemaIsEnglish() {
        Messages.setCurrent(Messages.EN);
        PostgreSQLLoader loader = new PostgreSQLLoader();
        DataSourceConfig cfg = config("postgresql", props(
                "host", "localhost", "port", "5432",
                "database", "postgres", "schema", "bad-name!",
                "table", "t", "username", "u", "password", "p"));

        DataSourceException e = assertThrows(DataSourceException.class, () -> loader.load(cfg));
        assertNoChinese("PostgreSQL 模式名非法", e.getMessage());
    }

    @Test
    @DisplayName("表名非法 —— 字段名（表名）也必须是英文")
    void invalidTableNameIsEnglish() {
        Messages.setCurrent(Messages.EN);
        MySQLLoader loader = new MySQLLoader();
        DataSourceConfig cfg = config("mysql", props(
                "host", "localhost", "port", "3306",
                "database", "db", "table", "bad-name!",
                "username", "u", "password", "p"));

        DataSourceException e = assertThrows(DataSourceException.class, () -> loader.load(cfg));
        assertNoChinese("表名非法", e.getMessage());
        assertTrue(e.getMessage().contains("Table Name"), e.getMessage());
    }

    @Test
    @DisplayName("端口非法 / 主机非法 / 密码是掩码 —— 全英文")
    void otherConfigErrorsAreEnglish() {
        Messages.setCurrent(Messages.EN);

        // 端口超范围
        DataSourceException port = assertThrows(DataSourceException.class, () ->
                new MySQLLoader().load(config("mysql", props(
                        "host", "localhost", "port", "99999", "database", "db",
                        "table", "t", "username", "u", "password", "p"))));
        assertNoChinese("端口非法", port.getMessage());

        // 主机非法
        DataSourceException host = assertThrows(DataSourceException.class, () ->
                new MySQLLoader().load(config("mysql", props(
                        "host", "not a host!", "port", "3306", "database", "db",
                        "table", "t", "username", "u", "password", "p"))));
        assertNoChinese("主机非法", host.getMessage());

        // 密码是脱敏占位符
        DataSourceException masked = assertThrows(DataSourceException.class, () ->
                new MySQLLoader().load(config("mysql", props(
                        "host", "localhost", "port", "3306", "database", "db",
                        "table", "t", "username", "u",
                        "password", com.tinyquery.datasource.spec.DataSourceInfo.MASK))));
        assertNoChinese("密码为掩码", masked.getMessage());
    }

    @Test
    @DisplayName("SQLite 文件不存在 —— 报错全英文")
    void sqliteFileNotFoundIsEnglish() {
        Messages.setCurrent(Messages.EN);
        DataSourceException e = assertThrows(DataSourceException.class, () ->
                new SQLiteLoader().load(config("sqlite", props(
                        "path", "Z:\\definitely\\not\\here.db", "table", "t"))));
        assertNoChinese("SQLite 文件不存在", e.getMessage());
    }

    @Test
    @DisplayName("必填项缺失 / 整数非法 / 布尔非法 —— 全英文")
    void configAccessorErrorsAreEnglish() {
        Messages.setCurrent(Messages.EN);

        // 每个非法值都必须真的在 properties 里 —— 键不存在时 getBoolean/getInt
        // 直接返回默认值、不抛异常，断言会变成「没抛」而失败（这次就踩到了）
        DataSourceConfig cfg = config("mysql", props(
                "port", "abc",            // 非整数
                "quoteAware", "maybe"));  // 非布尔

        assertNoChinese("缺少必填项",
                assertThrows(DataSourceException.class, () -> cfg.requireString("nope")).getMessage());
        assertNoChinese("不是合法整数",
                assertThrows(DataSourceException.class, () -> cfg.getInt("port", 3306)).getMessage());
        assertNoChinese("不是合法布尔值",
                assertThrows(DataSourceException.class, () -> cfg.getBoolean("quoteAware", true)).getMessage());
    }

    // ==================== 三、SQL 解析与执行报错 ====================

    @Test
    @DisplayName("各类 SQL 报错在英文模式下全无中文")
    void sqlErrorsAreEnglish() {
        Messages.setCurrent(Messages.EN);
        SQLParser parser = new SQLParser();

        // 这些在纯解析阶段（SQLParser.parse）就会抛
        List<String> parseStage = List.of(
                "select ' from t",                          // 引号未闭合
                "select * from",                            // 缺表名
                "select * from t where",                    // 缺条件
                "select * from t where a like '1%'",        // 不支持的运算符
                "select * from t where a >",                // 缺值
                "select * from t where = 30",               // 缺列名
                "select * from t where a = 1 order by",     // 缺排序列
                "select * from t order by a,",              // 空排序列
                "select * from t limit -1",                 // LIMIT 负数
                "select * from t limit 1, 2",               // 逗号写法

                // 这两条要走到 QueryStatement 的语句级校验才抛，
                // 不在 SQLParser.parse 里 —— 一开始把它们放进这个列表是错的
                "select name, count(*) from t",             // 聚合混用
                "select count(* from t"                     // 缺右括号
        );

        for (String sql : parseStage) {
            Exception e = assertThrows(Exception.class, () -> {
                QueryStatement stmt = parser.parse(sql);
                // 这两道语句级校验平时由 QueryEngine 触发，这里手工跑一遍 ——
                // 它们的报错同样会直接展示给用户，必须一并纳入守门范围
                stmt.validateAggregateSelect();   // 聚合混用在这里抛
                stmt.getAggregations();           // 缺右括号在这里抛
            }, "这条 SQL 本该报错：" + sql);
            assertNoChinese("SQL「" + sql + "」的报错", e.getMessage());
        }
    }

    @Test
    @DisplayName("解析阶段的报错本身不带 error: 前缀")
    void noInternalErrorPrefix() {
        Messages.setCurrent(Messages.EN);
        SQLParser parser = new SQLParser();
        for (String sql : List.of("select ' from t", "select * from")) {
            Exception e = assertThrows(Exception.class, () -> parser.parse(sql));
            assertFalse(e.getMessage().startsWith("error:"),
                    "「error:」是内部约定，不该漏给用户：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("聚合混用的报错包含出错的列名，且是英文 —— 上一轮漏掉的那条")
    void mixedSelectColumnsIsEnglish() {
        Messages.setCurrent(Messages.EN);
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> new SQLParser().parse("select name, count(*) from t"));

        assertNoChinese("聚合混用", e.getMessage());
        // 不能带 "error:" 前缀 —— 那是内部约定，不该漏给用户
        assertFalse(e.getMessage().startsWith("error:"), e.getMessage());
        assertTrue(e.getMessage().contains("name"), e.getMessage());
    }

    // ==================== 四、图表文案 ====================

    /**
     * 造一个两行的表，带一个空维度值。
     *
     * <p>必须走真实的 {@code QueryEngine} 聚合路径 —— 上一版这几条只断言了
     * {@code Messages.get("chart.seriesCount")} 等于 "Count"，看起来很对，
     * 但把 {@code seriesName()} 里的调用改回写死 {@code "计数"} 之后测试**照样绿**：
     * 词表还在，只是没人用它了。这就是典型的「因为错误原因而通过」。
     * 断言必须落在<b>真正产出给前端的那个对象</b>上。</p>
     *
     * <p>维度值刻意用 ASCII（"RD" / 空串）：类别名里混着用户数据，
     * 而<b>数据不该被翻译</b>。若用中文当数据，断言就会把「数据里的中文」
     * 误判成「漏翻」——这个假警报当场就出现过一次。</p>
     */
    private static com.tinyquery.executor.ColumnStore storeWithBlankDimension() {
        com.tinyquery.executor.ColumnStore store = new com.tinyquery.executor.ColumnStore();
        store.addRow(java.util.Map.of("dept", "RD", "salary", 100));
        store.addRow(java.util.Map.of("dept", "", "salary", 200));
        return store;
    }

    @Test
    @DisplayName("图表系列名真的走了词表 —— 产出 Count 而不是「计数」")
    void chartSeriesNameComesFromMessages() {
        Messages.setCurrent(Messages.EN);
        var result = chartInCurrentLocale();

        List<String> names = result.series().stream()
                .map(com.tinyquery.executor.ChartSeries::name).toList();
        for (String n : names) {
            assertNoChinese("图表系列名", n);
        }
        assertTrue(names.contains("Count"),
                "系列名应当来自词表（Count），实际是：" + names);
    }

    @Test
    @DisplayName("图表空值类别真的走了词表 —— 产出 (Empty) 而不是「(空)」")
    void chartEmptyCategoryComesFromMessages() {
        Messages.setCurrent(Messages.EN);
        var result = chartInCurrentLocale();

        for (String c : result.categories()) {
            assertNoChinese("图表类别名", c);
        }
        assertTrue(result.categories().contains("(Empty)"),
                "空值类别应当来自词表（(Empty)），实际是：" + result.categories());
    }

    @Test
    @DisplayName("中文模式下图表文案确实是中文 —— 两侧不是同一份")
    void chartIsChineseInChineseMode() {
        Messages.setCurrent(Messages.ZH);
        var result = chartInCurrentLocale();

        List<String> names = result.series().stream()
                .map(com.tinyquery.executor.ChartSeries::name).toList();
        assertTrue(names.contains("计数"), "中文模式系列名应当是「计数」，实际：" + names);
        assertTrue(result.categories().contains("(空)"),
                "中文模式空值类别应当是「(空)」，实际：" + result.categories());
    }

    /** 走真实的聚合路径，返回真正会下发给前端的那个 ChartResult */
    private static com.tinyquery.executor.ChartResult chartInCurrentLocale() {
        return new com.tinyquery.executor.QueryEngine(storeWithBlankDimension())
                .aggregateForChart(new SQLParser().parse("select * from t"),
                        "dept", List.of("*"), "count", 10);
    }

    @Test
    @DisplayName("图表配色与图例相关文案全英文")
    void chartMiscIsEnglish() {
        Messages.setCurrent(Messages.EN);
        String[] keys = {
                "chart.other", "chart.seriesCount", "chart.emptyCategory",
                "chart.noNumericHint", "chart.hintSeparator",
        };
        for (String k : keys) {
            assertNoChinese(k, Messages.get(k));
        }
    }

    @Test
    @DisplayName("英文文案里不放中文乱码示例 —— 那个例子对英文用户没有意义")
    void encodingDescHasNoChineseSampleInEnglish() {
        Messages.setCurrent(Messages.EN);
        String text = Messages.get("field.encodingDesc");

        assertNoChinese("CSV 编码说明", text);

        // 中文版特意举了「寮犱笁」这个例子（UTF-8 的中文被按 GBK 读的结果），
        // 英文版**刻意不放**。原因：ASCII 英文在 GBK 下解码是无损的，
        // 英文用户几乎不会遇到那类乱码；把一串中文天书塞进英文文案只会更费解。
        // 两边文案因此不是逐字对应，这是有意的。
        assertTrue(text.contains("GBK"), text);
        assertTrue(text.contains("garbled"), text);
    }

    @Test
    @DisplayName("中文文案里保留乱码示例 —— 中文用户确实会碰到")
    void encodingDescKeepsChineseSampleInChinese() {
        Messages.setCurrent(Messages.ZH);
        String text = Messages.get("field.encodingDesc");

        assertTrue(text.contains("寮犱笁"),
                "中文版应当保留乱码示例，它是用户判断「是不是这个问题」的关键线索：" + text);
    }

    // ==================== 五、桌面端（托盘 / 控制窗口 / 启动失败对话框）====================
    //
    // 注意：这里只查**词表本身**。真正「界面代码有没有用上词表」的守门在
    // com.tinyquery.desktop.DesktopEnglishModeTest 里 ——
    // TrayIconInstaller / ControlWindow / StartupFailureDialog 的取值方法是包可见的，
    // 只有 desktop 包里的测试够得着。两边分工：
    //   这边保证「英文词条里没有中文」，那边保证「界面真的取了英文词条」。

    @Test
    @DisplayName("桌面端英文词条本身不含中文")
    void desktopMessagesHaveNoChinese() {
        Messages.setDesktopLocale(Messages.EN);
        try {
            List<String> keys = Messages.allKeys().stream()
                    .filter(key -> key.startsWith("desktop."))
                    .toList();

            assertFalse(keys.isEmpty(), "一条 desktop.* 词条都没有，说明键名被改过");
            for (String key : keys) {
                assertNoChinese(key, Messages.get(key));
            }
        } finally {
            Messages.setDesktopLocale(Messages.ZH);
        }
    }

    @Test
    @DisplayName("桌面端真的读得到语言 —— 不是设了没生效")
    void desktopLocaleActuallyTakesEffect() {
        // 只断言词表值是不够的：词表全对、但代码里硬编码中文一样是 bug。
        // 这里断言的是「同一个键在两种语言下产出不同的值」——
        // 若实现被改回硬编码常量或缓存了首次取值，这条会失败。
        //
        // 更进一步的「驱动真实入口断言产出对象」在 StartupFailureDialogTest
        // 与 TrayIconInstallerTest 里 —— buildMessage/describe 是包可见的，
        // 只有同为 desktop 包的测试才够得着
        Messages.setDesktopLocale(Messages.ZH);
        String zh = Messages.get("desktop.failure.title");
        Messages.setDesktopLocale(Messages.EN);
        String en = Messages.get("desktop.failure.title");

        assertNotEquals(zh, en, "桌面端文案没跟着语言变");
        assertNoChinese("desktop.failure.title", en);
    }

    // ==================== 六、兜底：词表整体扫一遍 ====================

    @Test
    @DisplayName("英文词表整体不含中文（唯一例外：语言名「中文」）")
    void wholeEnglishTableHasNoChinese() {
        Messages.setCurrent(Messages.EN);

        List<String> offenders = new ArrayList<>();
        for (String key : Messages.allKeys()) {
            String text = Messages.get(key);
            // 语言选择器要显示「中文」二字，这是唯一允许的例外
            if ("settings.language.zh".equals(key) || "language.zh".equals(key)) {
                continue;
            }
            if (text != null && text.matches(CJK)) {
                offenders.add(key + " = " + text);
            }
        }
        assertTrue(offenders.isEmpty(),
                "英文词表里有中文（共 " + offenders.size() + " 条）：\n  " + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("中文词表整体确实含中文 —— 防止两侧被改成同一份")
    void wholeChineseTableHasChinese() {
        Messages.setCurrent(Messages.ZH);
        long withChinese = Messages.allKeys().stream()
                .filter(k -> Messages.get(k) != null && Messages.get(k).matches(CJK))
                .count();
        assertTrue(withChinese > 80,
                "中文词表只有 " + withChinese + " 条含中文，疑似被整体替换成了英文");
    }
}
