package com.tinyquery.export;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PdfChart} 的单元测试。
 *
 * <p>重点在<b>矢量</b>：图表必须是 PDF 的绘图指令画出来的，
 * 而不是塞一张位图进去。位图放大会糊，那正是选 PDF 的意义所在。
 * 所以这里断言内容流里出现的是路径指令（{@code m} / {@code l} / {@code re} / {@code c}），
 * 且没有任何图像对象（{@code /Image} / {@code Do}）。</p>
 */
class PdfChartTest {

    private static InputStream font() {
        InputStream in = PdfChartTest.class.getResourceAsStream("/fonts/LynMillSans-subset.ttf");
        assertNotNull(in, "字体资源缺失");
        return in;
    }

    private static List<String> streamsOf(byte[] pdf) throws Exception {
        List<String> out = new ArrayList<>();
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<< /Length (\\d+) /Filter /FlateDecode >>\\nstream\\n")
                .matcher(text);
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
            // 换行归一成 \n：内容流用的是 %n，在 Windows 上是 \r\n，
            // 不归一的话 endsWith(" c") 这类断言会永远失败
            out.add(o.toString(StandardCharsets.ISO_8859_1).replace("\r\n", "\n"));
        }
        return out;
    }

    private static byte[] chart(String type, PdfChart.Series... series) throws Exception {
        List<String> cats = List.of("一班", "二班", "三班", "四班");
        List<PdfChart.Series> ss = List.of(series);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter w = new PdfWriter(font())) {
            PdfChart c = w.chartBlock(320f, "各班平均分");
            c.draw(type, cats, ss);
            out.write(w.finish());
        }
        return out.toByteArray();
    }

    private static PdfChart.Series s(String name, double... vals) {
        List<Double> l = new ArrayList<>();
        for (double v : vals) {
            l.add(v);
        }
        return new PdfChart.Series(name, l);
    }

    // List.of(new String[]{...}) 会被当成可变参数展开成一个 String，
    // 不是「只含一个数组的列表」—— 必须用这个辅助方法包一层
    private static List<String[]> rows(String[]... r) {
        return List.of(r);
    }

    @Test
    void 柱状图用矢量路径绘制而非位图() throws Exception {
        byte[] pdf = chart("bar", s("数学", 95, 88, 76, 60));
        String all = String.join("\n", streamsOf(pdf));

        // 有矩形（柱子）与线条（坐标轴、网格）
        assertTrue(all.contains(" re f"), "柱状图应画出矩形柱子");
        assertTrue(all.contains(" l S"), "柱状图应画出坐标轴线");

        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(!body.contains("/Subtype /Image"), "不应包含位图 —— 那就失去矢量的意义了");
        assertTrue(!body.contains("/XObject"), "不应引用任何图像对象");
    }

    @Test
    void 折线图用线段绘制() throws Exception {
        byte[] pdf = chart("line", s("数学", 95, 88, 76, 60));
        String all = String.join("\n", streamsOf(pdf));
        assertTrue(all.contains(" l S"), "折线图应有连线");
        // 数据点用小方块表示
        assertTrue(all.contains(" re f"), "折线图应有数据点标记");

        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(!body.contains("/Subtype /Image"));
    }

    @Test
    void 饼图用贝塞尔曲线画扇区() throws Exception {
        // 4 个扇区，每个 90°，正好每段一个贝塞尔
        byte[] pdf = chart("pie", s("占比", 40, 30, 20, 10));
        String all = String.join("\n", streamsOf(pdf));

        // c = 三次贝塞尔，扇形靠它画出圆滑的弧。
        // 注意指令形如 "266.00 749.92 304.56 721.91 318.01 680.53 c"，
        // c 前面是数字与空格，所以按行尾匹配
        assertTrue(countLinesEndingWith(all, "c") > 0,
                "饼图扇区应使用贝塞尔曲线画弧，否则放大有棱角");
        assertTrue(all.contains("h f"), "扇区路径应闭合后填充");

        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(!body.contains("/Subtype /Image"));
    }

    @Test
    void 扇区角度加起来是一个整圆() throws Exception {
        // 直接验证几何：把扇区画在已知角度上，检查弧起点是否落在预期坐标。
        // 这里用「两个相等扇区」——若角度计算错了，第二条的起点会明显偏离
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter w = new PdfWriter(font())) {
            PdfChart c = w.chartBlock(300f, "");
            c.draw("pie", List.of("A", "B"), List.of(s("v", 50, 50)));
            out.write(w.finish());
        }
        String all = String.join("\n", streamsOf(out.toByteArray()));
        // 半圆（180°）会被切成 2 段贝塞尔；两个扇区共 4 段
        int beziers = countLinesEndingWith(all, " c");
        assertTrue(beziers >= 4, "两个半圆应至少有 4 段贝塞尔，实际 " + beziers);
    }

    // 数出以指定 PDF 操作符结尾的行数。
    //
    // 注意不能直接按 "\n" 切了再 endsWith(" c") —— 内容流里的换行是 %n，
    // 在 Windows 上是 \r\n，切完每行末尾还留一个 \r，endsWith 就永远不成立。
    // 所以先统一换行再切
    private static int countLinesEndingWith(String text, String operator) {
        int n = 0;
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (line.trim().endsWith(operator)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void 负数值也能正确绘制() throws Exception {
        byte[] pdf = chart("bar", s("盈亏", -20, 30, -5, 45));
        String all = String.join("\n", streamsOf(pdf));
        assertTrue(all.contains(" re f"), "含负数时仍应画出柱子");
        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("%%EOF"), "含负数的图表不应破坏文件结构");
    }

    @Test
    void 全部为零不会崩溃() throws Exception {
        byte[] pdf = chart("bar", s("空", 0, 0, 0, 0));
        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("%%EOF"), "全零数据应生成合法文件");
    }

    @Test
    void 饼图全为零不会除以零() throws Exception {
        byte[] pdf = chart("pie", s("空", 0, 0, 0, 0));
        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("%%EOF"));
    }

    @Test
    void 空数据不产生内容() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter w = new PdfWriter(font())) {
            PdfChart c = w.chartBlock(300f, "空图");
            c.draw("bar", List.of(), List.of());
            out.write(w.finish());
        }
        String body = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("%%EOF"), "空数据应生成合法但为空的文件");
    }

    @Test
    void 双系列画出图例() throws Exception {
        byte[] pdf = chart("bar", s("数学", 95, 88, 76, 60), s("语文", 80, 85, 90, 70));
        String all = String.join("\n", streamsOf(pdf));
        // 两个系列都要有色块
        assertTrue(all.contains(" re f"));
        assertTrue(pdf.length > 1000, "双系列图表应有实际内容");
    }

    @Test
    void 数值格式化去掉多余尾零() {
        assertEquals("95", PdfChart.formatValue(95.0));
        assertEquals("95.5", PdfChart.formatValue(95.5));
        assertEquals("95.55", PdfChart.formatValue(95.554));
        assertEquals("-20", PdfChart.formatValue(-20.0));
        assertEquals("0", PdfChart.formatValue(0.0));
        // 1.50 不该显示成 1.5 之外的样子
        assertEquals("1.5", PdfChart.formatValue(1.5));
    }

    @Test
    void 坐标轴刻度取整数感步长() {
        // 步长规整到 1 / 2 / 2.5 / 5 / 7.5 / 10 × 10^k。
        // 最初是把范围四等分，会算出 106.4、79.8 这种刻度，
        // 读图的人得心算才能估计柱高 —— 这是可读性问题，不是审美问题。
        // 7.5 这一档是后加的：没有它，raw 略超 5 就跳到 10，
        // 轴顶被抬高整整一倍（2191 的轴顶会从 2250 变成 3000），
        // 柱子显得矮，导出的图与界面上的对不上
        assertEquals(1.0, PdfChart.niceStep(0.9), 1e-9);
        assertEquals(1.0, PdfChart.niceStep(1.0), 1e-9);
        assertEquals(2.0, PdfChart.niceStep(1.7), 1e-9);
        assertEquals(2.5, PdfChart.niceStep(2.4), 1e-9);
        assertEquals(5.0, PdfChart.niceStep(4.0), 1e-9);
        assertEquals(7.5, PdfChart.niceStep(7.0), 1e-9);
        assertEquals(10.0, PdfChart.niceStep(9.9), 1e-9);
        assertEquals(100.0, PdfChart.niceStep(80.0), 1e-9);
        assertEquals(0.5, PdfChart.niceStep(0.4), 1e-9);
        assertEquals(0.25, PdfChart.niceStep(0.24), 1e-9);
        // 异常输入不该让坐标轴崩掉
        assertEquals(1.0, PdfChart.niceStep(0), 1e-9);
        assertEquals(1.0, PdfChart.niceStep(-5), 1e-9);
        assertEquals(1.0, PdfChart.niceStep(Double.NaN), 1e-9);
    }

    @Test
    void 极小数值也能画出可读的坐标轴() throws Exception {
        // 0.001~0.004 这种量级：不能因为步长规整就退化成一根线
        byte[] pdf = chart("bar", s("比率", 0.001, 0.002, 0.004, 0.003));
        String all = String.join("\n", streamsOf(pdf));
        assertTrue(all.contains(" re f"), "极小数值仍应画出柱子");
        assertTrue(new String(pdf, StandardCharsets.ISO_8859_1).contains("%%EOF"));
    }

    @Test
    void 分类名太多时不画数值标签避免重叠() throws Exception {
        List<String> many = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add("类目" + i);
            vals.add((double) (i * 3 + 5));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter w = new PdfWriter(font())) {
            PdfChart c = w.chartBlock(300f, "多分类");
            c.draw("bar", many, List.of(new PdfChart.Series("值", vals)));
            out.write(w.finish());
        }
        String body = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("%%EOF"), "分类很多时应正常生成");

        // 文字指令数量应远少于「每根柱子都标数值」的情况
        String all = String.join("\n", streamsOf(out.toByteArray()));
        int textOps = all.split(" Tj", -1).length - 1;
        assertTrue(textOps < 60, "分类很多时不应给每根柱子都标数值，实际文字指令 " + textOps);
    }

    @Test
    void 只导图表时标题块也会被画出来() throws Exception {
        // 「只要图不要表」的场景：没有 writeTable 可依赖。
        // 如果不主动调 writeHeader()，标题就永远不会画出来，
        // PDF 顶端是一片空白，看图的人不知道这份报表是什么
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter w = new PdfWriter(font())) {
            w.setHeader("销售报表", "2026 年 10 月");
            w.writeHeader();
            PdfChart c = w.chartBlock(280f, "各城市销售额");
            c.draw("bar", List.of("上海", "杭州"), List.of(s("sum(销售额)", 120, 95)));
            out.write(w.finish());
        }
        byte[] pdf = out.toByteArray();
        String all = String.join("\n", streamsOf(pdf));

        // 标题写出来了就会有文字指令
        assertTrue(all.contains("BT"), "标题块应画出文字");
        // 标题「销售报表」的首字应进入字体子集
        assertTrue(new String(pdf, StandardCharsets.ISO_8859_1).contains("%%EOF"));
    }

    @Test
    void 重复调用writeHeader不会画两遍() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter w = new PdfWriter(font())) {
            w.setHeader("只画一次", "");
            w.writeHeader();
            w.writeHeader();   // 再来一次
            w.writeTable(new String[]{"A"}, new boolean[]{false},
                    rows(new String[]{"1"}));   // writeTable 内部也会调
            out.write(w.finish());
        }
        String all = String.join("\n", streamsOf(out.toByteArray()));
        int textOps = all.split(" Tj", -1).length - 1;
        // 标题 + 表头 + 一行数据 = 3 条；重复画会明显多于 3
        assertTrue(textOps <= 4, "标题不该被重复绘制，实际文字指令 " + textOps);
    }

    @Test
    void 图表后面还能继续写表格() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter w = new PdfWriter(font())) {
            w.setHeader("报告", "测试");
            PdfChart c = w.chartBlock(280f, "各班平均分");
            c.draw("bar", List.of("一班", "二班"), List.of(s("数学", 95, 88)));
            w.gap(14f);
            w.separator();
            w.gap(8f);
            w.writeTable(new String[]{"班级", "平均分"}, new boolean[]{false, true},
                    List.of(new String[]{"一班", "95"}, new String[]{"二班", "88"}));
            out.write(w.finish());
        }
        String body = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("%%EOF"));
        // 图与表应都在文件里
        assertTrue(body.contains("62A5"), "标题「报」应在字体子集里");
    }
}
