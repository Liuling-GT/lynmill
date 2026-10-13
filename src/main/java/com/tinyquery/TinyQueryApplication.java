package com.tinyquery;

import com.tinyquery.i18n.Messages;
import com.tinyquery.i18n.DesktopLocale;
import com.tinyquery.config.BrowserOpener;
import com.tinyquery.config.PathResolver;
import com.tinyquery.config.ServerPortHolder;
import com.tinyquery.config.SingleInstanceGuard;
import com.tinyquery.desktop.DesktopTheme;
import com.tinyquery.desktop.StartupFailureDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

@SpringBootApplication
public class TinyQueryApplication {

    private static final Logger log = LoggerFactory.getLogger(TinyQueryApplication.class);

    // 首选端口。桌面应用希望地址稳定（便于收藏与记忆），
    // 因此不从 0 开始随机，而是从 8080 起找第一个空闲端口
    private static final int PREFERRED_PORT = 8080;

    // 向后探测的最大步数。走满仍未找到空闲端口时退回系统分配
    private static final int PORT_SCAN_LIMIT = 20;

    // 只监听本机回环网卡，不对局域网暴露。
    //
    // 不设这一项时 Spring Boot 绑通配地址（IPv4 的 0.0.0.0 与 IPv6 的 ::），
    // 局域网内任何机器都能访问 —— 实测确认过。
    //
    // 这在本项目里是实打实的安全洞，而不是「多开了一个口」：
    // 首页 / 必须不带令牌可访问（浏览器得先拿到它才有得用），
    // 而首页 HTML 里就写着 window.__TINYQUERY_TOKEN__ ——
    // 于是「能打开首页」就等于「拿到了令牌」，令牌校验形同虚设。
    //
    // 将来若要支持局域网访问，不能只改这一行：必须同时把「谁可以拿令牌」
    // 从「能打开首页的人」换成真正的登录体系。
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";

    // 单个日志文件的上限，以及保留的历史文件个数。
    // 本地应用日志量很小，这样既留得下排查所需的历史，又不会无限增长
    private static final String LOG_MAX_FILE_SIZE = "5MB";
    private static final String LOG_MAX_HISTORY = "3";

    // 日志文件编码。
    //
    // 不设的话，Logback 的 FileAppender 用平台默认编码 ——
    // 中文 Windows 上是 GBK。程序里写了中文日志（如「凭据存储不可用」），
    // 于是别人用 VS Code（默认按 UTF-8 打开）看到的就是乱码。
    //
    // 实测确认过这个差异：带 -Dfile.encoding=UTF-8 启动时日志是 UTF-8，
    // 不带参数启动（也就是用户从 exe 双击的真实情形）日志是 GBK。
    // 所以这里显式定死 UTF-8，不受平台与启动参数影响。
    //
    // 注意：改成 UTF-8 后，旧版本写下的历史日志仍是 GBK。
    // 同一个目录里可能两种编码共存，那是升级的正常现象，不影响新日志的读取
    private static final String LOG_CHARSET = "UTF-8";

    public static void main(String[] args) {
        // 桌面外壳（控制窗口 + 托盘）需要 AWT，而 Spring Boot 默认把
        // java.awt.headless 设为 true，那样 Swing 窗口根本建不出来。
        // 必须在这里设置：SpringApplication 只在属性「尚未设置」时才填默认值，
        // 启动之前设好它就会沿用我们的值
        System.setProperty("java.awt.headless", "false");

        // 桌面端语言必须在任何可能弹窗的代码之前定下来：
        // 连下面的 configureFileLogging() 都可能失败（数据目录建不出来），
        // 那是启动失败对话框最常出现的场景之一，那时语言得已经确定好了。
        // 本方法内部吞掉所有异常，读不到就用中文，绝不会成为启动失败的原因
        DesktopLocale.apply();

        // 桌面端主题同理：控制窗口上「运行中」那行字的颜色、
        // 托盘菜单的悬停色都在创建时就取好了，必须在建窗口/装托盘之前定下来。
        // 同样吞掉所有异常，读不到就用默认主题
        DesktopTheme.apply();

        try {
            configureFileLogging();
            start(args);
        } catch (Throwable failure) {
            // 捕获 Throwable 而不是 Exception：NoClassDefFoundError、
            // ExceptionInInitializerError 这类属于 Error，同样会让程序起不来，
            // 只 catch Exception 会把它们漏过去 —— 那正是一种「双击了没反应」。
            // （TrayIconInstaller 踩过同一个坑：AWTError 是 Error 不是 Exception）
            StartupFailureDialog.report(failure);
            System.exit(1);
        }
    }

    /**
     * 把日志同时写进用户数据目录。
     *
     * <p>打包成 exe 之后程序没有控制台，只写控制台的日志等于扔进黑洞 ——
     * 一旦启动失败，用户和开发者都拿不到任何线索。日志必须落盘，
     * 而且必须落在用户数据目录里：安装目录（{@code C:\Program Files} 之下）
     * 对普通用户只读，工作目录又随启动方式而变。</p>
     *
     * <p>必须在 Spring 启动之前设置。{@code SpringApplication} 会在启动过程中
     * 初始化日志系统，之后再设就晚了。这里用系统属性而不是新建
     * {@code application.properties}，与端口策略保持同一套做法。</p>
     *
     * <p>本方法可能抛 {@code UncheckedIOException}（数据目录建不出来）——
     * 那正是最常见的启动失败之一，交给 {@link #main} 的兜底去提示用户。</p>
     */
    private static void configureFileLogging() {
        System.setProperty("logging.file.name", PathResolver.getLogFile().toString());
        System.setProperty("logging.logback.rollingpolicy.max-file-size", LOG_MAX_FILE_SIZE);
        System.setProperty("logging.logback.rollingpolicy.max-history", LOG_MAX_HISTORY);
        // 见 LOG_CHARSET 的说明：不设就跟着平台走，中文 Windows 上会写成 GBK
        System.setProperty("logging.charset.file", LOG_CHARSET);
    }

    /**
     * 真正的启动流程：单实例判定 → 端口探测 → 起 Spring。
     *
     * <p>拆成独立方法，是为了让 {@link #main} 的 try 能完整地包住它们 ——
     * 失败可能发生在任何一步，只包 {@code SpringApplication.run} 是不够的。</p>
     */
    private static void start(String[] args) {
        // 单实例必须在 Spring 启动之前判定：否则第二个实例会白起一个容器、
        // 白占一个端口，等发现时已经晚了
        SingleInstanceGuard guard = SingleInstanceGuard.tryAcquire();
        if (!guard.isAcquired()) {
            openRunningInstance(guard);
            return;
        }

        // 绑定地址：显式指定过就不覆盖（与下面的端口策略同一套优先级思想）。
        // 必须在 Spring 启动之前设置，否则连接器已经按通配地址建好了
        if (System.getProperty("server.address") == null) {
            System.setProperty("server.address", LOOPBACK_ADDRESS);
        }

        // 端口优先级：命令行 > 系统属性 > 本方法探测出的空闲端口。
        // 前两者已显式指定时不覆盖，避免把调用方的意图改掉
        if (!hasExplicitPort(args) && System.getProperty("server.port") == null) {
            System.setProperty("server.port", String.valueOf(findFreePort()));
        }

        SpringApplication application = new SpringApplication(TinyQueryApplication.class);
        // 端口最终由 Web 服务器决定（server.port 为 0 时是随机端口），
        // 所以等服务器真正监听之后再写运行地址，第二个实例读到的才一定可用
        application.addListeners((ApplicationListener<WebServerInitializedEvent>) event ->
                guard.publishUrl(ServerPortHolder.baseUrlFor(event.getWebServer().getPort())));
        application.run(args);
    }

    /**
     * 已有实例在运行：打开它的页面，然后本进程退出。
     *
     * <p>这里刻意不启动 Spring —— 容器一旦起来就已经占用了端口，
     * 单实例也就形同虚设。也正因为没有容器，才用得上静态的
     * {@link BrowserOpener} 而不是 {@code BrowserLauncher}。</p>
     */
    private static void openRunningInstance(SingleInstanceGuard guard) {
        String url = guard.readRunningUrl();
        if (url == null) {
            // 打包后没有控制台，这种情况以前只写一行日志，用户看到的仍然是「双击没反应」——
            // 与启动失败是同一种观感，所以走同一套提示机制，只是标题说明真实情况
            StartupFailureDialog.report(
                    Messages.get("desktop.duplicate.title"),
                    Messages.get("desktop.duplicate.body"),
                    new IllegalStateException(Messages.get("desktop.duplicate.advice")));
            return;
        }
        if (BrowserOpener.open(url)) {
            log.info("LynMill 已在运行，已打开其页面：{}", url);
        } else {
            log.warn("LynMill 已在运行，请手动访问：{}", url);
        }
    }

    /**
     * 从 PREFERRED_PORT 起向后探测第一个可绑定的端口探测用的 socket 必须显式关掉 SO_REUSEADDR：Windows 下该选项默认开启
     * 允许两个 socket 绑同一端口，探测就会永远「成功」，等于没测
     * 探测完立即关闭，真正的占用交给 Spring 启动时完成
     * 两者之间存在极小的竞争窗口，对本地单机应用可以接受
     * @return 可用端口；自 PREFERRED_PORT 起连续 PORT_SCAN_LIMIT 个均被占用时返回 0
     *         交由操作系统分配，保证程序仍能启动
     */
    private static int findFreePort() {
        for (int port = PREFERRED_PORT; port < PREFERRED_PORT + PORT_SCAN_LIMIT; port++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(port));
                return port;
            } catch (IOException e) {
                // 该端口已被占用，试下一个
            }
        }
        return 0;
    }

    // 命令行里是否已经带了 --server.port。
    // 命令行参数的优先级高于系统属性，此处只是避免做无用的端口探测
    private static boolean hasExplicitPort(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--server.port=")) {
                return true;
            }
        }
        return false;
    }
}
