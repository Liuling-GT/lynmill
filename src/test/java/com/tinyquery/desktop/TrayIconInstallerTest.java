package com.tinyquery.desktop;

import org.junit.jupiter.api.DisplayName;
import com.tinyquery.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TrayIconInstaller 托盘菜单")
class TrayIconInstallerTest {

    private static final String ANY_URL = "http://localhost:8080";

    /**
     * 语言与主题都是全局状态（{@code Messages.desktopLocale} / {@code DesktopTheme}），
     * 测完必须还原，否则后面跑的测试会拿到上一个用例留下的值。
     */
    private String savedLocale;
    private String savedTheme;

    @BeforeEach
    void rememberLocale() {
        savedLocale = Messages.desktopLocale();
        savedTheme = DesktopTheme.current();
    }

    @AfterEach
    void restoreLocale() {
        Messages.setDesktopLocale(savedLocale);
        DesktopTheme.switchTo(savedTheme);
    }

    // ==================== 字体 ====================

    @Test
    @DisplayName("两种语言下，菜单字体都能显示菜单里真实出现的每一个字符")
    void menuFontCoversAllMenuTextInBothLocales() {
        // 中文要 CJK 字形、英文要拉丁字形，字体覆盖要求不同，
        // 只测一种语言会漏掉另一种。这里两种都过一遍
        for (String locale : List.of(Messages.ZH, Messages.EN)) {
            Messages.setDesktopLocale(locale);
            Font font = TrayIconInstaller.menuFont();
            String sample = TrayIconInstaller.menuTextSample();

            for (char c : sample.toCharArray()) {
                assertTrue(font.canDisplay(c),
                        "[" + locale + "] 字体 " + font.getFontName()
                                + " 无法显示 '" + c + "'，托盘菜单会显示成方块");
            }
        }
    }

    @Test
    @DisplayName("菜单文字跟随语言变化，而不是写死中文")
    void menuLabelsFollowLocale() {
        Messages.setDesktopLocale(Messages.ZH);
        assertEquals("打开界面", TrayIconInstaller.menuLabelOpen());
        assertEquals("显示窗口", TrayIconInstaller.menuLabelShow());
        assertEquals("退出", TrayIconInstaller.menuLabelExit());
        assertEquals("打开界面显示窗口退出", TrayIconInstaller.menuTextSample());

        Messages.setDesktopLocale(Messages.EN);
        assertEquals("Open Interface", TrayIconInstaller.menuLabelOpen());
        assertEquals("Show Window", TrayIconInstaller.menuLabelShow());
        assertEquals("Exit", TrayIconInstaller.menuLabelExit());
    }

    @Test
    @DisplayName("英文模式下菜单文字里没有中文")
    void menuLabelsHaveNoChineseInEnglish() {
        Messages.setDesktopLocale(Messages.EN);

        for (String label : List.of(TrayIconInstaller.menuLabelOpen(),
                TrayIconInstaller.menuLabelShow(), TrayIconInstaller.menuLabelExit())) {
            assertFalse(label.matches(".*[\\u4e00-\\u9fff].*"),
                    "英文模式下托盘菜单仍是中文：「" + label + "」");
        }
    }

    @Test
    @DisplayName("菜单文字取自词表，键名对得上")
    void menuLabelsUseMessageTable() {
        // 直接比对词表值，确认不是「碰巧一样的中文字面量」。
        // 若有人把实现改回硬编码常量，这两个断言仍会通过 ——
        // 所以真正拦得住的是下面那条：改语言后文字必须跟着变
        Messages.setDesktopLocale(Messages.ZH);
        String zh = TrayIconInstaller.menuLabelOpen();
        Messages.setDesktopLocale(Messages.EN);
        String en = TrayIconInstaller.menuLabelOpen();

        assertNotEquals(zh, en,
                "切换桌面端语言后菜单文字没变，说明没走词表（可能被写死或缓存了）");
    }

    @Test
    @DisplayName("每个菜单项都显式设了能显示自身文字的字体")
    void everyMenuItemHasChineseCapableFont() {
        JPopupMenu menu = TrayIconInstaller.buildMenu(ANY_URL, () -> { }, () -> { });

        for (Component component : menu.getComponents()) {
            if (!(component instanceof JMenuItem item)) {
                continue;
            }
            Font font = item.getFont();
            assertNotNull(font, "菜单项「" + item.getText() + "」没有设置字体");

            for (char c : item.getText().toCharArray()) {
                assertTrue(font.canDisplay(c),
                        "菜单项「" + item.getText() + "」所用字体 " + font.getFontName()
                                + " 显示不了 '" + c + "'，界面上会变成方块");
            }
        }
    }

    // ==================== 内容 ====================

    @Test
    @DisplayName("构建出的菜单项文字与顺序正确")
    void buildMenuProducesExpectedItems() {
        JPopupMenu menu = TrayIconInstaller.buildMenu(ANY_URL, () -> { }, () -> { });

        assertEquals(List.of("打开界面", "显示窗口", "退出"), menuItemLabels(menu));
    }

    // ==================== 外观 ====================

    @Test
    @DisplayName("每个菜单项都换成了自绘的居中 UI，否则 Windows 下文字会贴右边")
    void everyMenuItemUsesCenteredUi() {
        JPopupMenu menu = TrayIconInstaller.buildMenu(ANY_URL, () -> { }, () -> { });

        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item) {
                assertInstanceOf(CenteredMenuItemUI.class, item.getUI(),
                        "菜单项「" + item.getText() + "」没换成自绘 UI，"
                                + "Windows 外观下文字会贴着右边而不是居中");
            }
        }
    }

    @Test
    @DisplayName("菜单与菜单项都是黑底白字")
    void menuAndItemsUseDarkColors() {
        JPopupMenu menu = TrayIconInstaller.buildMenu(ANY_URL, () -> { }, () -> { });

        assertEquals(Color.BLACK, menu.getBackground(), "菜单底色不是黑");
        assertEquals(Color.WHITE, menu.getForeground(), "菜单字色不是白");
        assertTrue(menu.isOpaque(),
                "菜单必须不透明，否则未被菜单项盖住的缝隙会透出弹出窗口的浅色底（会变成白带）");

        for (Component component : menu.getComponents()) {
            if (!(component instanceof JMenuItem item)) {
                continue;
            }
            assertEquals(Color.BLACK, item.getBackground(),
                    "菜单项「" + item.getText() + "」底色不是黑");
            assertEquals(Color.WHITE, item.getForeground(),
                    "菜单项「" + item.getText() + "」字色不是白");
        }
    }

    @Test
    @DisplayName("分隔线是自绘的细线组件，不是会铺成白条的外观 Separator")
    void separatorIsCustomThinComponent() {
        JPopupMenu menu = TrayIconInstaller.buildMenu(ANY_URL, () -> { }, () -> { });

        List<Component> separators = new ArrayList<>();
        for (Component component : menu.getComponents()) {
            if (!(component instanceof JMenuItem)) {
                separators.add(component);
            }
        }

        assertEquals(1, separators.size(), "应恰好有一条分隔线");

        Component separator = separators.get(0);
        assertFalse(separator instanceof JPopupMenu.Separator,
                "不能再用外观的 Separator：它会把整条用白底铺满，在黑菜单上是一条刺眼的白条");
        assertEquals(TrayIconInstaller.SEPARATOR_HEIGHT, separator.getPreferredSize().height,
                "分隔线高度应为 " + TrayIconInstaller.SEPARATOR_HEIGHT + " 像素");
    }

    // ==================== 菜单外面的接住层 ====================
    //
    // 背景：菜单弹出后，点桌面/任务栏也该收起它。但 AWT 全局监听只能收到
    // 本程序窗口的事件，点桌面时根本收不到 —— 所以改为在菜单四周铺透明窗口。
    // 这两个不变量错了，要么菜单点不动，要么点别处还是收不起来。

    @Test
    @DisplayName("接住层铺满菜单以外的整块屏幕，且绝不与菜单重叠")
    void stripsCoverEverythingExceptMenu() {
        Rectangle screen = new Rectangle(0, 0, 1920, 1080);
        Rectangle menu = new Rectangle(1500, 900, 200, 160);

        List<Rectangle> strips = TrayIconInstaller.surroundingStrips(menu, screen);

        assertEquals(4, strips.size(), "应是上下左右 4 条");

        for (Rectangle strip : strips) {
            assertFalse(strip.intersects(menu),
                    "接住层 " + strip + " 与菜单重叠了 —— 重叠会让菜单点不动");
        }

        // 屏幕上除菜单外的每一点，都应恰好被 1 条接住层覆盖。
        // 「恰好 1 条」同时排除了两种情况：漏掉某块区域（点不到）、以及两条重叠
        // （重叠区会收到两次事件，虽然当前实现幂等，但说明切分算错了）
        for (int x = screen.x; x < screen.x + screen.width; x += 11) {
            for (int y = screen.y; y < screen.y + screen.height; y += 11) {
                if (menu.contains(x, y)) {
                    continue;
                }
                int hits = 0;
                for (Rectangle strip : strips) {
                    if (strip.contains(x, y)) {
                        hits++;
                    }
                }
                assertEquals(1, hits,
                        "点 (" + x + "," + y + ") 应恰好被 1 条接住层覆盖，实际 " + hits);
            }
        }
    }

    @Test
    @DisplayName("菜单贴着屏幕边缘时，贴边那条退化为空，不会算出负尺寸")
    void stripsDegenerateAtScreenEdge() {
        Rectangle screen = new Rectangle(0, 0, 800, 600);
        // 菜单正好贴在右下角 —— 托盘图标通常就在这个位置
        Rectangle menu = new Rectangle(600, 500, 200, 100);

        List<Rectangle> strips = TrayIconInstaller.surroundingStrips(menu, screen);

        for (Rectangle strip : strips) {
            assertTrue(strip.width >= 0 && strip.height >= 0,
                    "接住层 " + strip + " 出现了负尺寸，setBounds 会直接抛异常");
        }
        assertEquals(0, strips.get(1).height, "菜单贴屏幕底边时，下方接住层应为空");
        assertEquals(0, strips.get(3).width, "菜单贴屏幕右边时，右方接住层应为空");
    }

    @Test
    @DisplayName("副屏在主屏左侧（坐标为负）时同样算得对")
    void stripsWorkOnNegativeOriginScreen() {
        // 多显示器时副屏坐标可能是负的，直接用 (0,0) 起算会全错
        Rectangle screen = new Rectangle(-1920, 0, 1920, 1080);
        Rectangle menu = new Rectangle(-200, 900, 180, 160);

        List<Rectangle> strips = TrayIconInstaller.surroundingStrips(menu, screen);

        for (Rectangle strip : strips) {
            assertFalse(strip.intersects(menu), "接住层不该与菜单重叠：" + strip);
            assertTrue(strip.x >= screen.x && strip.x + strip.width <= screen.x + screen.width,
                    "接住层 " + strip + " 超出了屏幕左右边界");
            assertTrue(strip.y >= screen.y && strip.y + strip.height <= screen.y + screen.height,
                    "接住层 " + strip + " 超出了屏幕上下边界");
        }
    }

    // ==================== 主题跟随 ====================
    //
    // 托盘菜单是黑底白字，只有悬停高亮能体现主题。下面这几条真的画一遍
    // 再读像素 —— 只断言 CenteredMenuItemUI.hover() 等于某个颜色是不够的：
    // 那测的是取值函数，测不出 paintMenuItem 有没有真的用它。

    @Test
    @DisplayName("菜单项的悬停底色就是当前主题的托盘悬停色 —— 真画一遍读像素")
    void hoverBackgroundComesFromTheme() {
        for (String theme : DesktopTheme.supported()) {
            DesktopTheme.switchTo(theme);

            Color painted = paintMenuItem(true);
            Color expected = DesktopTheme.trayHover();

            assertEquals(expected, painted,
                    "[" + theme + "] 悬停底色画出来是 " + painted
                            + "，而当前主题的托盘悬停色是 " + expected
                            + " —— 菜单项没有真的用主题色");
        }
    }

    @Test
    @DisplayName("不悬停时仍是纯黑 —— 主题不该把菜单底色也染了")
    void idleBackgroundStaysBlack() {
        // 主题只该改悬停色。要是顺手把底色也换成强调色调暗，
        // 菜单在四套主题下会整体变色，与"菜单始终保持黑底"的设计相悖
        for (String theme : DesktopTheme.supported()) {
            DesktopTheme.switchTo(theme);
            assertEquals(Color.BLACK, paintMenuItem(false),
                    "[" + theme + "] 不悬停时底色不是纯黑了");
        }
    }

    @Test
    @DisplayName("四套主题画出来的悬停底色两两不同 —— 否则换了主题看不出来")
    void hoverBackgroundDiffersPerTheme() {
        List<Color> painted = new ArrayList<>();
        for (String theme : DesktopTheme.supported()) {
            DesktopTheme.switchTo(theme);
            painted.add(paintMenuItem(true));
        }

        assertEquals(painted.size(), new java.util.HashSet<>(painted).size(),
                "有主题画出了相同的悬停底色，用户看不出主题换过：" + painted);
    }

    @Test
    @DisplayName("悬停底色上压白字仍然清楚 —— 对比度不低于 4.5:1")
    void hoverBackgroundKeepsWhiteTextReadable() {
        // 托盘菜单的文字是白的。悬停色要是太亮，白字就糊了。
        // 4.5:1 是 WCAG AA 对正文的要求；这里按它卡，不按"看着还行"
        for (String theme : DesktopTheme.supported()) {
            DesktopTheme.switchTo(theme);
            Color bg = paintMenuItem(true);

            double ratio = contrast(bg, Color.WHITE);
            assertTrue(ratio >= 4.5,
                    "[" + theme + "] 悬停底色 " + bg + " 上的白字对比度只有 "
                            + String.format("%.2f", ratio) + ":1，低于 4.5:1");
        }
    }

    // ==================== 工具 ====================

    /** 把菜单项画进一张离屏图，返回角落那个像素的颜色（即底色）。 */
    private static Color paintMenuItem(boolean armed) {
        JPopupMenu menu = TrayIconInstaller.buildMenu(ANY_URL, () -> { }, () -> { });
        JMenuItem item = null;
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem candidate) {
                item = candidate;
                break;
            }
        }
        assertNotNull(item, "菜单里没有菜单项，本用例的前提不成立");

        item.setSize(160, 28);
        item.getModel().setArmed(armed);

        BufferedImage image = new BufferedImage(160, 28, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            item.paint(g);
        } finally {
            g.dispose();
        }
        // 取 (1,1)：fillRect 铺满整块，文字在中间，角落一定是底色
        return new Color(image.getRGB(1, 1));
    }

    /** WCAG 相对亮度对比度。 */
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

    private static List<String> menuItemLabels(JPopupMenu menu) {
        List<String> labels = new ArrayList<>();
        for (Component component : menu.getComponents()) {
            // 分隔线（JPopupMenu.Separator）不是 JMenuItem，会被自然排除
            if (component instanceof JMenuItem item) {
                labels.add(item.getText());
            }
        }
        return labels;
    }
}
