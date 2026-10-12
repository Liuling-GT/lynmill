package com.tinyquery.desktop;

import com.tinyquery.config.PathResolver;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 桌面端主题色的来源：一个落在用户数据目录里的小文件。
 *
 * <p>网页的主题是纯前端的：换的是 {@code <html data-theme>} 属性，
 * 颜色由 {@code tokens.css} 里的变量块决定，服务端不参与。而桌面外壳
 * （控制窗口上「● 运行中」那行字、托盘菜单的悬停底色）由 Swing 绘制，
 * 读不到浏览器的 CSS 变量，也没有请求上下文可以跟着 {@code X-TinyQuery-*}
 * 头走。网页切主题时要让它们一起变，只能由服务端记着一份。取值优先级、
 * 容错策略、版本号机制都与 {@link com.tinyquery.i18n.DesktopLocale} 相同。</p>
 *
 * <p>色值在这里重复一份是因为 Java 读不到 CSS。为了不让两边跑偏，
 * {@code ThemeTokensTest} 会解析 {@code tokens.css} 并逐个断言本类的
 * {@link #ACCENTS} 与 {@code --accent} 一致，改色时漏改一边测试即失败。</p>
 *
 * <p>取值优先级：环境变量 {@code TINYQUERY_THEME}（便于测试与 CI，不落盘）；
 * 数据目录下的 {@code theme.txt}（用户可编辑，跨重启保留）；
 * 都没有则用默认主题（紫罗兰）。</p>
 *
 * <p>任何读取失败都不抛异常：主题取不到只是颜色不对，不该让程序起不来。
 * 认不出的主题值一律回落默认，与语言那边同一条原则。</p>
 */
public final class DesktopTheme {

    /** 覆盖用环境变量。测试与 CI 用它，不必真的写文件 */
    public static final String ENV = "TINYQUERY_THEME";

    /** 数据目录下的文件名 */
    public static final String FILE_NAME = "theme.txt";

    /** 默认主题。与前端 theme/index.js 的 DEFAULT_THEME 必须一致 */
    public static final String DEFAULT = "violet";

    /**
     * 主题标识 → 强调色。顺序即展示顺序。
     *
     * <p>这些值必须与 {@code tinyquery-frontend/src/assets/tokens.css} 里
     * 同名主题块的 {@code --accent} 逐字节一致，由 {@code ThemeTokensTest} 断言。</p>
     */
    private static final Map<String, String> ACCENTS = Map.of(
            "violet", "#8b5cf6",
            "indigo", "#6366f1",
            "bamboo", "#08965e",
            "coffee", "#b06b05");

    /**
     * 展示顺序。
     *
     * <p>单独列一份而不是靠 {@link Map#of} 的顺序：{@code Map.of} 的迭代顺序
     * 未定义，而接口要把这个顺序给前端，好让两边列出来的主题顺序一致。</p>
     */
    private static final List<String> ORDER = List.of("violet", "indigo", "bamboo", "coffee");

    /**
     * 主题版本号，每次主题被改变时自增。
     *
     * <p>与 {@link com.tinyquery.i18n.DesktopLocale#version()} 同一套机制：
     * Swing 控件在创建时就把颜色定死了，靠比对版本号决定要不要重刷，
     * 而不是每次都重建界面。</p>
     */
    private static final AtomicLong VERSION = new AtomicLong();

    /** 当前主题标识。写在 Tomcat 线程、读在 EDT，需要可见性保证 */
    private static volatile String desktopTheme = DEFAULT;

    private DesktopTheme() {
        // 工具类，不实例化
    }

    // ==================== 查询 ====================

    /** 当前主题版本号。见 {@link #VERSION} */
    public static long version() {
        return VERSION.get();
    }

    /** 当前主题标识 */
    public static String current() {
        return desktopTheme;
    }

    /** 支持的主题标识，顺序即展示顺序 */
    public static List<String> supported() {
        return ORDER;
    }

    /** 主题是否被支持 */
    public static boolean isSupported(String theme) {
        return theme != null && ACCENTS.containsKey(theme);
    }

    /**
     * 主题的强调色，供 Swing 界面直接使用。
     *
     * <p>认不出的主题回落到默认主题的色：宁可颜色不对，也不要抛异常
     * 把控制窗口的构造搞崩，否则用户连退出入口都没有。</p>
     */
    public static Color accent() {
        return accentOf(desktopTheme);
    }

    /** 指定主题的强调色。认不出时回落到默认主题 */
    public static Color accentOf(String theme) {
        String hex = ACCENTS.get(theme);
        if (hex == null) {
            hex = ACCENTS.get(DEFAULT);
        }
        return Color.decode(hex);
    }

    /**
     * 托盘菜单的悬停底色：强调色压暗后的深色。
     *
     * <p>托盘菜单是黑底白字（见 {@code CenteredMenuItemUI}），悬停原来是中性深灰
     * {@code #3A3A3A}。这里换成强调色调暗，让主题在托盘里也看得见。混入比例 55%
     * 是照「在黑底上要看得出来、又不能亮到刺眼」调的：比例再低就和原来的中性灰
     * 分不出来，再高白字压上去就发飘。</p>
     */
    public static Color trayHover() {
        return tinted(0.55);
    }

    /**
     * 强调色调暗成适合压在黑底上的深色。
     *
     * @param ratio 强调色占比（0~1），其余按黑算
     */
    static Color tinted(double ratio) {
        Color base = accent();
        return new Color(
                (int) Math.round(base.getRed() * ratio),
                (int) Math.round(base.getGreen() * ratio),
                (int) Math.round(base.getBlue() * ratio));
    }

    // ==================== 切换与持久化 ====================

    /**
     * 运行期切换桌面端主题，并持久化。
     *
     * <p>供「在网页设置里切了主题」这条路径调用：一个程序一套主题，
     * 网页上选的应当同样作用于托盘与控制窗口。</p>
     *
     * <p>持久化到 {@code theme.txt}，这样重启之后仍然记得。
     * 写盘失败不影响本次切换：内存里的主题照样生效，只是重启后会退回默认。</p>
     *
     * @param theme 目标主题，认不出的值回落默认主题
     * @return 实际生效的主题
     */
    public static String switchTo(String theme) {
        String normalized = normalize(theme);
        String previous = desktopTheme;
        desktopTheme = normalized;

        // 只有真的变了才自增版本号：否则每次调接口都会让桌面端白白重绘一遍
        if (!normalized.equals(previous)) {
            VERSION.incrementAndGet();
        }

        writeFileQuietly(normalized);
        return normalized;
    }

    /**
     * 解析并应用桌面端主题。应在创建任何窗口/托盘之前调用一次。
     *
     * @return 实际生效的主题
     */
    public static String apply() {
        String resolved = resolve(System.getenv(ENV), readFileSafely());
        desktopTheme = resolved;
        return desktopTheme;
    }

    /**
     * 规范化主题标识：认不出的一律回落默认。
     *
     * <p>不做 trim / 小写之外的宽松匹配：主题标识是内部枚举，不是用户输入的自由
     * 文本，宽松匹配只会掩盖前端传错值。</p>
     */
    public static String normalize(String theme) {
        if (theme == null) {
            return DEFAULT;
        }
        String trimmed = theme.trim().toLowerCase(java.util.Locale.ROOT);
        return ACCENTS.containsKey(trimmed) ? trimmed : DEFAULT;
    }

    /**
     * 纯函数形式的解析逻辑，便于测试三个分支而不碰真实环境。
     *
     * @param envValue  环境变量的值，可为 null
     * @param fileValue 文件内容，可为 null（文件不存在或读失败）
     * @return 规范化后的主题标识
     */
    static String resolve(String envValue, String fileValue) {
        // 环境变量优先：它是显式指定的，且能覆盖一个写错的文件
        if (envValue != null && !envValue.isBlank()) {
            return normalize(envValue);
        }
        if (fileValue != null && !fileValue.isBlank()) {
            return normalize(fileValue);
        }
        return DEFAULT;
    }

    /**
     * 读主题文件。文件不存在是正常状态（绝大多数用户从不设置它）。
     *
     * <p>与语言那边一样把 IOException 也吞掉：数据目录不可读时，程序该做的是
     * 照常启动、用默认主题，而不是因为一个可选偏好就崩掉。</p>
     */
    private static String readFileSafely() {
        try {
            Path file = PathResolver.getDataDir().resolve(FILE_NAME);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            // 取第一行：用户可能在文件末尾留了换行，或顺手写了注释
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException | RuntimeException e) {
            // 读不到就用默认主题。不记 WARN：用户在没设置过主题时
            // 也会走到这里（文件不存在），记日志只会制造无意义的噪音
            return null;
        }
    }

    /**
     * 把主题写入 theme.txt。
     *
     * <p>刻意不抛异常：写不进去只是「重启后不记得」，而这次切换在内存里已经生效了。
     * 为了一个持久化失败回滚用户的当前选择，反而更让人困惑。</p>
     */
    private static void writeFileQuietly(String theme) {
        try {
            Path file = PathResolver.getDataDir().resolve(FILE_NAME);
            Files.writeString(file, theme + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // 见方法注释：持久化失败不影响本次切换
        }
    }
}
