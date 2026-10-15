package com.tinyquery.executor;

import com.tinyquery.datasource.loader.XlsxLoader;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.parser.QueryStatement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 数据源的类型推断回归。
 *
 * <p>这一组测试来自一个真实缺陷：Excel 导入的表在图表功能里选不到度量、
 * 数值维度也不分桶，而同一份数据存成 CSV 却完全正常。根因有两层 ——</p>
 *
 * <ol>
 *   <li>{@code XlsxLoader} 没走共用的 {@code ValueNormalizer}，
 *       Long / Double 原样进了引擎；</li>
 *   <li>{@code QueryEngine.inferType} 只认 Integer / BigDecimal，
 *       遇到 Long / Double 就判成字符串。</li>
 * </ol>
 *
 * <p>之前所有图表相关测试都用 CSV / SQLite，Excel 这条路径从没被端到端走过，
 * 所以一直没暴露。这里直接把 Excel 加载出来的值喂给引擎，钉住类型判定。</p>
 */
@DisplayName("Excel 数据源类型推断")
class XlsxNumericTypeTest {

    /**
     * 手写一个最小 xlsx。不引 POI —— 本项目一贯不引第三方库，
     * 测试里也一样。只放数值与文本两种单元格，够验证类型推断即可。
     */
    private static Path writeXlsx(Path dir, String sheetName, String[] headers,
                                  Object[][] rows) throws IOException {
        Path file = dir.resolve("类型回归.xlsx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                            + "</Types>");

            put(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                            + "</Relationships>");

            put(zip, "xl/_rels/workbook.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                            + "</Relationships>");

            put(zip, "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                            + "<sheets><sheet name=\"" + sheetName + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                            + "</workbook>");

            StringBuilder sheet = new StringBuilder();
            sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                    .append("<sheetData>");

            // 表头行：全部按 inlineStr 写
            sheet.append("<row r=\"1\">");
            for (int c = 0; c < headers.length; c++) {
                sheet.append("<c r=\"").append(colName(c)).append("1\" t=\"inlineStr\"><is><t>")
                        .append(headers[c]).append("</t></is></c>");
            }
            sheet.append("</row>");

            for (int r = 0; r < rows.length; r++) {
                int rowNum = r + 2;
                sheet.append("<row r=\"").append(rowNum).append("\">");
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    String ref = colName(c) + rowNum;
                    // 数值写成 <v> 而无 t 属性 —— 这正是 Excel 存数字的方式，
                    // 也是 XlsxReader 产出 Long / Double 的入口
                    if (v instanceof Number) {
                        sheet.append("<c r=\"").append(ref).append("\"><v>")
                                .append(v).append("</v></c>");
                    } else {
                        sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>")
                                .append(v).append("</t></is></c>");
                    }
                }
                sheet.append("</row>");
            }
            sheet.append("</sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return file;
    }

    private static String colName(int index) {
        return String.valueOf((char) ('A' + index));
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private ColumnStore load(Path file) {
        DataSourceConfig config = new DataSourceConfig("回归", "xlsx",
                java.util.Map.of("path", file.toString()));
        return new XlsxLoader().load(config);
    }

    @Test
    @DisplayName("Excel 的整数列被识别为 number（Long 不能漏判）")
    void integerColumnIsNumeric() throws Exception {
        Path dir = Files.createTempDirectory("tq-xlsx-type");
        try {
            Path file = writeXlsx(dir, "成绩", new String[]{"学号", "数学"},
                    new Object[][]{{2024001, 95}, {2024002, 88}});

            ColumnStore store = load(file);
            QueryEngine engine = new QueryEngine(store);

            // 这是缺陷的核心：Excel 的整数走 Long，旧代码判成 string
            assertEquals("number", typeOf(engine, "数学"),
                    "Excel 整数列必须识别为数值列，否则图表选不到度量");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("Excel 的小数列被识别为 number（Double 不能漏判）")
    void decimalColumnIsNumeric() throws Exception {
        Path dir = Files.createTempDirectory("tq-xlsx-type");
        try {
            Path file = writeXlsx(dir, "成绩", new String[]{"姓名", "平均分"},
                    new Object[][]{{"张三", 92.5}, {"李四", 88.25}});

            ColumnStore store = load(file);
            QueryEngine engine = new QueryEngine(store);
            assertEquals("number", typeOf(engine, "平均分"),
                    "Excel 小数列必须识别为数值列");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("文本列仍然是 string")
    void textColumnStaysString() throws Exception {
        Path dir = Files.createTempDirectory("tq-xlsx-type");
        try {
            Path file = writeXlsx(dir, "成绩", new String[]{"姓名", "班级"},
                    new Object[][]{{"张三", "一班"}, {"李四", "二班"}});

            ColumnStore store = load(file);
            QueryEngine engine = new QueryEngine(store);
            assertEquals("string", typeOf(engine, "姓名"));
            assertEquals("string", typeOf(engine, "班级"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("Excel 数值列可以直接当度量做聚合")
    void numericColumnWorksAsMeasure() throws Exception {
        Path dir = Files.createTempDirectory("tq-xlsx-type");
        try {
            Path file = writeXlsx(dir, "成绩", new String[]{"班级", "数学"},
                    new Object[][]{
                            {"一班", 90}, {"一班", 80},
                            {"二班", 70}, {"二班", 60}});

            ColumnStore store = load(file);
            QueryEngine engine = new QueryEngine(store);

            // 按班级求数学平均分。数值列若被判成文本，聚合会失败或给出文本拼接
            ChartResult result = engine.aggregateForChart(
                    parse("select 班级, 数学 from 成绩"), "班级", List.of("数学"), "avg", 50);
            assertTrue(result.categories().contains("一班"), "应有两组");
            assertEquals(2, result.categories().size());

            // 一班平均 (90+80)/2 = 85
            int i = result.categories().indexOf("一班");
            assertEquals(85.0, result.series().get(0).values().get(i).doubleValue(), 0.001,
                    "Excel 数值列必须能正确求平均");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("Excel 数值维度会分桶，而不是每个值一根柱子")
    void numericDimensionGetsBucketed() throws Exception {
        Path dir = Files.createTempDirectory("tq-xlsx-type");
        try {
            // 24 个各不相同的分数。不分桶会出现 24 根柱子（就是用户看到的现象），
            // 分桶后应聚成少数几段
            Object[][] rows = new Object[24][2];
            for (int i = 0; i < 24; i++) {
                rows[i][0] = 70 + (i * 7) % 31;
                rows[i][1] = 1;
            }
            Path file = writeXlsx(dir, "成绩", new String[]{"数学", "人数"}, rows);

            ColumnStore store = load(file);
            QueryEngine engine = new QueryEngine(store);

            ChartResult result = engine.aggregateForChart(
                    parse("select 数学, 人数 from 成绩"), "数学", List.of("人数"), "sum", 50);
            assertTrue(result.bucketed(),
                    "Excel 数值维度应当分桶；不分桶说明维度被判成了文本列");
            assertTrue(result.categories().size() < 24,
                    "分桶后段数应少于原始值个数，实际 " + result.categories().size());
        } finally {
            deleteRecursively(dir);
        }
    }

    // 取某列的类型。走真实的 executePage 路径，而不是直接调私有方法 ——
    // 这样测的是「接口对外报出来的类型」，与前端看到的一致
    private static String typeOf(QueryEngine engine, String column) {
        QueryResult r = engine.executePage(parse("select * from 成绩"), 1, 1);
        int i = r.columns().indexOf(column);
        return i < 0 ? "(无此列)" : r.columnTypes().get(i);
    }

    private static QueryStatement parse(String sql) {
        return new com.tinyquery.parser.SQLParser().parse(sql);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响断言结果
                }
            });
        }
    }
}
