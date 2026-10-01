package com.tinyquery.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * 用系统默认浏览器打开一个 URL。
 *
 * <p>做成无状态的静态工具类，是因为它有两个调用方，其中一个拿不到 Spring 容器：
 * {@link BrowserLauncher}（{@code ApplicationReadyEvent} 时自动打开）和
 * 启动类的单实例退出路径（发现已有实例在运行时打开对方的页面，
 * 那个时刻 Spring 还没启动）。若把逻辑留在 {@code BrowserLauncher} 里，
 * 平台命令表就得抄第二遍。</p>
 *
 * <p>打开方式分两级，顺序是刻意的 —— <b>先系统命令，后 {@link Desktop}</b>。
 * 系统命令不依赖 AWT：在无图形界面的环境（无 DISPLAY 的 Linux、以服务方式运行）
 * 下依然可用，而那时 {@code Desktop.isDesktopSupported()} 恒为 false。
 * 把不依赖图形环境的方式放在前面，服务端场景下也才打得开浏览器。</p>
 *
 * <p>（注：桌面外壳会把 {@code java.awt.headless} 设为 false，因此 Desktop 这条路
 * 在带界面的机器上是可用的退路，而不再是一段永远执行不到的代码。）</p>
 *
 * <p>两级都失败返回 false，不抛异常 —— 打不开浏览器不是致命错误，
 * 服务本身是可用的，调用方提示用户手动访问即可。</p>
 */
public final class BrowserOpener {

    private static final Logger log = LoggerFactory.getLogger(BrowserOpener.class);

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
        try {
            // 只关心命令有没有成功启动，不等它退出 ——
            // 浏览器进程可能长期存活，waitFor 会把启动流程挂住
            new ProcessBuilder(command).start();
            return true;
        } catch (IOException e) {
            log.debug("系统命令 {} 执行失败", command[0], e);
            return false;
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
     * <p><b>这是一个纯函数</b>：操作系统名由参数传入而非读系统属性，
     * 测试因此可以在任意一台机器上验证全部分支 —— 否则 macOS / Linux
     * 两条分支永远跑不到，等于「写了但从没执行过」的代码。</p>
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
}
