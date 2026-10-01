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
 * <h2>为什么必须自己画</h2>
 *
 * <p>Windows 外观下的菜单项 UI 是 {@code WindowsMenuItemUI}（继承自
 * {@link BasicMenuItemUI}），它<b>无视</b> {@code setHorizontalAlignment} 和
 * {@code setHorizontalTextPosition}，文字会贴着菜单右侧。</p>
 *
 * <p>实测数据（真实弹出菜单 + 截屏逐像素测量左右边距之差）：</p>
 * <ul>
 *   <li>不处理 —— 偏右，差 41 像素</li>
 *   <li>{@code setHorizontalAlignment(CENTER)} —— 无效，差 41 像素</li>
 *   <li>{@code setHorizontalTextPosition(CENTER)} —— 无效，差 17 像素</li>
 *   <li>两者都设 —— 无效，差 41 像素</li>
 *   <li><b>替换 UI 自己绘制 —— 居中，差 1～2 像素</b></li>
 * </ul>
 *
 * <p>顺带这也让我们完全掌控配色，黑底白字不再受外观默认值影响。</p>
 *
 * <p><b>注意</b>：本类不调用 {@code super.paintMenuItem}，因此勾选标记、子菜单箭头、
 * 快捷键文字都不会被绘制。当前托盘菜单三者都没有；将来若要加，必须在这里补齐。</p>
 */
final class CenteredMenuItemUI extends BasicMenuItemUI {

    /** 菜单底色 */
    static final Color BACKGROUND = Color.BLACK;

    /** 文字颜色 */
    static final Color FOREGROUND = Color.WHITE;

    /** 鼠标悬停／选中时的高亮底色 —— 纯黑底上必须有个能看出选中的颜色 */
    static final Color HOVER = new Color(0x3A3A3A);

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

            g2.setColor(active ? HOVER : BACKGROUND);
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
