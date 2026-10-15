package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.FieldSpec;
import com.tinyquery.datasource.spec.FieldValueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「探测可选项」能力（{@code DataLoader#probeOptions}）的测试。
 *
 * <p>这是 0-6 的核心：Excel 的工作表名要打开文件才知道，没法写进静态的
 * {@code fields()}，所以改成让前端放个探测按钮、点了再向后端要。
 * 这里覆盖三件事：字段被标记成可探测、探得到正确的工作表名、
 * 以及探不到的时候不把表单搞成报错框。</p>
 */
@DisplayName("数据源字段探测（probeOptions）")
class DataLoaderProbeTest {

    @TempDir
    Path tempDir;

    private final XlsxLoader loader = new XlsxLoader();

    /**
     * 造一个多工作表的 xlsx。
     *
     * <p>手写最简 xlsx 而不是引入 POI：本项目的生产代码就是手写 XML 读写
     * （XlsxReader/XlsxWriter，为了不背 POI 的体积），测试也该守同样的约束。</p>
     */
    private Path writeXlsx(String fileName, String... sheetNames) throws IOException {
        Path file = tempDir.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            StringBuilder ct = new StringBuilder(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
            for (int i = 1; i <= sheetNames.length; i++) {
                ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            }
            ct.append("</Types>");
            write(zip, ct.toString());

            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            write(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");

            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            StringBuilder wb = new StringBuilder(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            for (int i = 0; i < sheetNames.length; i++) {
                wb.append("<sheet name=\"").append(escape(sheetNames[i]))
                        .append("\" sheetId=\"").append(i + 1)
                        .append("\" r:id=\"rId").append(i + 1).append("\"/>");
            }
            wb.append("</sheets></workbook>");
            write(zip, wb.toString());

            zip.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));
            StringBuilder rels = new StringBuilder(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 1; i <= sheetNames.length; i++) {
                rels.append("<Relationship Id=\"rId").append(i)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i).append(".xml\"/>");
            }
            rels.append("</Relationships>");
            write(zip, rels.toString());

            for (int i = 1; i <= sheetNames.length; i++) {
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + i + ".xml"));
                write(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                        + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>列</t></is></c></row></sheetData>"
                        + "</worksheet>");
            }
        }
        return file;
    }

    private static void write(ZipOutputStream zip, String xml) throws IOException {
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    private DataSourceConfig config(String path) {
        return new DataSourceConfig("t", "xlsx", Map.of("path", path));
    }

    // ==================== 字段声明 ====================

    @Test
    @DisplayName("工作表名字段被标记为可探测")
    void sheetNameFieldIsProbeable() {
        FieldSpec sheet = loader.fields().stream()
                .filter(f -> "sheetName".equals(f.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 sheetName 字段"));

        assertTrue(sheet.probeable(), "sheetName 应当是可探测字段，前端据此渲染探测按钮");
        assertFalse(sheet.required(), "工作表名留空即第一张表，不该是必填");
        assertEquals(FieldValueType.STRING, sheet.valueType(),
                "可探测字段平时仍是普通输入框，用户可以手输");
    }

    @Test
    @DisplayName("普通字段不会被误标为可探测")
    void normalFieldsAreNotProbeable() {
        for (FieldSpec f : loader.fields()) {
            if (!"sheetName".equals(f.name())) {
                assertFalse(f.probeable(), "字段 " + f.name() + " 不该是可探测的");
            }
        }
    }

    // ==================== 探测 ====================

    @Test
    @DisplayName("探测出全部工作表名，且保持文件内顺序")
    void probesAllSheetNamesInOrder() throws Exception {
        Path file = writeXlsx("多表.xlsx", "成绩单", "2026春季", "汇总");

        Map<String, List<String>> options = loader.probeOptions(config(file.toString()));

        assertEquals(List.of("成绩单", "2026春季", "汇总"), options.get("sheetName"),
                "顺序必须与文件里一致 —— 用户按第一张表的直觉选「第一个」");
    }

    @Test
    @DisplayName("单工作表文件也能探到一项")
    void probesSingleSheet() throws Exception {
        Path file = writeXlsx("单表.xlsx", "只有一张");
        Map<String, List<String>> options = loader.probeOptions(config(file.toString()));
        assertEquals(List.of("只有一张"), options.get("sheetName"));
    }

    // ==================== 探不到的时候不该报错 ====================

    @Test
    @DisplayName("路径为空返回空 map，而不是抛异常")
    void emptyPathReturnsEmpty() {
        // 用户刚把类型切成 xlsx、还没填路径就点了探测按钮 —— 这是常态不是错误
        Map<String, List<String>> options = loader.probeOptions(config(""));
        assertTrue(options.isEmpty());
    }

    @Test
    @DisplayName("文件不存在返回空 map，而不是抛异常")
    void missingFileReturnsEmpty() {
        Map<String, List<String>> options =
                loader.probeOptions(config(tempDir.resolve("不存在.xlsx").toString()));
        assertTrue(options.isEmpty());
    }

    @Test
    @DisplayName("不是 xlsx 的文件返回空 map，而不是抛异常")
    void nonXlsxReturnsEmpty() throws IOException {
        Path fake = tempDir.resolve("假.xlsx");
        Files.writeString(fake, "a,b\n1,2\n", StandardCharsets.UTF_8);

        Map<String, List<String>> options = loader.probeOptions(config(fake.toString()));
        assertTrue(options.isEmpty(),
                "拿 csv 冒充 xlsx 时探测应当安静失败，错误留给「连接测试」去报");
    }

    @Test
    @DisplayName("配置为 null 不崩溃")
    void nullConfigDoesNotThrow() {
        assertTrue(loader.probeOptions(null).isEmpty());
    }

    @Test
    @DisplayName("探测结果里只有 sheetName 一个键")
    void probeReturnsOnlySheetNameKey() throws Exception {
        Path file = writeXlsx("t.xlsx", "A", "B");
        Map<String, List<String>> options = loader.probeOptions(config(file.toString()));
        assertEquals(1, options.size());
        assertTrue(options.containsKey("sheetName"));
    }
}
