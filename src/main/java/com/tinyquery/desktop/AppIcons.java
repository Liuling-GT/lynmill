package com.tinyquery.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用图标（控制窗口的标题栏图标、系统托盘图标）。
 *
 * <p>图标从资源读，而不是用代码画：正式设计是 {@code packaging/tinyquery.ico}，
 * 含 7 个尺寸，这里从 {@code src/main/resources/icons/} 读同源的 PNG。</p>
 *
 * <p>不放一份 .ico 直接读，是因为 JDK 的 ImageIO 不认识 .ico 格式，读出来是 null，
 * 除非自己写解析器或引入第三方库。所以由生成图标的脚本顺手把每个尺寸导出成 PNG ——
 * 数据块本来就是 PNG，原样抽出即可，与 .ico 像素级一致。</p>
 *
 * <p>每个尺寸各存一张、而不是只放 256 再缩：小尺寸下的图标需要简化
 * （16px 时去掉文字、只留加粗的折线），把 256 硬缩到 16 只会糊成一团。
 * 这正是 .ico 支持多尺寸内嵌的原因，PNG 资源沿用同一套策略。</p>
 */
public final class AppIcons {

    private static final Logger log = LoggerFactory.getLogger(AppIcons.class);

    // ==================== 资源 ====================

    private static final String RESOURCE_DIR = "/icons/";
    private static final String RESOURCE_PREFIX = "tinyquery-";
    private static final String RESOURCE_SUFFIX = ".png";

    /**
     * 资源里实际存在的尺寸，必须与 {@code src/main/resources/icons/} 下的文件一一对应。
     *
     * <p>升序排列：{@link #nearestAvailable(int)} 依赖这个顺序。</p>
     */
    static final int[] AVAILABLE_SIZES = {16, 24, 32, 48, 64, 128, 256};

    /** 资源读不到时的兜底色（应用主色紫） */
    private static final Color FALLBACK_COLOR = new Color(0x6D4AFF);

    /** 圆角半径占边长的比例，与图标设计稿一致（196 / 1024） */
    private static final float CORNER_RATIO = 196f / 1024f;

    /**
     * 缓存已解码的图像。
     *
     * <p>图标会被反复取用（控制窗口一次、托盘一次），而解码 PNG 不算便宜。
     * 用并发 Map 是因为托盘与控制窗口可能在不同线程上初始化。</p>
     */
    private static final Map<Integer, BufferedImage> CACHE = new ConcurrentHashMap<>();

    private AppIcons() {
        // 工具类，不实例化
    }

    // ==================== 对外接口 ====================

    /**
     * 生成指定边长的图标。
     *
     * @param size 边长（像素），需为正数
     * @return 带透明通道的图像
     * @throws IllegalArgumentException 边长非正数
     */
    public static Image create(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("图标边长必须为正数：" + size);
        }

        BufferedImage source = load(nearestAvailable(size));
        if (source == null) {
            return fallback(size);
        }
        // 尺寸正好命中时直接用原图，避免一次无谓的重采样
        return source.getWidth() == size ? source : scale(source, size);
    }

    /**
     * 选出能覆盖请求尺寸的最小可用尺寸。
     *
     * <p>宁可缩小也不要放大：放大必然发虚，缩小至少是清晰的。
     * 请求超过最大尺寸时退回最大尺寸。</p>
     *
     * <p>包可见，仅用于测试。</p>
     *
     * @param size 请求的边长
     * @return {@link #AVAILABLE_SIZES} 中的某个值
     */
    static int nearestAvailable(int size) {
        for (int candidate : AVAILABLE_SIZES) {
            if (candidate >= size) {
                return candidate;
            }
        }
        return AVAILABLE_SIZES[AVAILABLE_SIZES.length - 1];
    }

    // ==================== 读取与缩放 ====================

    private static BufferedImage load(int size) {
        BufferedImage cached = CACHE.get(size);
        if (cached != null) {
            return cached;
        }
        BufferedImage loaded = readResource(size);
        if (loaded != null) {
            CACHE.put(size, loaded);
        }
        return loaded;
    }

    private static BufferedImage readResource(int size) {
        String path = RESOURCE_DIR + RESOURCE_PREFIX + size + RESOURCE_SUFFIX;
        try (InputStream in = AppIcons.class.getResourceAsStream(path)) {
            if (in == null) {
                log.warn("图标资源不存在：{}，本次退回纯色占位图标", path);
                return null;
            }
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                log.warn("图标资源无法解码：{}，本次退回纯色占位图标", path);
                return null;
            }
            return image;
        } catch (IOException e) {
            // 读图标失败不该让界面起不来 —— 退回纯色图标即可
            log.warn("读取图标资源失败：{}，本次退回纯色占位图标", path, e);
            return null;
        }
    }

    private static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        try {
            applyQualityHints(g);
            g.drawImage(source, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /**
     * 资源缺失时的兜底图标：一个主色圆角方块。
     *
     * <p>刻意不抛异常。图标读不到只是不好看，而调用它的地方（控制窗口的构造函数、
     * 托盘安装）一旦抛出去，用户会连退出入口都没有。界面能起来、能退出，
     * 比图标好看重要得多。</p>
     */
    private static BufferedImage fallback(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            applyQualityHints(g);
            int arc = Math.max(2, Math.round(size * CORNER_RATIO * 2));
            g.setColor(FALLBACK_COLOR);
            g.fillRoundRect(0, 0, size, size, arc, arc);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
