package com.tinyquery.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数值轴刻度取整。
 *
 * <p>这一组来自用户反馈「导出的图 X 轴跟原图不一样」。实际不一样的是
 * <b>Y 轴</b>：界面上 ECharts 把最大值 2191 的轴顶定在 2250/2500 附近，
 * 而 PDF 定到了 3000，柱子只占轴高的三分之二，两张图看着就不是一张。</p>
 *
 * <p>根因是步长挡位太稀疏：rawStep = 2191 / 4 = 547.75，
 * 落在 5 与 10 之间就一路跳到 10（即 1000 一格），轴顶被抬到 3000。</p>
 */
@DisplayName("数值轴刻度取整")
class PdfChartAxisScaleTest {

    @Test
    @DisplayName("547.75 这类卡在 5 与 10 之间的步长不该跳到下一个数量级")
    void stepBetweenFiveAndTenDoesNotJumpADecade() {
        // 2191/4，正是用户那组数据（avg(点赞数) 最大 2191）
        double step = PdfChart.niceStep(2191.0 / 4);
        assertEquals(750.0, step, 0.001,
                "步长应落在 750，轴顶才能贴近数据；跳到 1000 会把轴顶抬到 3000");
    }

    @Test
    @DisplayName("轴顶贴近数据：2191 取到 2250，而不是 3000")
    void axisTopHugsTheData() {
        // 复刻 valueRange 的算法：4 格、步长取不小于 rawStep 的档位、
        // 轴顶向上取到步长的整数倍
        double max = 2191.0;
        double step = PdfChart.niceStep(max / 4);
        double top = Math.ceil(max / step) * step;
        assertEquals(2250.0, top, 0.001,
                "轴顶应为 2250。取 3000 时最高的柱子只占三分之二高，与界面不一致");
    }

    @Test
    @DisplayName("常见量级的步长仍然是人习惯的数字")
    void commonStepsStayRound() {
        assertEquals(1.0, PdfChart.niceStep(1), 0.001);
        assertEquals(2.0, PdfChart.niceStep(2), 0.001);
        assertEquals(5.0, PdfChart.niceStep(5), 0.001);
        assertEquals(10.0, PdfChart.niceStep(10), 0.001);
        assertEquals(100.0, PdfChart.niceStep(100), 0.001);
        assertEquals(1000.0, PdfChart.niceStep(1000), 0.001);
    }

    @Test
    @DisplayName("退化的输入不炸")
    void degenerateInputs() {
        assertEquals(1.0, PdfChart.niceStep(0), 0.001);
        assertEquals(1.0, PdfChart.niceStep(-5), 0.001);
        assertEquals(1.0, PdfChart.niceStep(Double.NaN), 0.001);
        assertEquals(1.0, PdfChart.niceStep(Double.POSITIVE_INFINITY), 0.001);
    }

    @Test
    @DisplayName("步长永远不小于请求值，否则轴顶装不下数据")
    void stepIsNeverSmallerThanRequested() {
        for (double raw = 0.1; raw < 10000; raw *= 1.7) {
            double step = PdfChart.niceStep(raw);
            assertTrue(step >= raw,
                    "步长 " + step + " 小于请求值 " + raw + " 会导致轴顶装不下最高柱");
        }
    }

    /**
     * 轴顶不能白空太多。
     *
     * <p>用户第二次反馈「Y 轴还是奇怪」时，数据最大 16000，PDF 轴顶却是 20000
     * —— 上方白空 25%，柱子显得矮，和界面上 ECharts 画的那张（轴顶 18000）
     * 摆在一起就不是一张图。</p>
     */
    @Test
    @DisplayName("轴顶贴近数据：渲染后的留白不超过两成")
    void axisTopHugsDataAcrossMagnitudes() throws Exception {
        for (double max : new double[]{2191, 16000, 950, 9, 123456}) {
            double top = axisTopOf(max);
            double waste = (top - max) / max;
            assertTrue(waste <= 0.2,
                    "数据最大 " + max + " 时轴顶 " + top + "，留白 "
                            + Math.round(waste * 100) + "%，超过了上限");
            assertTrue(top >= max, "轴顶不能低于数据最大值");
        }
    }

    @Test
    @DisplayName("16000 的轴顶是 18000（与界面一致），不是 20000")
    void sixteenThousandTopsAtEighteenThousand() throws Exception {
        assertEquals(18000.0, axisTopOf(16000.0), 0.001,
                "界面显示 18000；PDF 若取 20000 则白空 25%，看着就是另一张图");
    }

    /** 复刻 PdfChart.valueRange 的完整流程，返回轴顶 */
    private static double axisTopOf(double max) throws Exception {
        java.lang.reflect.Method m = PdfChart.class
                .getDeclaredMethod("chooseStep", double.class, double.class);
        m.setAccessible(true);
        double step = (double) m.invoke(null, 0.0, max);
        double lo2 = 0;
        double hi2 = Math.ceil(max / step) * step;
        if (hi2 - lo2 < step) {
            hi2 = lo2 + step;
        }
        // 顶端留一格，最高的柱子不至于顶到边框
        if (hi2 == max) {
            hi2 += step;
        }
        return hi2;
    }
}
