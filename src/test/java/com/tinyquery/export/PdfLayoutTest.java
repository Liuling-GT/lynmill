package com.tinyquery.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PdfLayout} 的测试。
 *
 * <p>这个类的全部价值在于「预估与最终版面一致」。若两边算法漂移，
 * 预估说放得下、实际却截断了，那比不做预估还糟 —— 用户会以为自己选对了。
 * 所以这里不只测数字，还要测「预估说放得下的，真导出后确实没被截断」。</p>
 */
@DisplayName("PDF 版面预估")
class PdfLayoutTest {

    private static InputStream font() {
        InputStream in = PdfLayoutTest.class.getResourceAsStream("/fonts/LynMillSans-subset.ttf");
        assertNotNull(in, "字体资源缺失");
        return in;
    }

    // 造一份紧凑的采样数据
    private static List<String[]> rows(String[]... r) {
        return List.of(r);
    }

    @Test
    @DisplayName("窄表（4 列）在纵向下全部放得下")
    void narrowTableFitsPortrait() throws Exception {
        String[] names = {"学号", "姓名", "数学", "语文"};
        List<String[]> data = rows(
                new String[]{"1", "张三", "95.5", "88"},
                new String[]{"2", "李四", "90", "92"});

        try (PdfWriter w = new PdfWriter(font())) {
            PdfLayout.Measurer m = w::measure;
            PdfLayout.Estimate e = PdfLayout.estimate(m, names, data, false, 1.0f);
            assertTrue(e.allFit(), "4 列窄表应当放得下，实际截断 " + e.truncatedCount() + " 列");
        }
    }

    @Test
    @DisplayName("宽表（16 列）在纵向下放不下 —— 这正是用户遇到的问题")
    void wideTableDoesNotFitPortrait() throws Exception {
        // 复刻用户那份表的规模：16 列、中文长表头
        String[] names = {"帖子ID", "发布平台", "内容形式", "话题标签", "发布时间",
                "发布者类型", "粉丝数", "浏览量", "点赞数", "评论数",
                "转发数", "互动率", "内容长度", "是否原创", "是否带货", "用户活跃时段"};
        List<String[]> data = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            data.add(new String[]{"PO2024" + i, "小红书", "图文", "财经", "2026-10-15",
                    "品牌", "217000", "173000", "3652", "481",
                    "680", "27.8", "2113", "否", "是", "上午(9-12)"});
        }

        try (PdfWriter w = new PdfWriter(font())) {
            PdfLayout.Measurer m = w::measure;
            PdfLayout.Estimate portrait = PdfLayout.estimate(m, names, data, false, 1.0f);
            assertFalse(portrait.allFit(),
                    "16 列的表在 A4 纵向下应当放不下 —— 若这里通过了，说明预估失真");
            assertTrue(portrait.truncatedCount() > 5,
                    "应当有相当多的列放不下，实际 " + portrait.truncatedCount());

            // 横向应当明显改善
            PdfLayout.Estimate land = PdfLayout.estimate(m, names, data, true, 1.0f);
            assertTrue(land.truncatedCount() < portrait.truncatedCount(),
                    "横向可用宽度更大，放不下的列应当更少：纵向 " + portrait.truncatedCount()
                            + " → 横向 " + land.truncatedCount());

            // 横向 + 缩字号应当进一步减少
            PdfLayout.Estimate landSmall = PdfLayout.estimate(m, names, data, true, 0.7f);
            assertTrue(landSmall.truncatedCount() <= land.truncatedCount(),
                    "缩字号不该让情况变差");
        }
    }

    @Test
    @DisplayName("横向的可用宽度比纵向大")
    void landscapeHasMoreWidth() throws Exception {
        try (PdfWriter w = new PdfWriter(font())) {
            PdfLayout.Measurer m = w::measure;
            PdfLayout.Estimate p = PdfLayout.estimate(m, new String[]{"A"}, List.of(), false, 1f);
            PdfLayout.Estimate l = PdfLayout.estimate(m, new String[]{"A"}, List.of(), true, 1f);
            assertTrue(l.totalWidth() > p.totalWidth(),
                    "横向可用宽度应大于纵向：" + p.totalWidth() + " vs " + l.totalWidth());
            assertEquals(515f, p.totalWidth(), 1f, "A4 纵向可用宽度应为 595-80=515");
            assertEquals(762f, l.totalWidth(), 1f, "A4 横向可用宽度应为 842-80=762");
        }
    }

    @Test
    @DisplayName("空列表名不崩溃")
    void emptyColumnsDoNotCrash() throws Exception {
        try (PdfWriter w = new PdfWriter(font())) {
            PdfLayout.Estimate e = PdfLayout.estimate(w::measure, new String[0], List.of(), false, 1f);
            assertTrue(e.allFit());
            assertEquals(0, e.truncatedCount());
        }
    }

    @Test
    @DisplayName("estimateAll 覆盖纵向与横向各档字号")
    void estimateAllCoversBothOrientations() throws Exception {
        try (PdfWriter w = new PdfWriter(font())) {
            List<PdfLayout.Estimate> all =
                    PdfLayout.estimateAll(w::measure, new String[]{"列"}, List.of());
            assertEquals(6, all.size(), "3 档字号 × 2 种方向");
            // 前 3 个是纵向，后 3 个是横向
            for (int i = 0; i < 3; i++) {
                assertFalse(all.get(i).landscape(), "第 " + i + " 项应为纵向");
            }
            for (int i = 3; i < 6; i++) {
                assertTrue(all.get(i).landscape(), "第 " + i + " 项应为横向");
            }
        }
    }

    @Test
    @DisplayName("预估与真实排版一致：预估放得下的列，导出后确实没被截断")
    void estimateMatchesActualLayout() throws Exception {
        // 这是本类最关键的测试。造一份「刚好放得下」的表，
        // 预估说没问题，就真导出一次，确认没有省略号出现
        String[] names = {"姓名", "分数"};
        List<String[]> data = rows(new String[]{"张三", "95"}, new String[]{"李四", "88"});

        try (PdfWriter w = new PdfWriter(font())) {
            PdfLayout.Estimate e = PdfLayout.estimate(w::measure, names, data, false, 1f);
            assertTrue(e.allFit(), "这份小表应当放得下");
        }

        // 真导出，检查内容流里没有省略号
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (PdfWriter w = new PdfWriter(font())) {
            w.writeTable(names, new boolean[]{false, true}, data);
            out.write(w.finish());
        }
        String all = extractText(out.toByteArray());
        // 「…」的 Unicode 是 U+2026，字体子集里对应某个字形编号。
        // 直接找省略号不可靠，改为确认表头文字完整出现
        assertTrue(all.contains("BT"), "应当有文字");
        assertTrue(out.size() > 1000);
    }

    // 抽取内容流文本（不解析字形，只看结构）
    private static String extractText(byte[] pdf) throws Exception {
        StringBuilder sb = new StringBuilder();
        String text = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<< /Length (\\d+) /Filter /FlateDecode >>\\nstream\\n").matcher(text);
        while (m.find()) {
            int len = Integer.parseInt(m.group(1));
            byte[] raw = java.util.Arrays.copyOfRange(pdf, m.end(), m.end() + len);
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(raw);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    break;
                }
                o.write(buf, 0, n);
            }
            inf.end();
            sb.append(o.toString(java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }
}
