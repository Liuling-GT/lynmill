package com.tinyquery.export;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link XlsxWriter} 的单元测试。
 *
 * <p>重点验证三件事，它们是「导出的文件 Excel 能不能正常打开」的全部关键：</p>
 *
 * <ul>
 *   <li>ZIP 结构完整 —— 缺任何一个部件 Excel 都会报「文件已损坏」</li>
 *   <li>数字与日期写成原生类型，不是文本</li>
 *   <li>XML 特殊字符被正确转义，控制字符被剔除</li>
 * </ul>
 */
class XlsxWriterTest {

    // 把写出的字节解开，返回 条目名 -> 内容
    private static java.util.Map<String, String> unzip(byte[] data) throws Exception {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }


    // 显式构造 Object[] 的列表。不能用 List.of(new Object[]{...})——
    // 那会被当成 varargs，得到 List<Object> 而不是 List<Object[]>
    private static List<Object[]> rows(Object[]... data) {
        return new ArrayList<>(java.util.Arrays.asList(data));
    }

    private static byte[] write(String[] header, List<Object[]> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter w = new XlsxWriter(out, "数据")) {
            w.writeHeader(header);
            for (Object[] r : rows) {
                w.writeRow(r);
            }
        }
        return out.toByteArray();
    }

    @Test
    void 写出必需的全部部件() throws Exception {
        byte[] data = write(new String[]{"id", "name"}, rows(new Object[]{1, "a"}));
        var parts = unzip(data);

        // 少任何一个，Excel 都会报「文件已损坏」
        for (String required : new String[]{
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml",
                "xl/sharedStrings.xml",
                "xl/styles.xml"}) {
            assertTrue(parts.containsKey(required), "缺少部件: " + required);
        }
    }

    @Test
    void 表头与数据都写进工作表() throws Exception {
        byte[] data = write(new String[]{"编号", "姓名"},
                rows(new Object[]{1, "张三"}, new Object[]{2, "李四"}));
        String sheet = unzip(data).get("xl/worksheets/sheet1.xml");

        assertNotNull(sheet);
        // 表头 + 2 行数据 = 3 个 row 元素
        assertEquals(3, sheet.split("<row ", -1).length - 1, "应有 3 行");
        // 表头走共享字符串表，所以这里看到的是索引而不是文字
        assertTrue(sheet.contains("t=\"s\""), "表头应为共享字符串");
    }

    @Test
    void 数字写成原生类型而非文本() throws Exception {
        byte[] data = write(new String[]{"金额", "数量"},
                rows(new Object[]{1234.5, 7L}));
        String sheet = unzip(data).get("xl/worksheets/sheet1.xml");

        // 数字单元格不带 t="s"，且值直接放在 <v> 里
        assertTrue(sheet.contains("<v>1234.5</v>"), "小数应写成原生数值，实际: " + sheet);
        // 整数不该出现小数点，否则 Excel 里显示成 7.0
        assertTrue(sheet.contains("<v>7</v>"), "整数不应带小数位");
        assertFalse(sheet.contains("<v>7.0</v>"), "整数被写成了 7.0");
    }

    @Test
    void 共享字符串去重() throws Exception {
        // 「北京」出现两次，共享字符串表里只应有一份
        byte[] data = write(new String[]{"城市"},
                rows(new Object[]{"北京"}, new Object[]{"北京"}, new Object[]{"上海"}));
        var parts = unzip(data);
        String sst = parts.get("xl/sharedStrings.xml");

        // 表头「城市」+ 北京 + 上海 = 3 个唯一值。
        // 北京写了两次却只存一份，所以是 3 而不是 4 —— 这正是去重生效的证据
        assertEquals(3, sst.split("<si>", -1).length - 1, "去重后应有 3 个条目（表头+北京+上海）");
        assertEquals(1, sst.split("北京", -1).length - 1, "「北京」应只出现一次");
    }

    @Test
    void XML特殊字符被转义() throws Exception {
        // & < > " ' 五兄弟，以及 XML 不允许的控制字符
        byte[] data = write(new String[]{"text"},
                rows(new Object[]{"a&b<c>d\"e'f"}, new Object[]{"含\u0007控制字符"}));
        String sst = unzip(data).get("xl/sharedStrings.xml");

        assertTrue(sst.contains("a&amp;b&lt;c&gt;d&quot;e&apos;f"), "特殊字符转义不正确: " + sst);
        // \u0007（BEL）是 XML 1.0 禁止的字符，必须剔除，否则 Excel 报文件损坏
        assertFalse(sst.contains("\u0007"), "控制字符未被剔除");
    }

    @Test
    void 表头被加粗且首行冻结() throws Exception {
        byte[] data = write(new String[]{"a"}, rows(new Object[]{1}));
        var parts = unzip(data);

        // 表头单元格引用样式 1
        assertTrue(parts.get("xl/worksheets/sheet1.xml").contains("s=\"1\""), "表头应使用加粗样式");
        // styles.xml 里样式 1 的字体带 <b/>
        String styles = parts.get("xl/styles.xml");
        assertTrue(styles.contains("<font><b/>"), "样式表缺少加粗字体");
        // 冻结窗格
        assertTrue(parts.get("xl/worksheets/sheet1.xml").contains("state=\"frozen\""), "首行未冻结");
    }

    @Test
    void 空单元格被跳过() throws Exception {
        byte[] data = write(new String[]{"a", "b", "c"},
                rows(new Object[]{"x", null, "z"}));
        String sheet = unzip(data).get("xl/worksheets/sheet1.xml");

        // 中间的空值不应产生单元格，否则 xlsx 里会出现无意义的空 c 元素
        assertTrue(sheet.contains("r=\"A2\""), "A2 应有值");
        assertTrue(sheet.contains("r=\"C2\""), "C2 应有值");
        assertFalse(sheet.contains("r=\"B2\""), "B2 为空，不应写出单元格");
    }

    @Test
    void 大量行不会只写出一部分() throws Exception {
        // 验证缓冲区 flush 逻辑：写超过缓冲区阈值（32 KB）后数据仍然完整
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            rows.add(new Object[]{i, "row-" + i});
        }
        byte[] data = write(new String[]{"i", "label"}, rows);
        String sheet = unzip(data).get("xl/worksheets/sheet1.xml");

        assertEquals(5001, sheet.split("<row ", -1).length - 1, "行数不符，缓冲区 flush 有丢数据");
        assertTrue(sheet.contains(">4999<"), "最后一行数据缺失");
        assertTrue(sheet.endsWith("</sheetData></worksheet>"), "工作表未正确闭合");
    }

    @Test
    void 工作表名非法字符被替换() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Excel 不允许 : \ / ? * [ ]，且最长 31 字符
        try (XlsxWriter w = new XlsxWriter(out, "a:b/c?d*e[f]g_hhhhiiiiijjjjjkkkkklllllmmmmm")) {
            w.writeHeader(new String[]{"x"});
        }
        String wb = unzip(out.toByteArray()).get("xl/workbook.xml");

        assertFalse(wb.contains("a:b"), "非法字符未被替换");
        // 名称长度应被截到 31 以内
        int start = wb.indexOf("name=\"") + 6;
        int end = wb.indexOf('"', start);
        assertTrue(end - start <= 31, "工作表名超过 31 字符");
    }

    @Test
    void 未写表头就写数据会报错() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter w = new XlsxWriter(out, "S")) {
            // 表头缺失时列引用无从对齐，必须早失败
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> w.writeRow(new Object[]{1}));
        }
    }
}
