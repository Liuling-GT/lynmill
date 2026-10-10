package com.tinyquery.i18n;

import com.tinyquery.config.PathResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 桌面端语言的来源：一个落在用户数据目录里的小文件。
 *
 * <h2>为什么是文件，不是环境变量</h2>
 *
 * <p>环境变量（{@code TINYQUERY_LOCALE=en}）只对「从命令行启动」有效。
 * 但本产品的主要入口是<b>双击桌面图标</b> —— 那种启动方式继承的是系统环境，
 * 用户没法方便地改。写一个文件则哪种启动方式都认，用户也能直接编辑。</p>
 *
 * <h2>为什么不在设置页里改</h2>
 *
 * <p>设置页改的是浏览器里的 localStorage，服务端并不知道（语言跟着请求头走）。
 * 桌面端的语言是<b>启动期确定一次</b>的：托盘菜单在 AWT 线程构建，
 * 那时没有任何请求上下文。要让它实时跟着设置页变，得额外做前端通知后端的接口；
 * 而托盘菜单只在右键时出现，用户切完语言后本来就多半看不见它 ——
 * 重启即生效的代价可以接受。详见 {@link Messages} 的类注释。</p>
 *
 * <h2>取值优先级</h2>
 *
 * <ol>
 *   <li>环境变量 {@code TINYQUERY_LOCALE}（便于测试与 CI，不落盘）</li>
 *   <li>数据目录下的 {@code locale.txt}（用户可编辑，跨重启保留）</li>
 *   <li>都没有 → 中文</li>
 * </ol>
 *
 * <p>任何读取失败都<b>不抛异常</b>：语言取不到只是文案语言不对，
 * 不该让程序起不来。这与 {@code PathResolver} 对致命错误的处理方式不同，
 * 因为两者的后果量级完全不同。</p>
 */
public final class DesktopLocale {

    /** 覆盖用环境变量。测试与 CI 用它，不必真的写文件 */
    public static final String ENV = "TINYQUERY_LOCALE";

    /** 数据目录下的文件名 */
    public static final String FILE_NAME = "locale.txt";

    /**
     * 语言版本号。每次语言被改变时自增。
     *
     * <p>桌面端的界面元素（托盘菜单项、控制窗口上的标签按钮）在创建时就把文字取好了，
     * 之后光改 {@link Messages} 里的值它们不会自己更新。它们靠比对这个版本号
     * 判断「要不要重建/重新 setText」——而不是每次都重建（那样右键菜单会变慢，
     * 且菜单里还有字体探测这类一次性开销）。</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong VERSION =
            new java.util.concurrent.atomic.AtomicLong();

    private DesktopLocale() {
        // 工具类，不实例化
    }

    /** 当前语言版本号。见 {@link #VERSION} */
    public static long version() {
        return VERSION.get();
    }

    /**
     * 运行期切换桌面端语言，并持久化。
     *
     * <p>供「在网页设置里切了语言」这条路径调用 —— 一个程序一套语言设置，
     * 网页上选的应当同样作用于托盘与控制窗口。</p>
     *
     * <p>持久化到 {@code locale.txt}，这样重启之后仍然记得。
     * 写盘失败<b>不影响本次切换</b>：内存里的语言照样生效，只是重启后会退回默认。</p>
     *
     * @param locale 目标语言，认不出的值当中文
     * @return 实际生效的语言
     */
    public static String switchTo(String locale) {
        String normalized = Messages.normalize(locale);
        String previous = Messages.desktopLocale();
        Messages.setDesktopLocale(normalized);

        // 只有真的变了才自增版本号：否则每次调接口都会让桌面端白白重建一遍界面
        if (!normalized.equals(previous)) {
            VERSION.incrementAndGet();
        }

        writeFileQuietly(normalized);
        return normalized;
    }

    /**
     * 解析并应用桌面端语言。<b>应在创建任何窗口/托盘之前调用一次。</b>
     *
     * @return 实际生效的语言，{@link Messages#ZH} 或 {@link Messages#EN}
     */
    public static String apply() {
        String resolved = resolve(System.getenv(ENV), readFileSafely());
        Messages.setDesktopLocale(resolved);
        return Messages.desktopLocale();
    }

    /**
     * 纯函数形式的解析逻辑，便于测试三个分支而不碰真实环境。
     *
     * @param envValue  环境变量的值，可为 null
     * @param fileValue 文件内容，可为 null（文件不存在或读失败）
     * @return 规范化后的语言
     */
    static String resolve(String envValue, String fileValue) {
        // 环境变量优先：它是显式指定的，且能覆盖一个写错的文件
        if (envValue != null && !envValue.isBlank()) {
            return Messages.normalize(envValue);
        }
        if (fileValue != null && !fileValue.isBlank()) {
            return Messages.normalize(fileValue);
        }
        return Messages.ZH;
    }

    /**
     * 读语言文件。文件不存在是<b>正常状态</b>（绝大多数用户从不设置它）。
     *
     * <p>这里刻意把 IOException 也吞掉：数据目录不可读时，
     * 程序该做的是照常启动、用默认语言，而不是因为一个可选偏好就崩掉。</p>
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
            // 读不到就用默认语言。不记 WARN —— 用户在没设置过语言时
            // 也会走到这里（文件不存在），记日志只会制造无意义的噪音
            return null;
        }
    }

    /**
     * 把语言写入 locale.txt。
     *
     * <p>刻意<b>不抛异常</b>：写不进去（磁盘满、目录只读）只是「重启后不记得」，
     * 而这次切换在内存里已经生效了。为了一个持久化失败回滚用户的当前选择，
     * 反而更让人困惑。</p>
     *
     * <p>写的是纯语言标识（{@code zh} / {@code en}），不带注释 ——
     * 用户手改这个文件时不该被格式干扰。读取那边已经会跳过空行与 {@code #} 开头的行。</p>
     */
    private static void writeFileQuietly(String locale) {
        try {
            Path file = PathResolver.getDataDir().resolve(FILE_NAME);
            Files.writeString(file, locale + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // 见方法注释：持久化失败不影响本次切换
        }
    }
}
