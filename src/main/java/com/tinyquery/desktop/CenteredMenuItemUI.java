package com.tinyquery.desktop;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.plaf.basic.BasicMenuItemUI;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 托盘菜单项的渲染器：黑底、白字、文字水平垂直居中。
 *
 * <p>必须自己绘制，因为 Windows 外观下的菜单项 UI 是 {@code WindowsMenuItemUI}
 * （继承自 {@link BasicMenuItemUI}），它无视 {@code setHorizontalAlignment} 和
 * {@code setHorizontalTextPosition}，文字会贴着菜单右侧。实测数据（真实弹出菜单
 * 加截屏逐像素测量左右边距之差）：不处理偏右 41 像素；
 * {@code setHorizontalAlignment(CENTER)} 无效，差 41 像素；
 * {@code setHorizontalTextPosition(CENTER)} 无效，差 17 像素；两者都设无效，
 * 差 41 像素；替换 UI 自己绘制则居中，差 1~2 像素。</p>
 *
 * <p>顺带这也让我们完全掌控配色，黑底白字不再受外观默认值影响。</p>
 *
 * <p>注意：本类不调用 {@code super.paintMenuItem}，因此勾选标记、子菜单箭头、
 * 快捷键文字都不会被绘制。当前托盘菜单三者都没有；将来若要加，必须在这里补齐。</p>
 */
final class CenteredMenuItemUI extends BasicMenuItemUI {

    /** 菜单底色 */
    static final Color BACKGROUND = Color.BLACK;

    /** 文字颜色 */
    static final Color FOREGROUND = Color.WHITE;

    /**
     * 鼠标悬停／选中时的高亮底色：纯黑底上必须有个能看出选中的颜色。
     *
     * <p>原来是中性的 {@code #3A3A3A}。改成从当前主题的强调色调暗而来
     * （见 {@link DesktopTheme#trayHover()}），这样网页上换了主题，
     * 托盘菜单的选中色也跟着变，否则托盘是唯一一处「换了主题没反应」的地方。</p>
     *
     * <p>刻意不是 {@code static final} 常量：主题可以在运行期切换，常量会在类加载时
     * 定死。每次绘制时读一遍，代价是一次 {@code Color} 构造，相对整条绘制路径可以忽略。</p>
     */
    static Color hover() {
        return DesktopTheme.trayHover();
    }

    @Override
    protected void paintMenuItem(Graphics g, JComponent c, Icon checkIcon, Icon arrowIcon,
                                 Color background, Color foreground, int defaultTextIconGap) {
        JMenuItem item = (JMenuItem) c;
        boolean active = item.getModel().isArmed() || item.getModel().isSelected();

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(active ? hover() : BACKGROUND);
            g2.fillRect(0, 0, c.getWidth(), c.getHeight());

            g2.setColor(FOREGROUND);
            g2.setFont(item.getFont());

            FontMetrics metrics = g2.getFontMetrics();
            String text = item.getText();

            // 居中就是这两行 —— 整块宽度减去文字宽度，一半给左边
            int x = (c.getWidth() - metrics.stringWidth(text)) / 2;
            // 垂直居中要用 ascent 定位基线；用 getHeight() 会整体偏高
            int y = (c.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();

            g2.drawString(text, x, y);
        } finally {
            g2.dispose();
        }
    }
}
