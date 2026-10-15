package com.tinyquery.api;

import java.util.List;
import java.util.Map;

/**
 * 图表导出为 PDF 的请求。
 *
 * <p>比 {@link ChartRequest} 多出来的都是「画图需要、但聚合不需要」的东西：
 * 图类型、标题、要不要附数据表。聚合参数原样透传，保证导出的图
 * 与屏幕上看到的是同一份数据。</p>
 *
 * <p><b>关于配色：为什么由前端把整排颜色发过来，而不是只发一个主色。</b>
 * 界面上的调色板不是「一个主色」那么简单 —— 它由主色经黄金角派生出一整排
 * 10 个颜色（见 ChartPanel.derivePalette）。如果只把主色发给后端、由后端
 * 重新派生，就得把那段 HSL 取模运算原样移植一遍，两边任何一点浮点差异
 * 都会让导出的配色与屏幕不同。既然前端本来就已经算好了，直接发结果最省事
 * 也最不容易错 —— 这是「所见即所得」最直接的实现方式。</p>
 *
 * @param sql          与界面相同的查询 SQL
 * @param dimension    分组维度列
 * @param measures     度量列，最多两个
 * @param aggregation  sum / avg / count / max / min
 * @param topN         分类数上限，留空用默认
 * @param chartType    bar / line / pie，其余值按 bar 处理
 * @param chartTitle   画在图上的标题，留空则自动用「维度 — 系列名」兜一个
 * @param tableName    报表标题与文件名，用数据源表名
 * @param includeTable 是否在图下面附数据表。默认不附 —— 图已经表达了数据，
 *                     多数时候只要那张图；要精确数值时由用户在导出菜单里勾选。
 * @param palettes     每个系列一整排已派生好的颜色，下标 = 系列下标。
 *                     柱状/折线只用每排的第 0 个；饼图按分类下标取模使用。
 *                     缺省时后端退回自己的默认配色
 * @param sectorColors 饼图每个扇区的颜色覆盖，键是<b>分类名</b>而不是下标。
 *                     用户在界面上单独改过某个扇区的颜色时才有
 * @param highlightMode 是否开启高亮模式。开启时只点亮 highlighted 里的分类，
 *                      其余压成灰色
 * @param highlighted  被高亮的分类名列表。与分类名做全等比较
 * @param unifyScale   双系列是否共用一根 Y 轴。false = 各用各的轴
 *                     （界面上显示为左轴 / 右轴）。这是两者差别最大的一处：
 *                     共用轴时可比较柱高，分开轴时各自铺满高度
 * @param keepFullPrecision 数据标签是否显示完整精度。false = 固定两位小数
 * @param landscape    是否横向，null 视作纵向
 * @param scale        字号缩放，null 视作 1.0
 */
public record ChartPdfRequest(String sql,
                              String dimension,
                              List<String> measures,
                              String aggregation,
                              Integer topN,
                              String chartType,
                              String chartTitle,
                              String tableName,
                              Boolean includeTable,
                              List<List<String>> palettes,
                              Map<String, String> sectorColors,
                              Boolean highlightMode,
                              List<String> highlighted,
                              Boolean unifyScale,
                              Boolean keepFullPrecision,
                              Boolean landscape,
                              Float scale) {

    /** 是否横向，默认纵向 */
    public boolean isLandscape() {
        return Boolean.TRUE.equals(landscape);
    }

    /** 字号缩放，夹在 0.5~1.0，默认 1.0 */
    public float scaleOrDefault() {
        if (scale == null || scale.isNaN() || scale.isInfinite()) {
            return 1f;
        }
        return Math.max(0.5f, Math.min(1f, scale));
    }

    /** 高亮模式是否开启，默认关 */
    public boolean isHighlightMode() {
        return Boolean.TRUE.equals(highlightMode);
    }

    /** 是否两个系列共用一根 Y 轴。默认共用 —— 与界面上的默认值一致 */
    public boolean isUnifyScale() {
        return !Boolean.FALSE.equals(unifyScale);
    }

    /** 是否显示完整精度，默认否（固定两位小数） */
    public boolean isKeepFullPrecision() {
        return Boolean.TRUE.equals(keepFullPrecision);
    }

    /** 是否在图下附数据表，默认附 */
    /**
     * 是否在图下面附数据表。
     *
     * <p>默认<b>不附</b>。图本身已经表达了数据，多数情况下用户只要那张图；
     * 表格放在后面会把图挤到上半页，还得自己裁掉。需要看具体数值时，
     * 界面上的导出菜单里有开关可以勾上。</p>
     */
    public boolean isIncludeTable() {
        return Boolean.TRUE.equals(includeTable);
    }

    /**
     * 取第 index 个系列的第 slot 个颜色。
     *
     * <p>越界一律返回 null，由调用方退回默认配色 —— 前端某个系列没设置
     * 颜色是正常情况，不该因此报错。</p>
     */
    public String colorAt(int index, int slot) {
        if (palettes == null || index < 0 || index >= palettes.size()) {
            return null;
        }
        List<String> row = palettes.get(index);
        if (row == null || slot < 0 || slot >= row.size()) {
            return null;
        }
        String c = row.get(slot);
        return c == null || c.isBlank() ? null : c;
    }
}
