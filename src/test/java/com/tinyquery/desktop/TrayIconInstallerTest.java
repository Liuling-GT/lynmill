package com.tinyquery.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TrayIconInstaller 托盘菜单")
class TrayIconInstallerTest {

    private static final String ANY_URL = "http://localhost:8080";

    // ==================== 字体 ====================

    @Test
    @DisplayName("菜单字体能显示菜单里真实出现的每一个汉字，否则会渲染成方块")
    void menuFontCoversAllMenuText() {
        Font font = TrayIconInstaller.menuFont();

        // 校验的是常量拼出来的真实菜单文字，不是随便一个字符串，
        // 因此菜单改了字而字体没跟上时，这个测试会失败
        for (char c : TrayIconInstaller.MENU_TEXT_SAMPLE.toCharArray()) {
            assertTrue(font.canDisplay(c),
                    "字体 " + font.getFontName() + " 无法显示 '" + c + "'，托盘菜单会显示成方块");
        }
    }

    @Test
    @DisplayName("菜单文字常量确实覆盖了三个菜单项")
    void sampleCoversAllLabels() {
        assertEquals("打开界面显示窗口退出", TrayIconInstaller.MENU_TEXT_SAMPLE);
        assertTrue(TrayIconInstaller.MENU_TEXT_SAMPLE.contains(TrayIconInstaller.MENU_LABEL_OPEN));
        assertTrue(TrayIconInstaller.MENU_TEXT_SAMPLE.contains(TrayIconInstaller.MENU_LABEL_SHOW));
        assertTrue(TrayIconInstaller.MENU_TEXT_SAMPLE.contains(TrayIconInstaller.MENU_LABEL_EXIT));
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
        //（重叠区会收到两次事件，虽然当前实现幂等，但说明切分算错了）
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

    // ==================== 工具 ====================

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
