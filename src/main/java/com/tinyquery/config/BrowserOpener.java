package com.tinyquery.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 用系统默认浏览器打开一个 URL。
 *
 * <p>做成无状态的静态工具类，是因为它有两个调用方，其中一个拿不到 Spring 容器：
 * {@link BrowserLauncher}（{@code ApplicationReadyEvent} 时自动打开）和
 * 启动类的单实例退出路径（发现已有实例在运行时打开对方的页面，
 * 那个时刻 Spring 还没启动）。若把逻辑留在 {@code BrowserLauncher} 里，
 * 平台命令表就得抄第二遍。</p>
 *
 * <p>打开方式分两级，顺序是先系统命令、后 {@link Desktop}。系统命令不依赖 AWT：
 * 在无图形界面的环境（无 DISPLAY 的 Linux、以服务方式运行）下依然可用，
 * 而那时 {@code Desktop.isDesktopSupported()} 恒为 false。把不依赖图形环境的方式
 * 放在前面，服务端场景下也才打得开浏览器。</p>
 *
 * <p>桌面外壳会把 {@code java.awt.headless} 设为 false，因此 Desktop 这条路
 * 在带界面的机器上是可用的退路，而不是一段永远执行不到的代码。</p>
 *
 * <p>两级都失败返回 false，不抛异常：打不开浏览器不是致命错误，
 * 服务本身是可用的，调用方提示用户手动访问即可。</p>
 *
 * <p>一级必须检验退出码。早先的实现只判断「命令进程有没有启动起来」，起来就算成功，
 * 这在 Linux 上是错的：{@code xdg-open} 在系统没有设置默认浏览器时照常能启动，
 * 只是立刻以非 0 退出码失败、什么也不打开，于是得到一个静默的假成功 ——
 * 浏览器没打开，应用却认为打开了，而 {@code Desktop} 那条退路永远轮不到。</p>
 *
 * <p>Linux 上这两条路还共用同一个前提：{@code Desktop.browse()} 在 OpenJDK 里
 * （{@code XDesktopPeer}）底层同样是去调 {@code xdg-open}。所以默认浏览器没配好时，
 * 两条路会一起失败。这种情况下唯一有用的事就是把原因和怎么修说清楚，
 * 见 {@link #manualHint(String)}。</p>
 */
public final class BrowserOpener {

    private static final Logger log = LoggerFactory.getLogger(BrowserOpener.class);

    /**
     * 等待打开命令结束的宽限时间，单位秒。
     *
     * <p>为什么必须等：不等就判断成功，会在 Linux 上产生「日志说打开了、实际没打开」
     * 的假象。</p>
     *
     * <p>为什么只等 2 秒：成功的 {@code rundll32} / {@code open} / {@code xdg-open}
     * 都很快返回 —— 它们只负责把请求交给浏览器，不等浏览器退出。
     * 失败同样是立刻返回。2 秒足够覆盖慢一些的机器。</p>
     */
    static final int COMMAND_GRACE_SECONDS = 2;

    /** 捕获命令输出时最多保留多少字符。只为诊断，不必完整 */
    private static final int MAX_CAPTURED_OUTPUT = 2000;

    private BrowserOpener() {
        // 工具类，不实例化
    }

    /**
     * 用系统默认浏览器打开 URL。
     *
     * @return 是否成功发起打开动作；false 表示两级方式都失败
     */
    public static boolean open(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (openByCommand(url)) {
            log.debug("已用系统命令打开浏览器：{}", url);
            return true;
        }
        if (openByDesktop(url)) {
            log.debug("已用 Desktop 打开浏览器：{}", url);
            return true;
        }
        return false;
    }

    /**
     * 一级：调各平台自带的「用默认程序打开 URL」命令。
     * 不依赖图形环境，headless 下同样可用
     */
    private static boolean openByCommand(String url) {
        String[] command = buildCommand(System.getProperty("os.name", ""), url);
        if (command == null) {
            return false;
        }
        return runAndCheck(command);
    }

    /**
     * 执行打开命令，并判断它是否真的成功。
     *
     * <p>包可见，便于测试直接喂一个必定失败的命令（例如 {@code /bin/false}）
     * 来验证「失败必须返回 false」，不必在测试里伪造操作系统。</p>
     *
     * @param command 命令数组，第一段是命令名
     * @return true 表示命令成功退出、或仍在运行（说明请求已交给长期存活的进程）
     */
    static boolean runAndCheck(String[] command) {
        return runAndCheck(command, COMMAND_GRACE_SECONDS);
    }

    /**
     * 同上，但宽限时间可注入 —— 让「命令仍在运行」这条分支可被测到
     * （传 0 秒时，任何还没退出的命令都会走那条分支）。
     */
    static boolean runAndCheck(String[] command, int graceSeconds) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            // 合并 stderr：失败原因（例如 xdg-open 的 "no method available for opening"）在那里
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (IOException e) {
            log.debug("系统命令 {} 执行失败", command[0], e);
            return false;
        }

        StringBuilder captured = new StringBuilder();
        // 输出必须被读走，且在独立线程里读：
        //   ① 管道缓冲区写满时子进程会阻塞在写上面，主线程就永远等不到它结束；
        //   ② 但也不能在主线程同步读到 EOF —— 有些实现会把 stdout 交给浏览器，
        //      那样读到最后也等不到 EOF，反而把启动流程挂住。
        // 用守护线程：最坏情况它一直等到 JVM 退出，不影响主流程。
        Thread pump = new Thread(() -> drainInto(process, captured), "tinyquery-browser-opener-output");
        pump.setDaemon(true);
        pump.start();

        try {
            if (!process.waitFor(graceSeconds, TimeUnit.SECONDS)) {
                // 还在跑：请求已经交给某个长期存活的进程（浏览器本身），算成功。
                // 不能 destroy —— 那可能正是用户的浏览器。
                return true;
            }
            int code = process.exitValue();
            if (code == 0) {
                return true;
            }
            log.warn("打开浏览器的命令 {} 以退出码 {} 失败：{}",
                    command[0], code, captured.isEmpty() ? "(无输出)" : captured.toString().trim());
            return false;
        } catch (InterruptedException e) {
            // 被打断不等于命令失败。此时再去试 Desktop 反而可能打开两个窗口
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** 把子进程输出读进 builder。读到 EOF 或出错为止；由调用方保证在守护线程里跑 */
    private static void drainInto(Process process, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.length() < MAX_CAPTURED_OUTPUT) {
                    sink.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            // 读不到输出不影响主流程，只是少一点诊断信息
            log.debug("读取打开命令的输出失败", e);
        }
    }

    /**
     * 二级：AWT 的 Desktop。仅在一级不可用时才尝试 ——
     * 例如运行环境缺少 xdg-open 之类的系统命令
     */
    private static boolean openByDesktop(String url) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return false;
            }
            desktop.browse(URI.create(url));
            return true;
        } catch (IOException | RuntimeException e) {
            // RuntimeException 覆盖 UnsupportedOperationException 等 AWT 侧异常
            log.debug("Desktop.browse 打开失败", e);
            return false;
        }
    }

    /**
     * 按操作系统给出打开 URL 的命令，三者均为系统自带，不引入任何新依赖。
     *
     * <p>这是一个纯函数：操作系统名由参数传入而非读系统属性，
     * 测试因此可以在任意一台机器上验证全部分支 —— 否则 macOS / Linux
     * 两条分支永远跑不到。</p>
     *
     * <p>包可见，仅用于测试。</p>
     *
     * @param osName {@code os.name} 系统属性
     * @param url    要打开的地址
     * @return 命令数组；无法识别的操作系统返回 null
     */
    static String[] buildCommand(String osName, String url) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);

        if (os.contains("windows")) {
            // 判据必须是完整的 "windows" 而不是 "win"：
            // macOS 的 os.name 可能是 "Darwin"，而 "darwin" 里恰好含有 "win"，
            // 用 "win" 会把 macOS 误判成 Windows。
            // 另外用 rundll32 而非 start：start 是 cmd 的内建命令，无法被直接 exec，
            // 且 "url.dll,FileProtocolHandler" 必须作为单个参数传递，逗号不能被拆开
            return new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new String[]{"open", url};
        }
        if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            return new String[]{"xdg-open", url};
        }
        return null;
    }

    /**
     * 两级都失败时，给用户一句可执行的补救指引；没有针对性建议时返回空串。
     *
     * <p>Linux 是唯一需要这句提示的平台：Windows 的 {@code rundll32} 与 macOS 的
     * {@code open} 都直接依赖系统里写好的 URL 关联，几乎不会「两条路一起失败」；
     * 而 Linux 上如果从没设置过默认浏览器，{@code xdg-open} 和 {@code Desktop.browse}
     * 会一起失败，光说「请手动访问」用户并不知道该怎么修。</p>
     *
     * <p>纯函数，包可见，仅用于测试与调用方拼装日志。</p>
     */
    static String manualHint(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            return "Linux 上若从未设置默认浏览器，执行 xdg-settings set default-web-browser <浏览器>.desktop "
                    + "（例如 firefox.desktop）即可修复";
        }
        return "";
    }

    /** 便捷重载：按当前系统给出提示 */
    static String manualHint() {
        return manualHint(System.getProperty("os.name", ""));
    }
}
