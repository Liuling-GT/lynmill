package com.tinyquery.desktop;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 生成应用图标。
 *
 * <p>刻意用代码画，而不是往仓库里放一个 png：这样不引入二进制资源，
 * 打包时也不必处理资源路径（jpackage 的 app-image 里资源定位很容易出错）。</p>
 *
 * <p>这是<b>占位图标</b> —— 一个圆角方块加字母 T。将来有设计稿时，
 * 把 {@link #create(int)} 换成读图片即可，调用方不用改。</p>
 */
public final class AppIcons {

    private static final Color BACKGROUND = new Color(0x1E7B34);
    private static final Color FOREGROUND = Color.WHITE;

    private AppIcons() {
        // 工具类，不实例化
    }

    /**
     * 生成指定边长的图标。
     *
     * @param size 边长（像素），需为正数
     * @return 带透明通道的图像
     */
    public static Image create(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("图标边长必须为正数：" + size);
        }

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 圆角半径取边长的四分之一，小尺寸下也看得出是圆角
            int arc = Math.max(2, size / 4);
            g.setColor(BACKGROUND);
            g.fillRoundRect(0, 0, size, size, arc, arc);

            g.setColor(FOREGROUND);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, Math.round(size * 0.7f))));

            String text = "T";
            FontMetrics metrics = g.getFontMetrics();
            int x = (size - metrics.stringWidth(text)) / 2;
            // 用 ascent 而不是 getHeight() 定位基线，字母才会真正居中
            int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }
        return image;
    }
}
