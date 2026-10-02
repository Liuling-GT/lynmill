package com.tinyquery.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AppIcons 应用图标")
class AppIconsTest {

    // ==================== 尺寸 ====================

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
    @DisplayName("非正边长直接拒绝，不生成无意义的图像")
    void rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> AppIcons.create(0));
        assertThrows(IllegalArgumentException.class, () -> AppIcons.create(-1));
    }

    @Test
    @DisplayName("选出能覆盖请求尺寸的最小可用尺寸 —— 宁可缩小也不要放大")
    void nearestAvailableNeverUpscales() {
        // 正好命中时就用自己
        for (int size : AppIcons.AVAILABLE_SIZES) {
            assertEquals(size, AppIcons.nearestAvailable(size), size + " 应命中自身");
        }

        // 夹在中间时向上取，保证是缩小而不是放大
        assertEquals(16, AppIcons.nearestAvailable(8));
        assertEquals(16, AppIcons.nearestAvailable(15));
        assertEquals(24, AppIcons.nearestAvailable(17));
        assertEquals(64, AppIcons.nearestAvailable(50));
        assertEquals(128, AppIcons.nearestAvailable(100));

        // 超过最大尺寸时退回最大尺寸，没有更好的选择
        assertEquals(256, AppIcons.nearestAvailable(512));
    }

    @Test
    @DisplayName("每个声明可用的尺寸都真的有对应资源，否则运行时会静默退回纯色块")
    void everyDeclaredSizeHasResource() {
        for (int size : AppIcons.AVAILABLE_SIZES) {
            String path = "/icons/tinyquery-" + size + ".png";
            assertNotNull(AppIcons.class.getResourceAsStream(path),
                    "缺少图标资源 " + path + " —— 该尺寸会退回纯色占位图标");
        }
    }

    // ==================== 内容 ====================

    @Test
    @DisplayName("中心不透明、圆角处透明 —— 证明真的画了圆角方块")
    void centerOpaqueCornerTransparent() {
        BufferedImage image = (BufferedImage) AppIcons.create(32);

        // 中心落在折线图上，圆角 (0,0) 落在圆角矩形之外
        assertEquals(255, alphaAt(image, 16, 16), "中心应完全不透明");
        assertEquals(0, alphaAt(image, 0, 0), "圆角处应完全透明");
        // 这一处在背景填充区内、不在图案上，用来确认底色确实被填满了
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
    @DisplayName("图像确实带透明通道，贴到深色任务栏上不会出现白底方块")
    void imageHasAlphaChannel() {
        BufferedImage image = (BufferedImage) AppIcons.create(64);

        assertTrue(image.getColorModel().hasAlpha(), "图标必须带 alpha 通道");
    }

    private static int alphaAt(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }
}
