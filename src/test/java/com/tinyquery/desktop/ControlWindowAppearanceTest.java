package com.tinyquery.desktop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 控制窗口跟随主题。
 *
 * <p>不直接建窗口：{@code ControlWindow} 是 {@link javax.swing.JFrame}，
 * 构造它需要真实显示设备，无头环境里会抛 {@code HeadlessException} ——
 * 而「换了主题颜色有没有跟上」这件事必须在无头 CI 上也能验。所以这里验的是
 * 窗口取值与判断的那两层（{@link ControlWindow#runningColor()} 与
 * {@link ControlWindow#needsRefresh}），它们是颜色链路上唯一会写错的地方；
 * 把值设进 {@code JLabel} 的那一行是赋值，不是逻辑。</p>
 *
 * <p>托盘菜单那边不同 —— 它是画出来的，所以那边的测试真的画一遍再读像素
 * （见 {@code TrayIconInstallerTest}）。</p>
 *
 * <p>已知盲区（实测确认过）：这里验的是 {@link ControlWindow#needsRefresh}
 * 这个判断函数本身，验不到 {@code refreshIfChanged()} 里的接线 ——
 * 也就是四个参数有没有按对的位置传进去。实测把调用点从
 * {@code if (!needsRefresh(appliedVersion, appliedThemeVersion, ...))}
 * 改成 {@code if (localeVersion == appliedVersion)}（即只比语言版本号、
 * 完全不管主题），本测试依然全绿。补不上，除非能构造窗口（无头环境建不出来）。
 * 要覆盖这一层得把「判断 + 更新字段 + 刷新」整段搬进一个与窗口无关的类，
 * 那是为测试而重构产品结构，代价大于收益。所以记下这条盲区，
 * 免得后来者看到 8 条全绿就以为这条链已经端到端验过了。</p>
 */
@DisplayName("控制窗口跟随主题")
class ControlWindowAppearanceTest {

    /**
     * 主题是全局状态，测完必须还原，否则后面跑的用例会拿到这里留下的主题。
     * {@code switchTo} 会落盘，所以连文件一起清掉。
     */
    private String savedTheme;

    @BeforeEach
    void rememberTheme() {
        savedTheme = DesktopTheme.current();
    }

    @AfterEach
    void restoreTheme() {
        DesktopTheme.switchTo(savedTheme);
        try {
            // switchTo 会落盘，留下 theme.txt 就会污染后面的用例
            java.nio.file.Files.deleteIfExists(
                    com.tinyquery.config.PathResolver.getDataDir()
                            .resolve(DesktopTheme.FILE_NAME));
        } catch (java.io.IOException ignored) {
            // 清理失败不影响用例结论
        }
    }

    // ==================== 颜色 ====================

    @Test
    @DisplayName("「运行中」的颜色就是当前主题的强调色")
    void runningColorFollowsTheme() {
        for (String theme : DesktopTheme.supported()) {
            DesktopTheme.switchTo(theme);

            assertEquals(DesktopTheme.accent(), ControlWindow.runningColor(),
                    "[" + theme + "] 控制窗口的运行中颜色不是当前主题的强调色 —— "
                            + "网页换了主题，这个窗口不会跟着变");
        }
    }

    @Test
    @DisplayName("四套主题的颜色两两不同 —— 否则换了主题看不出来")
    void runningColorDiffersPerTheme() {
        List<Color> colors = new ArrayList<>();
        for (String theme : DesktopTheme.supported()) {
            DesktopTheme.switchTo(theme);
            colors.add(ControlWindow.runningColor());
        }

        assertEquals(colors.size(), new java.util.HashSet<>(colors).size(),
                "有主题算出了相同的运行中颜色，用户看不出主题换过：" + colors);
    }

    @Test
    @DisplayName("窗口底色是浅色，运行中颜色必须够暗 —— 否则状态字样看不清")
    void runningColorIsReadableOnWindowBackground() {
        // 这个标签画在窗口的默认浅色底上。强调色要是太浅（比如直接用
        // --accent-bright 那一档），「● 运行中」会糊在背景里。
        //
        // 门槛取 3.5 而不是 4.0，与 ThemeTokensTest 同一个理由：
        // 2026-10-12 竹青的按钮底色按要求放浅，--accent 从 #078d58 变成
        // #08965e，白字/浅底对比度 4.23 → 3.79，有意跌破 4.0。
        //
        // 这里放宽的把握比按钮那边更大：「● 运行中」是加粗的，
        // WCAG AA 对大号加粗文字只要求 3:1，3.79 有明显富余。
        final double minRatio = 3.5;

        for (String theme : DesktopTheme.supported()) {
            DesktopTheme.switchTo(theme);
            Color fg = ControlWindow.runningColor();

            double ratio = contrast(fg, Color.WHITE);
            assertTrue(ratio >= minRatio,
                    "[" + theme + "] 运行中颜色 " + fg + " 在浅色窗口底上的对比度只有 "
                            + String.format("%.2f", ratio) + ":1，低于 "
                            + String.format("%.1f", minRatio) + ":1");
        }
    }

    // ==================== 刷新判断 ====================
    //
    // 这一段是真正容易写错的地方：把语言与主题两个版本号合成一个，
    // 就会丢掉「只有主题变了」—— 语言切换看起来完全正常，颜色却不动。

    @Test
    @DisplayName("都没变时不刷新")
    void nothingChangedMeansNoRefresh() {
        assertFalse(ControlWindow.needsRefresh(7, 9, 7, 9));
    }

    @Test
    @DisplayName("只有语言变了 → 刷新")
    void localeOnlyChangeRefreshes() {
        assertTrue(ControlWindow.needsRefresh(7, 9, 8, 9));
    }

    @Test
    @DisplayName("只有主题变了 → 也必须刷新（合成一个版本号就会漏掉这条）")
    void themeOnlyChangeRefreshes() {
        assertTrue(ControlWindow.needsRefresh(7, 9, 7, 10),
                "只有主题变了却判断为不用刷新 —— 网页换了主题，"
                        + "控制窗口的颜色会一直停在旧的那套");
    }

    @Test
    @DisplayName("两个都变了 → 刷新")
    void bothChangedRefreshes() {
        assertTrue(ControlWindow.needsRefresh(7, 9, 8, 10));
    }

    @Test
    @DisplayName("版本号回退（比如换了个更旧的值）也算变了")
    void versionGoingBackwardsStillRefreshes() {
        // 只写 > 而不是 != 会漏掉这种情况
        assertTrue(ControlWindow.needsRefresh(7, 9, 7, 8));
        assertTrue(ControlWindow.needsRefresh(7, 9, 6, 9));
    }

    // ==================== 工具 ====================

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed())
                + 0.7152 * channel(c.getGreen())
                + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int value) {
        double v = value / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
