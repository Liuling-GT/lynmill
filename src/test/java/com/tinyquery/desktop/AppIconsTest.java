package com.tinyquery.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AppIcons 应用图标生成")
class AppIconsTest {

    @Test
    @DisplayName("生成的图像尺寸与请求一致")
    void sizeMatchesRequest() {
        BufferedImage small = (BufferedImage) AppIcons.create(16);
        BufferedImage large = (BufferedImage) AppIcons.create(64);

        assertEquals(16, small.getWidth());
        assertEquals(16, small.getHeight());
        assertEquals(64, large.getWidth());
        assertEquals(64, large.getHeight());
    }

    @Test
    @DisplayName("中心不透明、圆角处透明 —— 证明真的画了圆角方块")
    void centerOpaqueCornerTransparent() {
        BufferedImage image = (BufferedImage) AppIcons.create(32);

        // 中心落在字母 T 上，圆角 (0,0) 落在圆角矩形之外
        assertEquals(255, alphaAt(image, 16, 16), "中心应完全不透明");
        assertEquals(0, alphaAt(image, 0, 0), "圆角处应完全透明");
        // 这一处在背景填充区内、不在字母上，用来确认底色确实被填满了
        assertEquals(255, alphaAt(image, 4, 16), "背景应填充到边缘内侧");
    }

    @Test
    @DisplayName("小尺寸也画得出来，中心不为空")
    void smallSizesWork() {
        for (int size : new int[]{8, 16, 24}) {
            BufferedImage image = (BufferedImage) AppIcons.create(size);

            assertEquals(size, image.getWidth());
            assertTrue(alphaAt(image, size / 2, size / 2) > 0, size + "px 图标中心不该是空的");
        }
    }

    @Test
    @DisplayName("非正边长直接拒绝，不生成无意义的图像")
    void rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> AppIcons.create(0));
        assertThrows(IllegalArgumentException.class, () -> AppIcons.create(-1));
    }

    private static int alphaAt(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }
}
