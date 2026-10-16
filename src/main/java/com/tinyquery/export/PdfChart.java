package com.tinyquery.export;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一张图表画进 PDF。
 *
 * <p><b>为什么用矢量画，而不是把 PNG 塞进去。</b>PDF 的价值就在于「放大不糊」——
 * 一张报表可能被投到会议室大屏上，也可能被打印成 A3。塞位图进去的话，
 * 放大后就是一堆马赛克，那还不如直接发 PNG。所以这里的柱子、折线、扇区
 * 全部是 PDF 的绘图指令，任意放大都是清晰的。</p>
 *
 * <p>绘制不依赖任何图表库，数据直接来自 {@code /api/query/chart} 的返回
 * （categories + series）。这也符合本项目「不引第三方库」的一贯做法：
 * Apache POI、iText 都因为体积被排除过。</p>
 *
 * <p>支持三种图：柱状、折线、饼图。配色沿用界面上的默认色，
 * 导出的图与屏幕上看到的是同一套观感。</p>
 */
public final class PdfChart {

    /** 画布区域，单位是 PDF 点 */
    private final PdfWriter.Canvas canvas;
    private final float x;
    private final float y;
    private final float width;
    private final float height;

    private final String title;

    // 界面没传配色时的兜底色。正常情况下走不到这里 ——
    // 前端会把派生好的整排颜色发过来，这只是在脚本直接调接口时的保险
    private static final float[][] DEFAULT_PALETTE = {
            {0.33f, 0.44f, 0.78f},   // #5470c6 默认主色
            {0.57f, 0.80f, 0.46f},   // #91cc75 默认副色
    };
    private static final float[] AXIS_COLOR = {0.56f, 0.58f, 0.60f};    // #909399
    private static final float[] GRID_COLOR = {0.92f, 0.93f, 0.96f};    // #ebeef5
    private static final float[] TEXT_COLOR = {0.56f, 0.58f, 0.60f};    // #909399
    private static final float[] LABEL_COLOR = {0.13f, 0.13f, 0.13f};

    private static final float TITLE_SIZE = 13f;
    private static final float AXIS_LABEL_SIZE = 7.5f;
    private static final float VALUE_LABEL_SIZE = 7f;
    private static final float LEGEND_SIZE = 8f;

    /** 数值轴分几格 */
    private static final int TICKS = 4;

    // 图表四周留给坐标轴文字的空间
    private static final float PAD_LEFT = 46f;
    private static final float PAD_RIGHT = 14f;
    private static final float PAD_TOP = 16f;
    private static final float PAD_BOTTOM = 34f;

    /**
     * 图表的观感设置，全部来自界面。
     *
     * <p>做成一个独立对象而不是往构造函数里再加七八个参数：这些值彼此相关
     * （比如高亮色要跟系列色一致），传一坨比传一串更容易看明白，
     * 也方便整体给个默认值。</p>
     *
     * @param seriesColors    每个系列的主色（已从界面直接取来，不是主色派生）
     * @param sectorColors    饼图按分类名覆盖的颜色，可为空
     * @param highlightMode   是否高亮模式
     * @param highlighted     被高亮的分类名
     * @param unifyScale      双系列是否共用一根 Y 轴
     * @param fullPrecision   数据标签是否显示完整精度
     */
    public record Style(List<String> seriesColors,
                        java.util.Map<String, String> sectorColors,
                        boolean highlightMode,
                        List<String> highlighted,
                        boolean unifyScale,
                        boolean fullPrecision,
                        List<List<Double>> axisRanges) {

        /** 界面默认观感：不统一轴、两位小数、不高亮、轴范围自己算 */
        public static Style defaults() {
            return new Style(null, null, false, List.of(), false, false, null);
        }

        /**
         * 不带轴范围的写法，轴范围由后端自己算。
         *
         * <p>留这个重载是因为六个参数的组合已经很常见，而轴范围属于可选的
         * 「所见即所得」增强 —— 不传时行为与从前完全一致。</p>
         */
        public Style(List<String> seriesColors,
                     java.util.Map<String, String> sectorColors,
                     boolean highlightMode,
                     List<String> highlighted,
                     boolean unifyScale,
                     boolean fullPrecision) {
            this(seriesColors, sectorColors, highlightMode, highlighted,
                    unifyScale, fullPrecision, null);
        }

        /**
         * 取第 index 根轴的指定范围，没有则返回 null 由调用方自己算。
         *
         * <p>返回 [min, max] 或 [min, max, ticks]，来自界面上的 ECharts，
         * 见 ChartPdfRequest#axisRangeAt。</p>
         */
        double[] axisRangeAt(int index) {
            if (axisRanges == null || index < 0 || index >= axisRanges.size()) {
                return null;
            }
            List<Double> r = axisRanges.get(index);
            if (r == null || r.size() < 2) {
                return null;
            }
            Double lo = r.get(0);
            Double hi = r.get(1);
            if (lo == null || hi == null || !Double.isFinite(lo) || !Double.isFinite(hi)
                    || hi <= lo) {
                return null;
            }
            double ticks = 0;
            if (r.size() >= 3 && r.get(2) != null && Double.isFinite(r.get(2))) {
                ticks = r.get(2);
            }
            return new double[]{lo, hi, ticks};
        }

        /** 取第 index 个系列的颜色，缺省返回 null 由调用方兜底 */
        String colorOf(int index) {
            if (seriesColors == null || index < 0 || index >= seriesColors.size()) {
                return null;
            }
            String c = seriesColors.get(index);
            return c == null || c.isBlank() ? null : c;
        }

        /** 某个分类是否被高亮 */
        boolean isLit(String category) {
            return highlighted != null && highlighted.contains(category);
        }
    }

    /** 观感设置。默认与界面默认值一致 */
    private Style style = Style.defaults();

    /** 未指定系列色时用的兜底色，与界面默认配色一致 */
    private static final float[] DEFAULT_SERIES_0 = {0.33f, 0.44f, 0.78f};   // #5470c6
    private static final float[] DEFAULT_SERIES_1 = {0.57f, 0.80f, 0.46f};   // #91cc75

    /** 高亮模式下未点亮柱子的灰色 #c6c6cd。刻意用无彩色，与任何主色都不冲突 */
    private static final float[] MUTED_BAR = {0.776f, 0.776f, 0.804f};

    public PdfChart(PdfWriter.Canvas canvas, float x, float y, float width, float height,
                    String title) {
        this.canvas = canvas;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.title = title == null ? "" : title;
    }

    /** 设置观感。不设则用与界面一致的默认值 */
    public void setStyle(Style style) {
        if (style != null) {
            this.style = style;
        }
    }

    /** 取第 index 个系列的颜色：界面给了就用界面的，没给才用兜底色 */
    private float[] seriesColor(int index) {
        float[] fallback = index == 0 ? DEFAULT_SERIES_0 : DEFAULT_SERIES_1;
        return parseColor(style.colorOf(index), fallback);
    }

    /** 画布自身的宽度，供调用方计算外部布局 */
    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    /**
     * 画图。
     *
     * @param type       bar / line / pie，其余值按 bar 处理
     * @param categories 横轴分类（饼图即各扇区名）
     * @param series     系列，每个含名称与数值；饼图只用第一个
     */
    public void draw(String type, List<String> categories, List<Series> series) throws IOException {
        if (categories == null || categories.isEmpty() || series == null || series.isEmpty()) {
            return;
        }

        float top = y + height;   // PDF 的 y 轴向上，所以「顶部」是 y+height

        if (!title.isEmpty()) {
            canvas.text(x, top - TITLE_SIZE, title, TITLE_SIZE, true, LABEL_COLOR);
            top -= TITLE_SIZE + 10f;
        }

        float plotX = x + PAD_LEFT;
        float plotW = width - PAD_LEFT - PAD_RIGHT;
        float plotBottom = y + PAD_BOTTOM;
        float plotTop = top - PAD_TOP;
        float plotH = plotTop - plotBottom;

        if (plotW <= 10f || plotH <= 10f) {
            return;   // 空间太小，画出来也是废的
        }

        if ("pie".equals(type)) {
            drawPie(plotX, plotBottom, plotW, plotH, categories, series.get(0));
        } else if ("line".equals(type)) {
            drawCartesian(plotX, plotBottom, plotW, plotH, categories, series, true);
        } else {
            drawCartesian(plotX, plotBottom, plotW, plotH, categories, series, false);
        }
    }

    // ------------------------------------------------------------ 直角坐标系（柱状 / 折线）

    private void drawCartesian(float px, float py, float pw, float ph,
                               List<String> categories, List<Series> series,
                               boolean line) throws IOException {
        // 双系列且没勾「统一刻度」时，两个系列各用各的 Y 轴 ——
        // 否则把 6 万的浏览量和 8 千的点赞数画在一根轴上，
        // 点赞那排柱子会被压成几乎看不见的一条线，图上等于没有它
        boolean dual = series.size() == 2 && !style.unifyScale();

        // 统一刻度时按两个系列的整体范围取刻度；分开时各自算各自的
        double[] axisMin = new double[2];
        double[] axisMax = new double[2];
        valueRange(dual ? List.of(series.get(0)) : series, 0);
        axisMin[0] = min;
        axisMax[0] = max;
        if (dual) {
            valueRange(List.of(series.get(1)), 1);
            axisMin[1] = min;
            axisMax[1] = max;
        }

        // 刻度用「整数感」的步长（1/2/2.5/5/7.5/10 × 10^k），而不是把范围四等分。
        // 四等分会算出 106.4、79.8 这种刻度，读图的人得心算才能估计柱高 ——
        // 正规图表的坐标轴都是整数刻度，这是可读性问题不是审美问题。
        // 界面传了范围就照用它的格数，这样网格线与屏幕上一一对应
        int ticks = uiTicks > 0 ? uiTicks : 4;
        min = axisMin[0];
        max = axisMax[0];
        for (int i = 0; i <= ticks; i++) {
            float ratio = (float) i / ticks;
            float gy = py + ph * ratio;
            canvas.line(px, gy, px + pw, gy, 0.4f, GRID_COLOR);
            String label = formatValue(min + (max - min) * ratio, style.fullPrecision());
            float tw = canvas.textWidth(label, AXIS_LABEL_SIZE);
            canvas.text(px - 5f - tw, gy - AXIS_LABEL_SIZE * 0.35f, label,
                    AXIS_LABEL_SIZE, false, TEXT_COLOR);
        }

        // 第二根轴的刻度标在右边。数值范围不同，所以单独画一列
        if (dual) {
            for (int i = 0; i <= ticks; i++) {
                float ratio = (float) i / ticks;
                float gy = py + ph * ratio;
                String label = formatValue(axisMin[1] + (axisMax[1] - axisMin[1]) * ratio, style.fullPrecision());
                canvas.text(px + pw + 5f, gy - AXIS_LABEL_SIZE * 0.35f, label,
                        AXIS_LABEL_SIZE, false, TEXT_COLOR);
            }
        }

        // 坐标轴
        canvas.line(px, py, px, py + ph, 0.6f, AXIS_COLOR);
        canvas.line(px, py, px + pw, py, 0.6f, AXIS_COLOR);
        if (dual) {
            // 右侧轴线。双轴没有这根线，读图的人不知道右边那列数字属于谁
            canvas.line(px + pw, py, px + pw, py + ph, 0.6f, AXIS_COLOR);
        }

        int n = categories.size();
        float slot = pw / n;

        if (line) {
            drawLines(px, py, pw, ph, slot, series, axisMin, axisMax, dual);
        } else {
            drawBars(px, py, pw, ph, slot, series, categories, axisMin, axisMax, dual);
        }

        // 横轴分类名。太密就隔一个画一个，避免文字互相压在一起
        int step = labelStep(n, slot);
        for (int i = 0; i < n; i++) {
            if (i % step != 0) {
                continue;
            }
            String name = truncate(categories.get(i), slot * step - 2f, AXIS_LABEL_SIZE);
            float tw = canvas.textWidth(name, AXIS_LABEL_SIZE);
            canvas.text(px + slot * i + (slot - tw) / 2f, py - 12f, name,
                    AXIS_LABEL_SIZE, false, TEXT_COLOR);
        }

        drawLegend(px, py + ph + 4f, series);
    }

    private void drawBars(float px, float py, float pw, float ph, float slot,
                          List<Series> series, List<String> categories,
                          double[] axisMin, double[] axisMax, boolean dual) throws IOException {
        int seriesCount = series.size();
        // 柱宽：一个槽位里放 n 个系列，柱与柱之间留 30% 间隙
        float groupW = slot * 0.62f;
        float barW = groupW / seriesCount;

        // 高亮模式下未点亮的柱子压成灰色，让被选中的几根跳出来。
        // 这是界面上「高亮模式」的核心效果，导出时必须还原 ——
        // 否则用户精心挑出来的重点在 PDF 里就消失了
        boolean lit = style.highlightMode();

        for (int s = 0; s < seriesCount; s++) {
            Series se = series.get(s);
            float[] color = seriesColor(s);
            // 双轴时每个系列用自己的值域换算高度 —— 这正是「分开轴」的意义
            double lo = dual ? axisMin[s] : axisMin[0];
            double hi = dual ? axisMax[s] : axisMax[0];
            double span = hi - lo;

            for (int i = 0; i < se.values().size() && i < slotCount(pw, slot); i++) {
                Double v = se.values().get(i);
                if (v == null) {
                    continue;
                }
                // 高亮模式下，没被选中的分类一律用灰色。
                // 判断按【分类名】做，两个系列里同一个分类要么都亮要么都灰 ——
                // 与界面上的行为一致
                float[] use = color;
                if (lit) {
                    String name = i < categories.size() ? categories.get(i) : "";
                    use = style.isLit(name) ? color : MUTED_BAR;
                }

                float ratio = span == 0 ? 0f : (float) ((v - lo) / span);
                float bh = Math.max(0.6f, ph * ratio);
                float bx = px + slot * i + (slot - groupW) / 2f + barW * s;
                canvas.rect(bx, py, barW * 0.88f, bh, use);

                // 柱子数量不多时标上数值 —— 太多就糊成一团了
                if (slotCount(pw, slot) <= 12) {
                    String label = formatValue(v, style.fullPrecision());
                    float tw = canvas.textWidth(label, VALUE_LABEL_SIZE);
                    canvas.text(bx + (barW * 0.88f - tw) / 2f, py + bh + 3f, label,
                            VALUE_LABEL_SIZE, false, LABEL_COLOR);
                }
            }
        }

        // 坐标轴线最后画一遍，压在柱子上，避免柱子盖住轴线显得没对齐
        canvas.line(px, py, px + pw, py, 0.6f, AXIS_COLOR);
    }

    private void drawLines(float px, float py, float pw, float ph, float slot,
                           List<Series> series, double[] axisMin, double[] axisMax,
                           boolean dual) throws IOException {
        for (int s = 0; s < series.size(); s++) {
            Series se = series.get(s);
            float[] color = seriesColor(s);
            // 双轴时每条线按自己的值域铺满高度，否则低量级的线会贴着底边
            double lo = dual ? axisMin[s] : axisMin[0];
            double hi = dual ? axisMax[s] : axisMax[0];
            double span = hi - lo;

            float prevX = 0;
            float prevY = 0;
            boolean has = false;

            for (int i = 0; i < se.values().size(); i++) {
                Double v = se.values().get(i);
                if (v == null) {
                    has = false;
                    continue;
                }
                float ratio = span == 0 ? 0f : (float) ((v - lo) / span);
                float cx = px + slot * i + slot / 2f;
                float cy = py + ph * ratio;

                if (has) {
                    canvas.line(prevX, prevY, cx, cy, 1.6f, color);
                }
                // 数据点：用一个实心小方块表示，比圆好画且缩小时更清晰
                canvas.rect(cx - 1.5f, cy - 1.5f, 3f, 3f, color);
                prevX = cx;
                prevY = cy;
                has = true;
            }
        }
    }

    // ------------------------------------------------------------ 饼图

    private void drawPie(float px, float py, float pw, float ph,
                         List<String> categories, Series series) throws IOException {
        int n = Math.min(categories.size(), series.values().size());

        double total = 0;
        for (int i = 0; i < n; i++) {
            Double v = series.values().get(i);
            if (v != null && v > 0) {
                total += v;
            }
        }
        if (total <= 0) {
            return;
        }

        // 饼图放左半边，右半边留作图例 —— 中文分类名较长，横排图例容易排不下
        float cx = px + pw * 0.30f;
        float cy = py + ph * 0.5f;
        float radius = Math.min(pw * 0.28f, ph * 0.46f) - 4f;
        if (radius <= 4f) {
            return;
        }

        double angle = Math.PI / 2;   // 从 12 点方向开始，顺时针，与 ECharts 默认一致
        for (int i = 0; i < n; i++) {
            Double v = series.values().get(i);
            if (v == null || v <= 0) {
                continue;
            }
            double sweep = v / total * Math.PI * 2;
            float[] color = sectorColor(categories, i, n);
            // 一个扇区 = 圆心 + 弧。用贝塞尔近似弧，避免多条短直线看起来有棱角
            double end = angle - sweep;
            canvas.pieSlice(cx, cy, radius, angle, end, color);
            angle = end;
        }

        // 图例：色块 + 分类名 + 占比
        float lx = px + pw * 0.58f;
        float ly = py + ph * 0.5f + (n * (LEGEND_SIZE + 5f)) / 2f;
        for (int i = 0; i < n; i++) {
            Double v = series.values().get(i);
            if (v == null) {
                continue;
            }
            canvas.rect(lx, ly - LEGEND_SIZE * 0.8f, 7f, 7f, sectorColor(categories, i, n));
            String name = categories.get(i);
            String pct = String.format("%.1f%%", v / total * 100);
            String text = name + "  " + pct;
            canvas.text(lx + 11f, ly - LEGEND_SIZE * 0.75f,
                    truncate(text, pw - (lx - px) - 6f, LEGEND_SIZE),
                    LEGEND_SIZE, false, LABEL_COLOR);
            ly -= LEGEND_SIZE + 5f;
        }
    }

    /**
     * 第 index 个扇区的颜色。
     *
     * <p>优先用界面上单独改过的颜色，键是【分类名】而不是下标 ——
     * 界面上点某个扇区改颜色时记的就是分类名，用下标对不上。</p>
     */
    private float[] sectorColor(List<String> categories, int index, int total) {
        // 界面单独指定过这个分类的颜色
        if (style.sectorColors() != null && index < categories.size()) {
            String custom = style.sectorColors().get(categories.get(index));
            if (custom != null && !custom.isBlank()) {
                return parseColor(custom, fallbackSector(index, total));
            }
        }

        // 饼图整排用系列 0 的颜色，按位置取模样，与界面 paletteOf(0)[i % 10] 一致
        List<String> row = style.seriesColors();
        if (row != null && !row.isEmpty()) {
            String c = row.get(index % row.size());
            return parseColor(c, fallbackSector(index, total));
        }

        return fallbackSector(index, total);
    }

    // 界面没给颜色时的兜底：用黄金角跳色，扇区多了也不会撞色
    private float[] fallbackSector(int index, int total) {
        if (total <= DEFAULT_PALETTE.length) {
            return DEFAULT_PALETTE[index % DEFAULT_PALETTE.length];
        }
        double hue = (index * 137.508) % 360.0;
        return hsv(hue, 0.55f, 0.85f);
    }

    // HSV → RGB。只需 0.55 饱和度、0.85 明度这一档，够用且不刺眼
    private static float[] hsv(double h, float s, float v) {
        float c = v * s;
        float hp = (float) (h / 60.0);
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r = 0;
        float g = 0;
        float b = 0;
        if (hp < 1) {
            r = c;
            g = x;
        } else if (hp < 2) {
            r = x;
            g = c;
        } else if (hp < 3) {
            g = c;
            b = x;
        } else if (hp < 4) {
            g = x;
            b = c;
        } else if (hp < 5) {
            r = x;
            b = c;
        } else {
            r = c;
            b = x;
        }
        float m = v - c;
        return new float[]{r + m, g + m, b + m};
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 把界面传来的颜色字符串转成 0~1 的 RGB。
     *
     * <p>支持 {@code #rgb}、{@code #rrggbb}、{@code rgb(r,g,b)} 三种写法 ——
     * 界面上的取色器给的是十六进制，但派生出来的整排颜色是 {@code hsl(...)}，
     * 而 hsl 那部分【不由后端解析】：前端会把派生好的结果整排发过来，
     * 所以这里只需要处理十六进制与 rgb。</p>
     *
     * <p>解析不了就退回兜底色。宁可颜色不对也不要因为一个颜色字符串
     * 让整张图导不出来 —— 用户要的是数据，颜色是次要的。</p>
     */
    static float[] parseColor(String text, float[] fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String s = text.trim();

        try {
            // rgb(r, g, b) / rgba(r, g, b, a)：alpha 忽略，导出到 PDF 的都是不透明色
            if (s.regionMatches(true, 0, "rgb", 0, 3)) {
                int lp = s.indexOf('(');
                int rp = s.lastIndexOf(')');
                if (lp < 0 || rp <= lp) {
                    return fallback;
                }
                String[] parts = s.substring(lp + 1, rp).split("[,/\\s]+");
                if (parts.length < 3) {
                    return fallback;
                }
                return new float[]{
                        clamp01(Float.parseFloat(parts[0].trim()) / 255f),
                        clamp01(Float.parseFloat(parts[1].trim()) / 255f),
                        clamp01(Float.parseFloat(parts[2].trim()) / 255f)};
            }

            if (s.startsWith("#")) {
                String hex = s.substring(1);
                if (hex.length() == 3) {
                    hex = "" + hex.charAt(0) + hex.charAt(0)
                            + hex.charAt(1) + hex.charAt(1)
                            + hex.charAt(2) + hex.charAt(2);
                }
                if (hex.length() < 6) {
                    return fallback;
                }
                return new float[]{
                        Integer.parseInt(hex.substring(0, 2), 16) / 255f,
                        Integer.parseInt(hex.substring(2, 4), 16) / 255f,
                        Integer.parseInt(hex.substring(4, 6), 16) / 255f};
            }
        } catch (RuntimeException e) {
            return fallback;
        }

        // hsl(...) 等其它写法不解析：前端本来就会把派生结果算好再发过来
        return fallback;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // 数值轴范围。上下两端都取「整数感」的刻度，
    // 让网格线上的数字是 0 / 50 / 100 / 150 / 200 这种，而不是 106.4 / 79.8
    private double min = 0;
    private double max = 1;

    /**
     * 定轴范围。优先用界面传过来的，没有才自己算。
     *
     * @param series 这根轴要画的系列
     * @param axisIndex 轴下标，用来取界面对应的那根轴的范围
     */
    private void valueRange(List<Series> series, int axisIndex) {
        double[] fromUi = style.axisRangeAt(axisIndex);
        if (fromUi != null) {
            min = fromUi[0];
            max = fromUi[1];
            // 第三项是界面上的格数。轴范围相同、格数不同时网格线位置仍不一样，
            // 所以连它一起照用；缺省 4，与下面自己算时的默认保持一致
            uiTicks = fromUi.length >= 3 ? (int) Math.round(fromUi[2]) : 4;
            return;
        }
        uiTicks = 0;   // 0 表示没有界面值，用默认格数
        valueRange(series);
    }

    /** 界面传来的格数；0 表示没有，走自己的默认值 */
    private int uiTicks = 0;

    private void valueRange(List<Series> series) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        boolean any = false;

        for (Series s : series) {
            for (Double v : s.values()) {
                if (v == null) {
                    continue;
                }
                any = true;
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }

        if (!any) {
            min = 0;
            max = 1;
            return;
        }

        // 数据全为正时从 0 起，柱子才有「长度」的含义；有负数才下探
        double base = lo >= 0 ? 0 : lo;
        if (base == 0 && hi == 0) {
            min = 0;
            max = 1;
            return;
        }

        /*
          选步长。

          目标不是「步长好看」而是「轴顶贴近数据」：最高柱子上方留白太多，
          整张图就显得空、柱子显矮，而用户对照的是界面上 ECharts 画的那张 ——
          留白比例不一样，看上去就是两张不同的图。

          做法是把几个候选步长都试一遍，算出各自的轴顶与格数，
          再按「留白越少越好、格数别太密也别太疏」挑一个。
          早先只按 max/TICKS 取一次再向上取整，会明显偏大：
          数据最大 16000 时算出 5000 一格、轴顶 20000，白空 25%。
        */
        double step = chooseStep(base, hi);

        double lo2 = base >= 0 ? 0 : Math.floor(base / step) * step;
        double hi2 = Math.ceil(hi / step) * step;

        // 上界和下界重合（全零或全同值）时给一格的余量
        if (hi2 - lo2 < step) {
            hi2 = lo2 + step;
        }
        // 顶端留半格，最高的柱子不至于顶到边框
        if (hi2 == hi) {
            hi2 += step;
        }

        min = lo2;
        max = hi2;
    }

    /**
     * 在候选步长里挑一个让轴顶最贴近数据的。
     *
     * <p>评价标准就一条：轴顶高出数据多少。留白越少柱子越高、图越饱满；
     * 但格数太少（比如只有 2 格）读起来又费劲，所以给格数一个下限。</p>
     */
    private static double chooseStep(double base, double hi) {
        if (hi <= base) {
            return 1;
        }
        // 候选：以 max/TICKS 为中心，往细里多试几档
        double center = (hi - base) / TICKS;
        double exp = Math.floor(Math.log10(center));
        double pow = Math.pow(10, exp);
        double[] fracs = {1, 2, 2.5, 5, 7.5, 10, 20};

        double best = niceStep(center);
        double bestWaste = Double.MAX_VALUE;
        for (double frac : fracs) {
            double step = frac * pow;
            if (step <= 0) {
                continue;
            }
            double top = Math.ceil(hi / step) * step;
            int ticks = (int) Math.round((top - (base >= 0 ? 0 : Math.floor(base / step) * step)) / step);
            // 格数太少读图费劲；太多则刻度数字会挤在一起
            if (ticks < 3 || ticks > 8) {
                continue;
            }
            double waste = (top - hi) / hi;
            if (waste < bestWaste) {
                bestWaste = waste;
                best = step;
            }
        }
        return best;
    }

    /**
     * 把任意步长规整到人习惯的数字上。
     *
     * <p>候选是 1 / 2 / 2.5 / 5 / 10 × 10^k，取<b>不小于 raw 的最小候选</b>。</p>
     *
     * <p><b>为什么要取「不小于」而不是「最接近」。</b>早先的实现是按区间落到
     * 1、2、2.5、5、10 这几个挡位，于是 raw 只要略微超过 5 就跳到 10：
     * 数据最大 2191、目标 4 格时 rawStep = 547.75，本该用 750 一格
     * （4 格正好到 3000 以内、刻度是 750/1500/2250），却因为
     * 547.75 落在 500 与 1000 之间被判成 1000，轴顶直接抬到 3000 ——
     * 最高的柱子只占了轴高的三分之二，与屏幕上 ECharts 画出来的不一样，
     * 用户会觉得「导出的图跟原图不是一个」。</p>
     *
     * <p>取不小于 raw 的最小候选，步长只会略大一点，轴顶贴近数据，
     * 与 ECharts 的表现一致。</p>
     */
    static double niceStep(double raw) {
        if (raw <= 0 || Double.isNaN(raw) || Double.isInfinite(raw)) {
            return 1;
        }
        double exp = Math.floor(Math.log10(raw));
        double pow = Math.pow(10, exp);
        double frac = raw / pow;   // [1, 10)

        // 候选挡位。1 与 10 是两端，中间是常用的细分。
        // 7.5 这一档不是凑数：没有它，raw 只要略超 5 就跳到 10，
        // 轴顶被抬高整整一倍，柱子显得矮，与屏幕上的图对不上
        double nice;
        if (frac <= 1) {
            nice = 1;
        } else if (frac <= 2) {
            nice = 2;
        } else if (frac <= 2.5) {
            nice = 2.5;
        } else if (frac <= 5) {
            nice = 5;
        } else if (frac <= 7.5) {
            nice = 7.5;
        } else {
            nice = 10;
        }
        return nice * pow;
    }

    private int slotCount(float pw, float slot) {
        return Math.max(1, (int) Math.floor(pw / slot));
    }

    // 分类名太密时隔几个画一个
    private int labelStep(int n, float slot) {
        float needed = 34f;   // 一个中文标签大约要这么多宽度
        return Math.max(1, (int) Math.ceil(needed / Math.max(slot, 1f)));
    }

    private void drawLegend(float lx, float ly, List<Series> series) throws IOException {
        if (series.size() < 2) {
            return;   // 只有一个系列时，图例是多余的
        }
        float cx = lx;
        for (int i = 0; i < series.size(); i++) {
            // 图例色块必须与柱/线同色，否则图例等于在骗人
            canvas.rect(cx, ly, 7f, 7f, seriesColor(i));
            String name = series.get(i).name();
            canvas.text(cx + 11f, ly - 0.5f, name, LEGEND_SIZE, false, LABEL_COLOR);
            cx += 11f + canvas.textWidth(name, LEGEND_SIZE) + 18f;
        }
    }

    // 数值格式化：整数不带小数点，小数保留两位 —— 与界面上的默认一致。
    // 公开是给调用方复用：图表下面的数据表要与图上的标签显示成同一个样子，
    // 各写一份迟早会出现「图上 92.5、表里 92.50」这种不一致
    public static String formatValue(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) {
            return String.valueOf((long) Math.rint(v));
        }
        String s = String.format("%.2f", v);
        // 去掉多余的尾随 0：1.50 显示成 1.5
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    /**
     * 按界面设置格式化数值。
     *
     * <p>界面上有个「显示完整精度」开关：勾上就原样显示，不勾就固定两位小数。
     * 导出到 PDF 的图必须跟着这个开关走 —— 用户特意打开精度，
     * 就是想在图或表里看到 59043.4873 而不是 59043.49，导出来却被打回两位，
     * 那这个开关在导出场景等于失效。</p>
     *
     * <p>完整精度下仍要去掉浮点噪声：{@code BigDecimal.stripTrailingZeros}
     * 能把 59043.487300000001 收敛成 59043.4873。</p>
     */
    public static String formatValue(double v, boolean fullPrecision) {
        if (!fullPrecision) {
            return formatValue(v);
        }
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) {
            return String.valueOf((long) Math.rint(v));
        }
        java.math.BigDecimal bd = java.math.BigDecimal.valueOf(v).stripTrailingZeros();
        // stripTrailingZeros 对 1000 会给出 1E+3 这种科学计数法，转回普通写法
        return bd.toPlainString();
    }

    private String truncate(String s, float maxW, float size) {
        if (canvas.textWidth(s, size) <= maxW) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        float w = 0;
        float ell = canvas.textWidth("…", size);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            float cw = canvas.charWidth(cp, size);
            if (w + cw + ell > maxW) {
                break;
            }
            sb.appendCodePoint(cp);
            w += cw;
        }
        return sb + "…";
    }

    /** 一个系列：名称 + 数值。数值允许为 null（缺失点） */
    public record Series(String name, List<Double> values) {

        public Series {
            values = values == null ? new ArrayList<>() : List.copyOf(values);
        }
    }
}
