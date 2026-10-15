package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.ConnectionTestResult;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.FieldSpec;
import com.tinyquery.executor.ColumnStore;
import com.tinyquery.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link XlsxLoader} 的对外行为：自检、加载、以及给前端的字段描述。
 */
@DisplayName("XlsxLoader")
class XlsxLoaderTest {

    private final XlsxLoader loader = new XlsxLoader();

    private String savedLocale;

    @BeforeEach
    void pinChinese() {
        savedLocale = Messages.desktopLocale();
        Messages.setDesktopLocale(Messages.ZH);
    }

    @AfterEach
    void restoreLocale() {
        Messages.setDesktopLocale(savedLocale);
    }

    // ==================== 类型描述 ====================

    @Test
    @DisplayName("类型标识是 xlsx，展示名走词表")
    void exposesTypeAndLabel() {
        assertEquals("xlsx", loader.type());
        assertEquals("Excel 文件", loader.label());
    }

    @Test
    @DisplayName("配置项：路径必填、工作表可选；且不给编码选项")
    void declaresFields() {
        List<FieldSpec> fields = loader.fields();

        FieldSpec path = fields.stream().filter(f -> "path".equals(f.name())).findFirst().orElseThrow();
        assertTrue(path.required(), "文件路径必填");
        assertEquals("文件路径", path.label());

        FieldSpec sheet = fields.stream().filter(f -> "sheetName".equals(f.name())).findFirst().orElseThrow();
        assertFalse(sheet.required(), "工作表名可选 —— 留空即第一张表");

        // 刻意没有编码选项：xlsx 内部一律是 UTF-8 的 XML，
        // 不存在 CSV 那种「同一文件可能是 GBK 也可能是 UTF-8」的问题。
        // 给一个永远只有唯一正确答案的选项只会让人困惑
        assertTrue(fields.stream().noneMatch(f -> "encoding".equals(f.name())),
                "不该有编码选项");
    }

    // ==================== 加载 ====================

    @Test
    @DisplayName("加载：表头成为列名，数据行数正确")
    void loadsRows(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1,
                        XlsxFixture.inlineCell("A1", "姓名") + XlsxFixture.inlineCell("B1", "年龄"))
                        + XlsxFixture.row(2,
                        XlsxFixture.inlineCell("A2", "张三") + XlsxFixture.numberCell("B2", "30"))
                        + XlsxFixture.row(3,
                        XlsxFixture.inlineCell("A3", "李四") + XlsxFixture.numberCell("B3", "25")))
                .writeTo(dir.resolve("load.xlsx"));

        ColumnStore store = loader.load(config(file, null));

        assertEquals(List.of("姓名", "年龄"), store.getColumnNames());
        assertEquals(List.of("张三", "李四"), store.getColumns("姓名"));
        // 数值归一成 Integer，与 CSV / MySQL 走同一个 ValueNormalizer。
        // 这里原来断言的是 Long —— 也就是 XlsxReader 的原始产出，
        // 等于把「Excel 的值类型与别处不一致」这个缺陷当成了预期行为。
        // 那个不一致的后果是 Excel 的数值列在图表里被判成文本、数值维度不分桶。
        assertEquals(List.of(30, 25), store.getColumns("年龄"));
        assertEquals(Integer.class, store.getColumns("年龄").get(0).getClass(),
                "Excel 数值列必须归一成 Integer，不能把 Long 泄漏给查询引擎");
    }

    @Test
    @DisplayName("加载：指定工作表名时读的是那张表")
    void loadsNamedSheet(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("甲", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "来源"))
                        + XlsxFixture.row(2, XlsxFixture.inlineCell("A2", "甲表")))
                .sheet("乙", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "来源"))
                        + XlsxFixture.row(2, XlsxFixture.inlineCell("A2", "乙表")))
                .writeTo(dir.resolve("sheets.xlsx"));

        assertEquals(List.of("乙表"), loader.load(config(file, "乙")).getColumns("来源"));
        assertEquals(List.of("甲表"), loader.load(config(file, null)).getColumns("来源"),
                "不指定工作表时应当读第一张");
    }

    @Test
    @DisplayName("加载：空串归一成 null，与 CSV 的处理一致")
    void emptyStringsBecomeNull(@TempDir Path dir) throws IOException {
        // 同一个值在不同数据源里该长得一样。CSV 那边空字段就是 null，
        // xlsx 这边也应当如此
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1,
                        XlsxFixture.inlineCell("A1", "有值") + XlsxFixture.inlineCell("B1", "空值"))
                        + XlsxFixture.row(2,
                        XlsxFixture.inlineCell("A2", "x") + XlsxFixture.inlineCell("B2", "")))
                .writeTo(dir.resolve("empty.xlsx"));

        ColumnStore store = loader.load(config(file, null));
        assertEquals("x", store.getColumns("有值").get(0));
        assertNull(store.getColumns("空值").get(0), "空串应当归一成 null");
    }

    @Test
    @DisplayName("加载：文件不存在时抛异常，不返回空结果")
    void loadThrowsWhenFileMissing(@TempDir Path dir) {
        DataSourceConfig config = config(dir.resolve("不存在.xlsx"), null);

        // 返回空的 ColumnStore 会让注册误报成功，问题被推迟到用户查询时才暴露。
        // 见 DataLoader 接口注释
        assertThrows(RuntimeException.class, () -> loader.load(config));
    }

    @Test
    @DisplayName("加载：工作表名不存在时报错并列出可选项")
    void loadReportsMissingSheet(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("销售", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "x")))
                .sheet("库存", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "y")))
                .writeTo(dir.resolve("missing.xlsx"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> loader.load(config(file, "不存在的表")));
        assertTrue(e.getMessage().contains("销售"), "错误里应列出可用工作表：" + e.getMessage());
    }

    // ==================== 自检 ====================

    @Test
    @DisplayName("自检通过：报出工作表数、列数、行数")
    void testSucceeds(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1,
                        XlsxFixture.inlineCell("A1", "姓名") + XlsxFixture.inlineCell("B1", "年龄"))
                        + XlsxFixture.row(2,
                        XlsxFixture.inlineCell("A2", "张三") + XlsxFixture.numberCell("B2", "30")))
                .writeTo(dir.resolve("ok.xlsx"));

        ConnectionTestResult result = loader.test(config(file, null));

        assertTrue(result.success(), "应当通过：" + result.message());
        // 报出真实行列数，让用户有依据判断「读到的对不对」
        assertTrue(result.message().contains("2"), "提示里应含列数/行数：" + result.message());
    }

    @Test
    @DisplayName("自检失败：文件不存在时说清原因")
    void testFailsWhenMissing(@TempDir Path dir) {
        ConnectionTestResult result = loader.test(config(dir.resolve("没有.xlsx"), null));

        assertFalse(result.success());
        assertTrue(result.message().contains("不存在") || result.message().contains("找不到"),
                "应说明文件不存在：" + result.message());
    }

    @Test
    @DisplayName("自检失败：路径为空时提示必填")
    void testFailsWhenPathBlank() {
        assertFalse(loader.test(config(null, null)).success());
    }

    @Test
    @DisplayName("自检失败：文件为空时提示为空")
    void testFailsWhenEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.xlsx");
        Files.createFile(file);

        assertFalse(loader.test(config(file, null)).success());
    }

    @Test
    @DisplayName("自检失败：.xls 老格式明确说「不支持」，并给出去哪另存")
    void testRejectsLegacyXls(@TempDir Path dir) throws IOException {
        // 用户拿 .xls 来试时最需要知道的是「怎么办」，
        // 而不是一个看不懂的 ZIP 异常
        Path file = dir.resolve("老文件.xls");
        Files.writeString(file, "不是真的 xls，但扩展名是");

        ConnectionTestResult result = loader.test(config(file, null));

        assertFalse(result.success());
        assertTrue(result.message().contains("xlsx") || result.message().contains(".xlsx"),
                "应告诉用户另存为 .xlsx：" + result.message());
    }

    @Test
    @DisplayName("自检失败：扩展名是 .xlsx 但内容不是 ZIP，说明文件本身有问题")
    void testRejectsFakeXlsx(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("假的.xlsx");
        Files.writeString(file, "这根本不是 ZIP");

        ConnectionTestResult result = loader.test(config(file, null));

        assertFalse(result.success());
        // 必须说清「文件不是有效 xlsx」，否则用户会去反复检查路径
        assertTrue(result.message().contains("不是") || result.message().contains("损坏"),
                "应指出文件本身不是有效 xlsx：" + result.message());
    }

    @Test
    @DisplayName("自检失败：工作表名写错时列出全部可选表名")
    void testReportsAvailableSheets(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("一月", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "x")))
                .sheet("二月", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "y")))
                .writeTo(dir.resolve("months.xlsx"));

        ConnectionTestResult result = loader.test(config(file, "三月"));

        assertFalse(result.success());
        assertTrue(result.message().contains("一月") && result.message().contains("二月"),
                "应列出可用工作表：" + result.message());
    }

    @Test
    @DisplayName("自检通过：只有表头没有数据行，也放行但说明没有数据行")
    void testAllowsHeaderOnlyFile(@TempDir Path dir) throws IOException {
        // 用户可能刚建好表还没填内容。此时放行是合理的，
        // 但提示里要说清楚，免得用户以为读漏了
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "列")))
                .writeTo(dir.resolve("headeronly.xlsx"));

        ConnectionTestResult result = loader.test(config(file, null));

        assertTrue(result.success(), "只有表头应当算通过：" + result.message());
    }

    @Test
    @DisplayName("加载：文件不存在时给出「文件不存在」而不是底层 IO 异常")
    void loadReportsMissingFileClearly(@TempDir Path dir) {
        // 注册路径直接调 load()，不经过 test()（见 DataSourceManager.register）。
        // 所以文件级校验必须在 load() 里也走一遍 —— 否则用户看到的是
        // 「Excel 文件无法解析：D:\\x.xlsx（D:\\x.xlsx (系统找不到指定的文件。)）」，
        // 路径出现两次，也没说清该怎么办
        Path missing = dir.resolve("不存在.xlsx");

        RuntimeException e = assertThrows(RuntimeException.class, () -> loader.load(config(missing, null)));

        assertTrue(e.getMessage().contains("不存在"),
                "应当说清文件不存在：" + e.getMessage());
        // 路径不该在一句话里出现两次
        long occurrences = e.getMessage().split(java.util.regex.Pattern.quote(missing.toString()), -1).length - 1;
        assertTrue(occurrences <= 1,
                "路径重复出现了 " + occurrences + " 次：" + e.getMessage());
    }

    @Test
    @DisplayName("加载：.xls 老格式提示「另存为 .xlsx」，不是通用的「不是有效 xlsx」")
    void loadRejectsLegacyXlsWithActionableMessage(@TempDir Path dir) throws IOException {
        // .xls 不是 ZIP，如果不特判就会被通用的「不是有效 xlsx」拦下。
        // 那句话对 .xls 用户没有帮助 —— 他需要知道的是「怎么办」
        Path file = dir.resolve("老表.xls");
        Files.writeString(file, "2003 二进制格式");

        RuntimeException e = assertThrows(RuntimeException.class, () -> loader.load(config(file, null)));

        assertTrue(e.getMessage().contains("另存"),
                "应当告诉用户去另存为 .xlsx：" + e.getMessage());
    }

    // ==================== 文件类型探测 ====================

    @Test
    @DisplayName("looksLikeXlsx：扩展名与内容都要对")
    void detectsXlsxByExtensionAndContent(@TempDir Path dir) throws IOException {
        Path real = XlsxFixture.builder()
                .sheet("S", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "x")))
                .writeTo(dir.resolve("real.xlsx"));

        assertTrue(XlsxLoader.looksLikeXlsx(real.toFile()));

        // 只看扩展名会把改名文件放进来，只看魔数会把任意 ZIP 当成表格
        Path zipNamedXlsx = dir.resolve("zip.xlsx");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipNamedXlsx))) {
            out.putNextEntry(new java.util.zip.ZipEntry("a.txt"));
            out.write("x".getBytes());
            out.closeEntry();
        }
        assertTrue(XlsxLoader.looksLikeXlsx(zipNamedXlsx.toFile()),
                "是合法 ZIP 就认为可能是 —— 真正的结构校验在打开时做");

        Path textNamedXlsx = dir.resolve("text.xlsx");
        Files.writeString(textNamedXlsx, "纯文本");
        assertFalse(XlsxLoader.looksLikeXlsx(textNamedXlsx.toFile()),
                "扩展名对但内容不是 ZIP，不算");

        Path realButXls = dir.resolve("real.xls");
        Files.copy(real, realButXls);
        assertFalse(XlsxLoader.looksLikeXlsx(realButXls.toFile()),
                "内容对但扩展名不是 .xlsx，不算");
    }

    @Test
    @DisplayName("sheetNames 能列出真实文件的工作表")
    void listsSheetNames(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("甲", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "x")))
                .sheet("乙", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "y")))
                .writeTo(dir.resolve("list.xlsx"));

        assertEquals(List.of("甲", "乙"), loader.sheetNames(file.toString()));
    }

    // ==================== 辅助 ====================

    private static DataSourceConfig config(Path path, String sheetName) {
        Map<String, Object> values = new HashMap<>();
        if (path != null) {
            values.put("path", path.toString());
        }
        if (sheetName != null) {
            values.put("sheetName", sheetName);
        }
        return new DataSourceConfig("测试", "xlsx", values);
    }
}
