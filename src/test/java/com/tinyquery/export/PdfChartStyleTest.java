package com.tinyquery.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图表观感同步的测试：界面上选的配色、高亮、双轴、精度，
 * 导出成 PDF 后必须还是那个样子。
 *
 * <p>这一组测试的由来：用户反馈「我选的颜色和高亮显示都没有体现出来，
 * 只截出了选定两个变量的初始状态」。查下来是导出时压根没把这些状态发给后端，
 * 后端只能用写死的配色画。所以这里逐项钉住，避免再把某个字段漏掉。</p>
 */
@DisplayName("图表观感同步")
class PdfChartStyleTest {

    private static InputStream font() {
        InputStream in = PdfChartStyleTest.class.getResourceAsStream("/fonts/LynMillSans-subset.ttf");
        assertNotNull(in, "字体资源缺失");
        return in;
    }

    private static PdfChart.Series s(String name, double... vals) {
        List<Double> l = new ArrayList<>();
        for (double v : vals) {
            l.add(v);
        }
        return new PdfChart.Series(name, l);
    }

    /**
     * 渲染一页，返回解压后的内容流文本（PDF 的绘图指令都在里面）。
     *
     * <p>参数用自定义的函数式接口而不是 {@code Consumer}：绘图会抛 IOException，
     * 而 Consumer 的 accept 不允许抛受检异常，用它就得在每处 lambda 里包 try/catch，
     * 白白淹没测试意图。</p>
     */
    @FunctionalInterface
    private interface DrawAction {
        void run(PdfWriter writer) throws IOException;
    }

    private static String render(DrawAction body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter w = new PdfWriter(font())) {
            body.run(w);
            out.write(w.finish());
        }
        return String.join("\n", streamsOf(out.toByteArray()));
    }

    /** 统计某种颜色在内容流里出现的次数。颜色以 "r g b rg" 形式出现 */
    private static int countColor(String content, float[] rgb) {
        return countColor(content, rgb[0], rgb[1], rgb[2]);
    }

    private static int countColor(String content, float r, float g, float b) {
        // PdfWriter 用 %.3f 输出分量
        String token = String.format("%.3f %.3f %.3f rg", r, g, b);
        int n = 0;
        int i = content.indexOf(token);
        while (i >= 0) {
            n++;
            i = content.indexOf(token, i + 1);
        }
        return n;
    }

    /** 解压 PDF 里的内容流 */
    private static List<String> streamsOf(byte[] pdf) throws Exception {
        List<String> out = new ArrayList<>();
        String text = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<< /Length (\\d+) /Filter /FlateDecode >>\\nstream\\n").matcher(text);
        while (m.find()) {
            int len = Integer.parseInt(m.group(1));
            byte[] raw = java.util.Arrays.copyOfRange(pdf, m.end(), m.end() + len);
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
            out.add(o.toString(java.nio.charset.StandardCharsets.ISO_8859_1).replace("\r\n", "\n"));
        }
        return out;
    }

    // ---------------------------------------------------------- 配色

    @Test
    @DisplayName("界面传的自定义颜色会用在柱子上，不再用写死的默认色")
    void customSeriesColorIsUsed() throws Exception {
        String content = render(w -> {
            PdfChart c = w.chartBlock(300f, "配色");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500"), null, false, List.of(), true, false));
            c.draw("bar", List.of("A", "B"), List.of(s("v", 10, 20)));
        });

        // #ff4500 = (1.0, 0.271, 0.0)
        assertTrue(countColor(content, 1.0f, 0.271f, 0.0f) > 0,
                "应当出现界面指定的 #ff4500");
        // 默认蓝 #5470c6 不该再出现
        assertTrue(countColor(content, 0.33f, 0.44f, 0.78f) == 0,
                "指定了颜色就不该再画默认色");
    }

    @Test
    @DisplayName("两个系列各用各的颜色")
    void twoSeriesUseTheirOwnColors() throws Exception {
        String content = render(w -> {
            PdfChart c = w.chartBlock(300f, "双色");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500", "#7b2cbf"), null, false, List.of(), true, false));
            c.draw("bar", List.of("A", "B"),
                    List.of(s("s1", 10, 20), s("s2", 30, 40)));
        });

        assertTrue(countColor(content, 1.0f, 0.271f, 0.0f) > 0, "系列 1 应为 #ff4500");
        // #7b2cbf = (0.482, 0.173, 0.749)
        assertTrue(countColor(content, 0.482f, 0.173f, 0.749f) > 0, "系列 2 应为 #7b2cbf");
    }

    @Test
    @DisplayName("图例色块与柱子同色 —— 否则图例在骗人")
    void legendMatchesSeriesColor() throws Exception {
        String content = render(w -> {
            PdfChart c = w.chartBlock(300f, "图例");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500", "#7b2cbf"), null, false, List.of(), true, false));
            c.draw("bar", List.of("A", "B"),
                    List.of(s("s1", 10, 20), s("s2", 30, 40)));
        });

        // 图例画了色块，所以每种颜色至少出现 2 次（柱子若干 + 图例 1）
        assertTrue(countColor(content, 1.0f, 0.271f, 0.0f) >= 2, "系列 1 的颜色应同时出现在柱子和图例");
        assertTrue(countColor(content, 0.482f, 0.173f, 0.749f) >= 2, "系列 2 同理");
    }

    @Test
    @DisplayName("颜色解析：支持 #rgb、#rrggbb、rgb()，非法值退回兜底色")
    void parseColorVariants() {
        float[] fallback = {0.5f, 0.5f, 0.5f};

        float[] a = PdfChart.parseColor("#ff4500", fallback);
        assertEquals(1.0f, a[0], 0.01f);
        assertEquals(0.271f, a[1], 0.01f);
        assertEquals(0.0f, a[2], 0.01f);

        // #f40 等价于 #ff4400
        float[] b = PdfChart.parseColor("#f40", fallback);
        assertEquals(1.0f, b[0], 0.01f);
        assertEquals(0.267f, b[1], 0.01f);
        assertEquals(0.0f, b[2], 0.01f);

        float[] c = PdfChart.parseColor("rgb(255, 69, 0)", fallback);
        assertEquals(1.0f, c[0], 0.01f);

        // rgba 的 alpha 被忽略
        float[] d = PdfChart.parseColor("rgba(255, 69, 0, 0.5)", fallback);
        assertEquals(1.0f, d[0], 0.01f);

        // 空值与非法值都退回兜底色，不能抛异常 —— 颜色是次要的，
        // 不该因为它让整张图导不出来
        assertEquals(fallback, PdfChart.parseColor(null, fallback));
        assertEquals(fallback, PdfChart.parseColor("", fallback));
        assertEquals(fallback, PdfChart.parseColor("nonsense", fallback));
        // hsl 不由后端解析：前端会把派生结果算好再发过来
        assertEquals(fallback, PdfChart.parseColor("hsl(210, 50%, 40%)", fallback));
    }

    // ---------------------------------------------------------- 高亮

    @Test
    @DisplayName("高亮模式：被选中的柱子用主色，其余压成灰色")
    void highlightMutesUnselectedBars() throws Exception {
        String content = render(w -> {
            PdfChart c = w.chartBlock(300f, "高亮");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500"), null, true, List.of("B"), true, false));
            c.draw("bar", List.of("A", "B", "C"), List.of(s("v", 10, 20, 30)));
        });

        // B 被高亮 → 主色出现
        assertTrue(countColor(content, 1.0f, 0.271f, 0.0f) > 0, "被高亮的柱子应为 #ff4500");
        // A、C 未被高亮 → 灰色 #c6c6cd
        assertTrue(countColor(content, 0.776f, 0.776f, 0.804f) > 0,
                "未高亮的柱子应为灰色 #c6c6cd");
    }

    @Test
    @DisplayName("高亮模式但一个都没选：所有柱子都是灰的")
    void highlightWithEmptySelectionMutesAll() throws Exception {
        String content = render(w -> {
            PdfChart c = w.chartBlock(300f, "全灰");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500"), null, true, List.of(), true, false));
            c.draw("bar", List.of("A", "B"), List.of(s("v", 10, 20)));
        });

        assertTrue(countColor(content, 0.776f, 0.776f, 0.804f) > 0, "应当全是灰色");
        // 主色一次都不该出现 —— 没有分类被点亮
        assertTrue(countColor(content, 1.0f, 0.271f, 0.0f) == 0,
                "没有分类被高亮时不该出现主色");
    }

    @Test
    @DisplayName("不开高亮模式时不用灰色")
    void noHighlightMeansNoMutedColor() throws Exception {
        String content = render(w -> {
            PdfChart c = w.chartBlock(300f, "不高亮");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500"), null, false, List.of("B"), true, false));
            c.draw("bar", List.of("A", "B"), List.of(s("v", 10, 20)));
        });

        assertTrue(countColor(content, 0.776f, 0.776f, 0.804f) == 0,
                "没开高亮模式就不该出现灰色");
    }

    @Test
    @DisplayName("高亮按分类名匹配，两个系列里同一分类要么都亮要么都灰")
    void highlightAppliesToBothSeries() throws Exception {
        String content = render(w -> {
            PdfChart c = w.chartBlock(300f, "双系列高亮");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500", "#7b2cbf"), null, true, List.of("A"), true, false));
            c.draw("bar", List.of("A", "B"),
                    List.of(s("s1", 10, 20), s("s2", 30, 40)));
        });

        // A 在两个系列里都该是各自的亮色；B 在两个系列里都该是灰色
        assertTrue(countColor(content, 1.0f, 0.271f, 0.0f) > 0, "系列 1 的 A 应为亮色");
        assertTrue(countColor(content, 0.482f, 0.173f, 0.749f) > 0, "系列 2 的 A 也应为亮色");
        assertTrue(countColor(content, 0.776f, 0.776f, 0.804f) > 0, "B 在两个系列里都该是灰色");
    }

    // ---------------------------------------------------------- 饼图扇区

    @Test
    @DisplayName("饼图按分类名覆盖扇区颜色")
    void pieSectorColorByCategoryName() throws Exception {
        String content = render(w -> {
            PdfChart c = w.chartBlock(300f, "饼图");
            // 键是分类名而不是下标 —— 界面上点扇区改色时记的就是分类名
            c.setStyle(new PdfChart.Style(
                    List.of("#5470c6"), Map.of("华东", "#ff4500"), false, List.of(), true, false));
            c.draw("pie", List.of("华东", "华北"),
                    List.of(s("v", 60, 40)));
        });

        assertTrue(countColor(content, 1.0f, 0.271f, 0.0f) > 0,
                "华东应当用指定的 #ff4500（扇区 + 图例至少各一次）");
    }

    @Test
    @DisplayName("饼图没指定颜色时用整排配色的第 0 个")
    void pieUsesFirstPaletteSlot() throws Exception {
        String content = render(w -> {
            PdfChart c = w.chartBlock(300f, "饼图默认");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500", "#7b2cbf", "#00d4ff"), null, false, List.of(), true, false));
            c.draw("pie", List.of("A", "B"), List.of(s("v", 60, 40)));
        });

        // 按位置取模：第 0 个分类用第 0 个颜色，第 1 个分类用第 1 个
        assertTrue(countColor(content, 1.0f, 0.271f, 0.0f) > 0, "A 应用第 0 个颜色");
        assertTrue(countColor(content, 0.482f, 0.173f, 0.749f) > 0, "B 应用第 1 个颜色");
    }

    // ---------------------------------------------------------- 双轴

    @Test
    @DisplayName("不统一刻度时画出右侧轴线，两个系列各按自己的值域铺满")
    void dualAxisDrawsSecondAxis() throws Exception {
        // 系列 2 的量级远小于系列 1。共用一根轴时它会被压成贴底的一条线
        String shared = render(w -> {
            PdfChart c = w.chartBlock(300f, "共用轴");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500", "#7b2cbf"), null, false, List.of(), true, false));
            c.draw("bar", List.of("A", "B"),
                    List.of(s("s1", 60000, 50000), s("s2", 8000, 6000)));
        });

        String dual = render(w -> {
            PdfChart c = w.chartBlock(300f, "双轴");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500", "#7b2cbf"), null, false, List.of(), false, false));
            c.draw("bar", List.of("A", "B"),
                    List.of(s("s1", 60000, 50000), s("s2", 8000, 6000)));
        });

        // 双轴会多画一根右侧轴线（一条竖线），共享轴不会
        int sharedV = countVerticalAxis(shared);
        int dualV = countVerticalAxis(dual);
        assertTrue(dualV > sharedV,
                "双轴应多画一根右侧轴线：共享轴 " + sharedV + " 条，双轴 " + dualV + " 条");
    }

    @Test
    @DisplayName("双轴时小量级系列的柱子高度按自己的值域算，不再被压扁")
    void dualAxisRescalesSecondSeries() throws Exception {
        // 系列 2 全是 8000 左右，系列 1 是 60000 左右。
        // 共用轴时系列 2 的柱子高度约为 8000/60000 = 13%；
        // 双轴时按自己的值域应为接近满高
        String dual = render(w -> {
            PdfChart c = w.chartBlock(400f, "双轴");
            c.setStyle(new PdfChart.Style(
                    List.of("#ff4500", "#7b2cbf"), null, false, List.of(), false, false));
            c.draw("bar", List.of("A", "B"),
                    List.of(s("s1", 60000, 60000), s("s2", 8000, 8000)));
        });

        // 系列 2 的颜色 #7b2cbf 对应的柱子应当明显高于共享轴时的 13%
        float maxH = maxRectHeightWithColor(dual, 0.482f, 0.173f, 0.749f);
        assertTrue(maxH > 200f,
                "系列 2 双轴时应当按自己的值域接近满高（400 点块高下应 > 200），实际 " + maxH);
    }

    // ---------------------------------------------------------- 精度

    @Test
    @DisplayName("默认两位小数，与界面一致")
    void defaultPrecisionIsTwoDecimals() {
        assertEquals("59043.49", PdfChart.formatValue(59043.4873, false));
        assertEquals("92.5", PdfChart.formatValue(92.5, false));
        assertEquals("100", PdfChart.formatValue(100.0, false));
    }

    @Test
    @DisplayName("开启完整精度时原样显示，并去掉浮点噪声")
    void fullPrecisionKeepsDigits() {
        // 这正是用户勾「显示完整精度」想看到的东西
        assertEquals("59043.4873", PdfChart.formatValue(59043.4873, true));
        // 整数仍然不带小数点
        assertEquals("100", PdfChart.formatValue(100.0, true));
        // 尾随零要收敛掉，不显示成 59043.4873000
        assertEquals("12.5", PdfChart.formatValue(12.5000, true));
    }

    @Test
    @DisplayName("完整精度不会输出科学计数法")
    void fullPrecisionAvoidsScientificNotation() {
        String s = PdfChart.formatValue(1000.0, true);
        assertEquals("1000", s, "不该出现 1E+3 这种写法");
        String big = PdfChart.formatValue(1234567.89, true);
        assertTrue(!big.contains("E"), "大数也不该用科学计数法：" + big);
    }

    // ---------------------------------------------------------- 兜底

    @Test
    @DisplayName("界面没传配色时用与界面一致的默认色")
    void fallsBackToDefaultColors() throws Exception {
        String content = render(w -> {
            PdfChart c = w.chartBlock(300f, "默认");
            // 不调用 setStyle
            c.draw("bar", List.of("A", "B"), List.of(s("v", 10, 20)));
        });

        assertTrue(countColor(content, 0.33f, 0.44f, 0.78f) > 0, "应退回默认蓝 #5470c6");
    }

    // ---------------------------------------------------------- 小工具

    // 数一数内容流里有多少条「竖线」。
    // 注意 PDF 的线段是一条指令写完的：`x1 y1 m x2 y2 l S`，不换行 ——
    // 所以不能按行尾匹配，要直接在整串里找这个模式
    private static int countVerticalAxis(String content) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([\\d.-]+) ([\\d.-]+) m ([\\d.-]+) ([\\d.-]+) l").matcher(content);
        int n = 0;
        while (m.find()) {
            if (m.group(1).equals(m.group(3)) && !m.group(2).equals(m.group(4))) {
                n++;
            }
        }
        return n;
    }

    /**
     * 找出指定颜色的矩形里最高的那个。
     *
     * <p>注意 PdfWriter 的 setFill 会连写两条指令：先 {@code r g b rg} 设填充色，
     * 再 {@code 0 0 0 RG} 把描边色重置成黑。所以颜色与 {@code re} 之间隔了一条
     * 描边指令，不能要求紧邻。这里用一个非贪婪的「跳过中间指令」来匹配。</p>
     */
    private static float maxRectHeightWithColor(String content, float r, float g, float b) {
        String token = String.format("%.3f %.3f %.3f rg", r, g, b);
        float best = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(token)
                        // 允许中间夹一条描边色指令，然后是要找的矩形
                        + "(?:\\s+[\\d.]+ [\\d.]+ [\\d.]+ RG)?"
                        + "\\s+([\\d.-]+) ([\\d.-]+) ([\\d.-]+) ([\\d.-]+) re")
                .matcher(content);
        while (m.find()) {
            // 几何上的高度是第 4 个参数；PDF 的 y 轴向上，正高度就是往上画
            best = Math.max(best, Float.parseFloat(m.group(4)));
        }
        return best;
    }
}
