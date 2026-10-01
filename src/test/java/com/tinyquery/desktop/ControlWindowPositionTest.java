package com.tinyquery.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 控制窗口的右下角定位计算。
 *
 * <p>抽成纯函数才测得动：真实屏幕尺寸、任务栏位置在单元测试里拿不到，
 * 而这段算术恰恰最容易写错（正负号、边界、窗口比屏幕还大）。
 * 之前是「居中」而不是右下角，也是因为位置逻辑从来没被测过。</p>
 */
@DisplayName("控制窗口右下角定位")
class ControlWindowPositionTest {

    /** 常见情形：1920×1080，任务栏 40px 在底部 */
    private static final Rectangle FULL_HD =
            new Rectangle(0, 0, 1920, 1040);

    @Test
    @DisplayName("窗口落在可用区域的右下角")
    void sitsAtBottomRight() {
        Point p = ControlWindow.bottomRightPosition(FULL_HD, 400, 200, 24);

        // 右边缘：窗口右边 = 可用区右边 - 间距
        assertEquals(1920 - 400 - 24, p.x);
        // 下边缘：窗口下边 = 可用区下边 - 间距
        assertEquals(1040 - 200 - 24, p.y);
    }

    @Test
    @DisplayName("不能压在任务栏上 —— 这是用 getMaximumWindowBounds 而非 getScreenSize 的理由")
    void neverOverlapsTaskbar() {
        // 若误用 getScreenSize()（1080 高，没排除任务栏），y 会是 1080-200-24=856，
        // 窗口下边到 1056，正好压住任务栏
        Point p = ControlWindow.bottomRightPosition(FULL_HD, 400, 200, 24);

        int windowBottom = p.y + 200;
        assertTrue(windowBottom <= FULL_HD.y + FULL_HD.height,
                "窗口下边 " + windowBottom + " 超出了可用区 "
                        + (FULL_HD.y + FULL_HD.height) + "，会压在任务栏上");
    }

    @Test
    @DisplayName("窗口明显偏右下，而不是居中")
    void isNotCentered() {
        Point p = ControlWindow.bottomRightPosition(FULL_HD, 400, 200, 24);

        // 居中应当是 x=760, y=420。确保我们真的改了行为
        Point center = new Point((1920 - 400) / 2, (1040 - 200) / 2);
        assertNotEquals(center, p, "仍然居中，说明改动没生效");
        assertTrue(p.x > center.x, "靠右：x 应当大于居中位置");
        assertTrue(p.y > center.y, "靠下：y 应当大于居中位置");
    }

    @Test
    @DisplayName("多显示器 / 副屏在左侧：可用区 x 为负也要算对")
    void handlesNegativeOrigin() {
        // 主屏右边还有一块屏时，主屏的可用区可能从负坐标开始
        Rectangle leftScreen = new Rectangle(-1920, -200, 1920, 1040);

        Point p = ControlWindow.bottomRightPosition(leftScreen, 400, 200, 24);

        assertEquals(-1920 + 1920 - 400 - 24, p.x);
        assertEquals(-200 + 1040 - 200 - 24, p.y);
    }

    @Test
    @DisplayName("屏幕太小时贴边，绝不返回负坐标（否则标题栏跑到屏幕外拖不回来）")
    void clampsWhenScreenTooSmall() {
        Rectangle tiny = new Rectangle(0, 0, 300, 200);

        Point p = ControlWindow.bottomRightPosition(tiny, 400, 300, 24);

        assertEquals(0, p.x, "算出来是负数时应当贴到可用区左边界");
        assertEquals(0, p.y, "算出来是负数时应当贴到可用区上边界");
    }

    @Test
    @DisplayName("间距为 0 时紧贴右下角")
    void zeroMarginSticksToCorner() {
        Point p = ControlWindow.bottomRightPosition(FULL_HD, 100, 50, 0);

        assertEquals(1920 - 100, p.x);
        assertEquals(1040 - 50, p.y);
    }

    @Test
    @DisplayName("窗口尺寸变化时位置跟着变，始终贴着右下")
    void tracksWindowSize() {
        Point small = ControlWindow.bottomRightPosition(FULL_HD, 200, 100, 24);
        Point big   = ControlWindow.bottomRightPosition(FULL_HD, 600, 400, 24);

        assertEquals(24, 1920 - (small.x + 200), "小窗口右边距应当正好是 24");
        assertEquals(24, 1920 - (big.x + 600), "大窗口右边距也应当是 24");
        assertTrue(big.x < small.x, "窗口更宽时应当更靠左");
        assertTrue(big.y < small.y, "窗口更高时应当更靠上");
    }
}
