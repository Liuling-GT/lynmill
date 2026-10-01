package com.tinyquery.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
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
