package com.tinyquery.export;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PdfWriter} 的单元测试。
 *
 * <p>重点验证 PDF 的<b>结构正确性</b>：阅读器能打开一个结构正确的 PDF，
 * 而结构错了它只会说「文件已损坏」，不会告诉你哪里错。所以这里逐项检查
 * 文件头、对象、交叉引用表、尾部的关键字段。</p>
 */
class PdfWriterTest {

    private static InputStream font() {
        InputStream in = PdfWriterTest.class.getResourceAsStream("/fonts/LynMillSans-subset.ttf");
        assertNotNull(in, "字体资源缺失：src/main/resources/fonts/LynMillSans-subset.ttf");
        return in;
    }

    private static byte[] build(String[] cols, boolean[] numeric, List<String[]> rows,
                                String title, String sub) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter w = new PdfWriter(font())) {
            w.setHeader(title, sub);
            w.writeTable(cols, numeric, rows);
            out.write(w.finish());
        }
        return out.toByteArray();
    }

    private static List<String[]> rows(String[]... r) {
        return new ArrayList<>(java.util.Arrays.asList(r));
    }

    @Test
    void 生成的文件是结构完整的PDF() throws Exception {
        byte[] pdf = build(
                new String[]{"学号", "姓名", "数学"},
                new boolean[]{true, false, true},
                rows(new String[]{"1", "张三", "95.5"}, new String[]{"2", "李四", "78"}),
                "成绩单", "共 2 行 · 2026-10-15");

        String head = new String(pdf, 0, 8, StandardCharsets.ISO_8859_1);
        assertEquals("%PDF-1.4", head, "缺少 PDF 文件头");

        String tail = new String(pdf, Math.max(0, pdf.length - 64), 
                Math.min(64, pdf.length), StandardCharsets.ISO_8859_1);
        assertTrue(tail.contains("%%EOF"), "缺少 %%EOF 结尾，阅读器会认为文件被截断");
        assertTrue(tail.contains("startxref"), "缺少 startxref");

        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("/Type /Catalog"), "缺少 Catalog 对象");
        assertTrue(body.contains("/Type /Pages"), "缺少 Pages 对象");
        assertTrue(body.contains("/Type /Page "), "缺少 Page 对象");
        assertTrue(body.contains("xref"), "缺少交叉引用表");
        assertTrue(body.contains("/Root 1 0 R"), "trailer 未指向 Catalog");
    }

    @Test
    void 中文字体被内嵌且声明了TrueType轮廓() throws Exception {
        byte[] pdf = build(new String[]{"姓名"}, new boolean[]{false},
                rows(new String[]{"张三"}), "标题", "");

        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("/FontFile2"), "缺少 FontFile2，字体没有真正内嵌 — 换台电脑会缺字");
        assertTrue(body.contains("/Subtype /CIDFontType2"), "TrueType 轮廓应声明为 CIDFontType2");
        assertTrue(body.contains("/CIDToGIDMap /Identity"), "CID 必须直接映射到字形编号");
        assertTrue(body.contains("/Encoding /Identity-H"), "缺少 Identity-H 编码");
    }

    @Test
    void 带有ToUnicode表_保证文字可复制可搜索() throws Exception {
        byte[] pdf = build(new String[]{"姓名"}, new boolean[]{false},
                rows(new String[]{"张三"}), "", "");

        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("/ToUnicode"), "缺少 ToUnicode，PDF 里的中文将无法复制与搜索");
        assertTrue(body.contains("beginbfchar"), "ToUnicode 表内容缺失");

        // 「张」的 Unicode 是 U+5F20，应出现在 bfchar 映射里
        assertTrue(body.contains("5F20"), "ToUnicode 里没有「张」的映射");
    }

    @Test
    void 数据量大时自动分页且每页都有内容() throws Exception {
        List<String[]> big = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            big.add(new String[]{String.valueOf(i), "姓名" + i, String.valueOf(i * 1.5)});
        }
        byte[] pdf = build(new String[]{"序号", "姓名", "数值"},
                new boolean[]{true, false, true}, big, "大表", "");

        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        // 300 行不可能装进一页（A4 一页约 60 行），必然分页
        int pageCount = body.split("/Type /Page ", -1).length - 1;
        assertTrue(pageCount > 1, "300 行应分成多页，实际页数=" + pageCount);
        assertTrue(body.contains("/Count " + pageCount), "Pages 的 Count 与实际页数不符");
    }

    @Test
    void 超长文本被截断不溢出() throws Exception {
        String longText = "这是一段非常长的文本".repeat(20);
        byte[] pdf = build(new String[]{"备注"}, new boolean[]{false},
                rows(new String[]{longText}), "", "");

        // 能生成就说明没有死循环；截断逻辑由 fit() 保证不溢出列宽
        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("%%EOF"));
        // 省略号 U+2026 应出现在映射里，说明确实发生了截断
        assertTrue(body.contains("2026"), "超长文本应被截断并产生省略号");
    }

    @Test
    void 分页后每页都重复表头() throws Exception {
        // 表头用别的单元格不会出现的字，这样能在每页内容流里搜到它
        List<String[]> big = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            big.add(new String[]{String.valueOf(i), "数据" + i});
        }
        byte[] pdf = build(new String[]{"编号列", "名称列"},
                new boolean[]{true, false}, big, "", "");

        List<String> streams = pageContentStreams(pdf);
        assertTrue(streams.size() > 1, "200 行应分成多页，实际 " + streams.size());

        // 「编」U+7F16 的字形编号应出现在【每一页】的内容流里。
        // 少了这一步，第二页起就没有列名，翻着看时不知道每列是什么
        String glyph = glyphHexFor(pdf, 0x7F16);
        assertNotNull(glyph, "ToUnicode 里找不到「编」");
        for (int i = 0; i < streams.size(); i++) {
            assertTrue(streams.get(i).contains(glyph),
                    "第 " + (i + 1) + " 页没有表头 —— 分页后未重复表头");
        }
    }

    @Test
    void 表头不会每一行都重复() throws Exception {
        // 这条测试是为了守住一个真实踩过的坑：分页判断写反（!ensureSpace）
        // 会让【每一行】前面都插一次表头，页面内容变成
        // 「表头/一行/表头/一行…」。渲染出来一眼能看出，但只看文本提取发现不了，
        // 所以这里按内容流里表头出现的次数来断言。
        List<String[]> big = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            big.add(new String[]{String.valueOf(i), "数据" + i});
        }
        byte[] pdf = build(new String[]{"编号列", "名称列"},
                new boolean[]{true, false}, big, "", "");

        String glyph = glyphHexFor(pdf, 0x7F16);
        assertNotNull(glyph);

        List<String> streams = pageContentStreams(pdf);
        assertTrue(streams.size() > 1, "应分成多页");

        // 每页里表头【恰好】出现一次
        for (int i = 0; i < streams.size(); i++) {
            int count = countOccurrences(streams.get(i), glyph);
            assertEquals(1, count,
                    "第 " + (i + 1) + " 页里表头出现了 " + count + " 次，应恰好 1 次");
        }
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    /**
     * 取出各页内容流并解压。
     *
     * <p>不能用「找 stream…endstream」的朴素做法：字体数据是二进制，
     * 里面可能恰好出现 {@code endstream} 这几个字节，一找就错位。
     * 必须按对象自己的 {@code /Length} 来切 —— 这也是 PDF 阅读器的做法。</p>
     */
    private static List<String> pageContentStreams(byte[] pdf) throws Exception {
        List<String> out = new ArrayList<>();
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<< /Length (\\d+) /Filter /FlateDecode >>\\nstream\\n")
                .matcher(text);
        while (m.find()) {
            int len = Integer.parseInt(m.group(1));
            int start = m.end();
            byte[] raw = java.util.Arrays.copyOfRange(pdf, start, start + len);
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(raw);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    break;
                }
                o.write(buf, 0, n);
            }
            inf.end();
            out.add(o.toString(StandardCharsets.ISO_8859_1));
        }
        return out;
    }

    /** 在 ToUnicode 的 bfchar 表里找出某个字符对应的字形编号（十六进制）。 */
    private static String glyphHexFor(byte[] pdf, int codePoint) {
        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        String uni = String.format("%04X", codePoint);
        // 形如 <0041> <7F16>
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<([0-9A-F]{4})> <" + uni + ">")
                .matcher(body);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void 空表格也能生成合法文件() throws Exception {
        byte[] pdf = build(new String[]{"a", "b"}, new boolean[]{false, false},
                new ArrayList<>(), "空表", "");

        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(body.startsWith("%PDF-1.4"));
        assertTrue(body.contains("%%EOF"));
    }

    @Test
    void 特殊字符不破坏PDF结构() throws Exception {
        // 括号与反斜杠在 PDF 字符串里有特殊含义；这里文本走十六进制字形编码，
        // 理论上不受影响，但仍要验证不会把文件结构搞坏
        byte[] pdf = build(new String[]{"text"}, new boolean[]{false},
                rows(new String[]{"( ) \\ < > & %"}), "标题 (测试)", "");

        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("%%EOF"), "特殊字符破坏了文件结构");
    }

    @Test
    void 中文标题被写入() throws Exception {
        byte[] pdf = build(new String[]{"列"}, new boolean[]{false},
                rows(new String[]{"值"}), "成绩分析报告", "数据源：成绩单");
        // 「报」U+62A5 、「告」U+544A 应出现在 ToUnicode 映射里
        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("62A5"), "标题中文未进入字体子集");
        assertTrue(body.contains("544A"), "标题中文未进入字体子集");
    }
}
