package com.tinyquery.datasource;

import com.tinyquery.executor.ColumnStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CSVLoader 加载与配置")
class CSVLoaderTest {

    @TempDir
    Path tempDir;

    private final CSVLoader loader = new CSVLoader();

    // 写一个临时 CSV 文件并返回其路径字符串
    private String csv(String content) throws IOException {
        Path file = tempDir.resolve("test.csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toString();
    }

    // 构造 CSV 配置。extra 用于覆盖可选配置项
    private DataSourceConfig config(String path, Map<String, Object> extra) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", path);
        props.putAll(extra);
        return new DataSourceConfig("t", "csv", props);
    }

    // ==================== 基础 ====================

    @Test
    @DisplayName("基本加载：表头作为列名，数字字段归一成 Integer")
    void loadBasic() throws IOException {
        ColumnStore store = loader.load(config(
                csv("name,age\n张三,25\n李四,30\n"), Map.of()));

        assertEquals(List.of("name", "age"), store.getColumnNames());
        assertEquals(2, store.getColumnIndex());
        assertEquals("张三", store.getColumns("name").get(0));
        assertEquals(25, store.getColumns("age").get(0));
    }

    @Test
    @DisplayName("空行被跳过，不产生空记录")
    void loadSkipsBlankLines() throws IOException {
        ColumnStore store = loader.load(config(
                csv("a\n1\n\n2\n\n"), Map.of()));

        assertEquals(2, store.getColumnIndex());
    }

    @Test
    @DisplayName("短行补空串，不发生列错位")
    void loadPadsShortRow() throws IOException {
        // 第二行只有两个字段，表头有三个。若不补齐，c 列会少一个元素，
        // 后续所有行的下标全部串位
        ColumnStore store = loader.load(config(
                csv("a,b,c\n1,2,3\n4,5\n"), Map.of()));

        assertEquals(2, store.getColumnIndex());
        assertEquals(2, store.getColumns("a").size());
        assertEquals(2, store.getColumns("b").size());
        assertEquals(2, store.getColumns("c").size());
        assertEquals("", store.getColumns("c").get(1));
    }

    // ==================== BOM ====================

    @Test
    @DisplayName("UTF-8 BOM 被剥离，首个列名不含不可见字符")
    void loadStripsBom() throws IOException {
        ColumnStore store = loader.load(config(
                csv("\uFEFFname,age\n张三,25\n"), Map.of()));

        assertEquals("name", store.getColumnNames().get(0));
    }

    // ==================== 注释前缀 ====================

    @Test
    @DisplayName("未配置注释前缀时，# 开头的行是数据不是注释")
    void commentDisabledByDefault() throws IOException {
        ColumnStore store = loader.load(config(
                csv("a\n#1\n2\n"), Map.of()));

        // 表头是 a，#1 被当作普通数据
        assertEquals(2, store.getColumnIndex());
        assertEquals("#1", store.getColumns("a").get(0));
    }

    @Test
    @DisplayName("配置多个注释前缀后，注释行在表头识别之前被跳过")
    void commentPrefixesSkipLines() throws IOException {
        ColumnStore store = loader.load(config(
                csv("# 说明\n// 说明\na,b\n-- 说明\n1,2\n"),
                Map.of("commentPrefixes", "#,//,--")));

        assertEquals(List.of("a", "b"), store.getColumnNames());
        assertEquals(1, store.getColumnIndex());
    }

    @Test
    @DisplayName("注释前缀只认行首：行中间出现的 # 不影响该行")
    void commentOnlyMatchesLineStart() throws IOException {
        ColumnStore store = loader.load(config(
                csv("a\nx#y\n"), Map.of("commentPrefixes", "#")));

        assertEquals("x#y", store.getColumns("a").get(0));
    }

    // ==================== 引号感知 ====================

    @Test
    @DisplayName("默认关闭引号感知：引号内的逗号仍会切分")
    void quoteAwareDisabledByDefault() throws IOException {
        ColumnStore store = loader.load(config(
                csv("a\n\"x,y\"\n"), Map.of()));

        // 两个字段： "x 和 y"
        assertEquals(1, store.getColumnIndex());
        assertEquals("x", String.valueOf(store.getColumns("a").get(0)).substring(1));
    }

    @Test
    @DisplayName("开启引号感知：引号内的分隔符不切分")
    void quoteAwareKeepsDelimiterInsideQuotes() throws IOException {
        ColumnStore store = loader.load(config(
                csv("a,b,c\n1,\"x,y\",3\n"), Map.of("quoteAware", true)));

        assertEquals("x,y", store.getColumns("b").get(0));
        assertEquals(3, store.getColumns("c").get(0));
    }

    @Test
    @DisplayName("开启引号感知：连续两个引号表示一个字面引号")
    void quoteAwareHandlesEscapedQuote() throws IOException {
        ColumnStore store = loader.load(config(
                csv("a\n\"say \"\"hi\"\"\"\n"), Map.of("quoteAware", true)));

        assertEquals("say \"hi\"", store.getColumns("a").get(0));
    }

    // ==================== 分隔符 ====================

    @Test
    @DisplayName("分隔符可配置为分号")
    void customDelimiter() throws IOException {
        ColumnStore store = loader.load(config(
                csv("a;b\n1;2\n"), Map.of("delimiter", ";")));

        assertEquals(List.of("a", "b"), store.getColumnNames());
        assertEquals(2, store.getColumns("b").get(0));
    }

    @Test
    @DisplayName("\\t 字面量被识别为制表符")
    void tabDelimiterLiteral() throws IOException {
        ColumnStore store = loader.load(config(
                csv("a\tb\n1\t2\n"), Map.of("delimiter", "\\t")));

        assertEquals(List.of("a", "b"), store.getColumnNames());
    }

    @Test
    @DisplayName("多字符分隔符报错")
    void multiCharDelimiterRejected() throws IOException {
        String path = csv("a,b\n1,2\n");

        assertThrows(DataSourceException.class,
                () -> loader.load(config(path, Map.of("delimiter", ";;"))));
    }

    // ==================== 失败路径 ====================

    @Test
    @DisplayName("文件不存在时抛异常，不返回空 store")
    void missingFileThrows() {
        DataSourceConfig cfg = config(tempDir.resolve("不存在.csv").toString(), Map.of());

        assertThrows(DataSourceException.class, () -> loader.load(cfg));
    }

    @Test
    @DisplayName("缺少 path 配置时抛异常")
    void missingPathThrows() {
        assertThrows(DataSourceException.class,
                () -> loader.load(new DataSourceConfig("t", "csv", Map.of())));
    }

    // ==================== 元数据与自检 ====================

    @Test
    @DisplayName("fields() 声明四个配置项，path 必填")
    void fieldsMetadata() {
        List<FieldSpec> fields = loader.fields();

        assertEquals(4, fields.size());
        assertEquals("path", fields.get(0).name());
        assertTrue(fields.get(0).required());
        assertFalse(fields.get(1).required());
    }

    // ==================== 以下三个需要连接测试功能，没做就删掉 ====================

    @Test
    @DisplayName("test() 对可读文件返回通过并报出列数")
    void testOk() throws IOException {
        ConnectionTestResult result = loader.test(config(
                csv("name,age,city\n张三,25,北京\n"), Map.of()));

        assertTrue(result.success());
        assertTrue(result.message().contains("3 列"));
    }

    @Test
    @DisplayName("test() 对不存在的文件返回失败而不是抛异常")
    void testMissingFile() {
        ConnectionTestResult result = loader.test(
                config(tempDir.resolve("不存在.csv").toString(), Map.of()));

        assertFalse(result.success());
        assertTrue(result.message().contains("文件不存在"));
    }

    @Test
    @DisplayName("test() 跳过注释行后再取表头")
    void testSkipsCommentsWhenFindingHeader() throws IOException {
        ConnectionTestResult result = loader.test(config(
                csv("# 说明\na,b\n1,2\n"), Map.of("commentPrefixes", "#")));

        assertTrue(result.success());
        assertTrue(result.message().contains("2 列"));
    }
}
