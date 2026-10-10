package com.tinyquery.desktop;

import com.tinyquery.i18n.Messages;

import com.tinyquery.config.BrowserOpener;
import com.tinyquery.config.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动失败时，把「为什么起不来」告诉用户。
 *
 * <p><b>要解决的是打包后最难受的一种失败：双击了，什么都没发生。</b>
 * 打包成 exe 之后程序没有控制台，启动过程中任何一处抛异常，堆栈都写进了那个
 * 并不存在的黑窗口，进程随即退出 —— 用户看到的只有鼠标转了一下圈。
 * 他不知道发生了什么，也不知道该找谁。</p>
 *
 * <p>因此这里按「先保证留痕、再尝试告知」的顺序做三件事：</p>
 * <ol>
 *   <li><b>留痕</b> —— 把「人话摘要 + 完整堆栈」写进用户数据目录；连数据目录都写不进去时
 *       （磁盘满、无权限 —— 这本身就是常见的启动失败原因）退回系统临时目录。
 *       这样即使用户关掉了窗口、事后也无法复现，证据仍然在。</li>
 *   <li><b>告知</b> —— 弹窗给出「原因 + 根本原因 + 详细日志路径」，
 *       并提供一个直达日志文件夹的按钮。</li>
 *   <li><b>兜底</b> —— 无图形环境（服务器、CI）时不弹窗，改写标准错误；
 *       弹窗本身失败也退回标准错误。</li>
 * </ol>
 *
 * <p><b>这个类是最后一道防线，因此它自己绝不抛异常。</b>它被调用的时刻，
 * 程序的其它部分已经全部失效了；此时再抛一个异常，只会把用户真正需要看到的
 * 原因覆盖掉。下面每一处 catch 都是为这条约定服务的。</p>
 */
public final class StartupFailureDialog {

    private static final Logger log = LoggerFactory.getLogger(StartupFailureDialog.class);

    /**
     * 默认标题与首句。
     *
     * <p>做成方法而不是常量：常量在类加载时求值，而本类刻意设计成
     * 「<b>启动最早期的失败</b>也能用」—— 那时 {@code DesktopLocale.apply()}
     * 可能都还没跑。做成方法，取值时机就落在真正要弹窗的那一刻，
     * 拿到的是当时已经确定好的语言。</p>
     *
     * <p>万一语言确实还没定（极端情况），{@link Messages#get} 会回落到中文，
     * 弹窗照样弹得出来 —— 这条兜底比语言正确更重要：用户看到中文的报错，
     * 总好过连报错都看不到。</p>
     */
    private static String title() {
        return Messages.get("desktop.failure.title");
    }

    private static String headline() {
        return Messages.get("desktop.failure.headline");
    }

    /**
     * 崩溃报告文件名。
     *
     * <p>刻意与 {@code tinyquery.log} 分开：日志文件由 logback 管理、会滚动、会被清理，
     * 而这份报告是在「日志系统可能都还没起来」的时刻写下的，必须是用户一定能找到的那一份。</p>
     */
    static final String CRASH_FILE_NAME = "startup-failure.txt";

    /** 异常链成环时的保护深度。正常链条不会超过几层 */
    private static final int MAX_CAUSE_DEPTH = 32;

    private static String buttonOpenLogs() {
        return Messages.get("desktop.failure.openLogs");
    }

    private static String buttonConfirm() {
        return Messages.get("desktop.failure.confirm");
    }

    private StartupFailureDialog() {
        // 工具类，不实例化
    }

    // ==================== 对外入口 ====================

    /**
     * 报告一次启动失败。本方法不抛异常。
     *
     * @param failure 导致启动失败的原因，允许为 null
     */
    public static void report(Throwable failure) {
        report(title(), headline(), failure);
    }

    /**
     * 报告一次「起不来」，标题与首句可自定义。
     *
     * <p>供「程序本身没坏、但这次动作没能完成」的场景使用 —— 例如第二个实例发现
     * 已有实例在运行、却读不到对方的访问地址。那种情况下用户看到的同样是
     * 「双击没反应」，值得用同一套机制告知，只是标题不该写「启动失败」。</p>
     *
     * <p>本方法不抛异常。</p>
     *
     * @param title    弹窗标题
     * @param headline 正文首句，一句话说清发生了什么
     * @param failure  技术原因，允许为 null
     */
    public static void report(String title, String headline, Throwable failure) {
        Path report = writeCrashReport(failure, logDirectory());
        if (report == null) {
            // 日志目录写不进去 —— 退到系统临时目录，总比什么都不留强
            report = writeCrashReport(failure, tempDirectory());
        }

        String message = buildMessage(headline, failure, report);

        // logback 这时多半已经就绪，让它也留一份；
        // 即便日志系统压根没起来，这一步也只是空转，不会抛
        log.error("{}（详情见 {}）", title, report == null ? Messages.get("desktop.failure.stderr") : report, failure);

        if (isHeadless()) {
            // 服务器 / CI：弹窗会抛 HeadlessException，直接写标准错误
            System.err.println(message);
            return;
        }
        showDialog(title, message, report == null ? null : report.getParent());
    }

    // ==================== 留痕 ====================

    /**
     * 把「人话摘要 + 完整堆栈」写进指定目录。
     *
     * @param failure   失败原因，允许为 null
     * @param directory 目标目录，允许为 null
     * @return 写出的文件路径；目录不可写时返回 null 而不抛异常
     */
    static Path writeCrashReport(Throwable failure, Path directory) {
        if (directory == null) {
            return null;
        }
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(CRASH_FILE_NAME);

            StringWriter buffer = new StringWriter();
            buffer.append(buildMessage(headline(), failure, file)).append(System.lineSeparator());
            if (failure != null) {
                buffer.append(System.lineSeparator());
                try (PrintWriter printer = new PrintWriter(buffer)) {
                    failure.printStackTrace(printer);
                }
            }

            Files.writeString(file, buffer.toString(), StandardCharsets.UTF_8);
            return file;
        } catch (IOException | RuntimeException e) {
            // 写报告失败不能再抛：那样用户看到的原因会变成「报告写不出来」，
            // 把真正的失败原因盖掉
            return null;
        }
    }

    private static Path logDirectory() {
        try {
            return PathResolver.getLogDir();
        } catch (RuntimeException e) {
            // 数据目录创建失败正是最常见的启动失败原因之一，
            // 这里必须咽下去，否则会把原始异常顶掉
            return null;
        }
    }

    private static Path tempDirectory() {
        String tmp = System.getProperty("java.io.tmpdir");
        return tmp == null || tmp.isBlank() ? null : Path.of(tmp);
    }

    // ==================== 文案 ====================

    /**
     * 拼出给用户看的那段文字。
     *
     * <p><b>这是一个纯函数</b>：不打日志、不碰文件系统。测试因此可以直接喂造出来的异常
     * 校验措辞，不需要真的制造一次启动失败 —— 那种事在自动化测试里既难做又不该做。</p>
     *
     * @param headline   首句，例如「TinyQuery 启动失败，无法继续运行。」
     * @param failure    失败原因，允许为 null
     * @param detailFile 完整堆栈所在的文件，允许为 null（表示没能留下文件）
     */
    static String buildMessage(String headline, Throwable failure, Path detailFile) {
        String nl = System.lineSeparator();
        StringBuilder text = new StringBuilder();

        text.append(headline).append(nl).append(nl);

        text.append(Messages.get("desktop.failure.reason")).append(nl);
        text.append(describe(failure)).append(nl);

        Throwable root = rootCause(failure);
        if (root != failure) {
            text.append(nl).append(Messages.get("desktop.failure.rootCause")).append(nl);
            text.append(describe(root)).append(nl);
        }

        text.append(nl);
        if (detailFile == null) {
            text.append(Messages.get("desktop.failure.screenshotHint"));
        } else {
            text.append(Messages.get("desktop.failure.detailLog")).append(nl).append(detailFile);
        }
        return text.toString();
    }

    /**
     * 把异常描述成一行。
     *
     * <p>刻意保留类的<b>全名</b>而不是只取 message：只为 {@code NullPointerException}
     * 这类没有 message 的异常，光看类型名才知道发生了什么。</p>
     */
    static String describe(Throwable failure) {
        if (failure == null) {
            return Messages.get("desktop.failure.noInfo");
        }
        String name = failure.getClass().getName();
        String message = failure.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }

    /**
     * 追到异常链的末端。
     *
     * <p>Spring 抛出来的几乎总是包装过的异常（{@code BeanCreationException} 包着真正的原因），
     * 只显示最外层那一层，用户看到的会是一句毫无信息量的「创建 bean 失败」。</p>
     *
     * <p>循环引用时靠深度上限退出：{@code getCause()} 可以被子类改写成任意行为，
     * 不能假设它一定能走到 null。</p>
     */
    static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && current != null; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return current;
    }

    // ==================== 告知 ====================

    static boolean isHeadless() {
        try {
            return GraphicsEnvironment.isHeadless();
        } catch (RuntimeException e) {
            // AWT 起不来时按「没有图形界面」处理，走标准错误兜底
            return true;
        }
    }

    private static void showDialog(String title, String message, Path logDirectory) {
        if (SwingUtilities.isEventDispatchThread()) {
            // invokeAndWait 在 EDT 上会直接抛 Error，不能无脑调用
            showDialogNow(title, message, logDirectory);
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> showDialogNow(title, message, logDirectory));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println(message);
        } catch (InvocationTargetException | RuntimeException e) {
            System.err.println(message);
        }
    }

    private static void showDialogNow(String title, String message, Path logDirectory) {
        try {
            Object[] options = {buttonOpenLogs(), buttonConfirm()};
            int choice = JOptionPane.showOptionDialog(
                    null, message, title,
                    JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE,
                    null, options, buttonConfirm());

            if (choice == 0 && logDirectory != null) {
                openDirectory(logDirectory);
            }
        } catch (RuntimeException e) {
            // HeadlessException、AWT 初始化失败等都落在这里。
            // 这是最后一道防线，退到标准错误，绝不能再往上抛
            System.err.println(message);
        }
    }

    private static void openDirectory(Path directory) {
        // 复用 BrowserOpener：file:// 形式的 URI 交给系统默认程序处理 ——
        // Windows 打开资源管理器、macOS 打开 Finder、Linux 打开文件管理器
        if (!BrowserOpener.open(directory.toUri().toString())) {
            log.warn("无法自动打开日志文件夹，请手动访问：{}", directory);
        }
    }
}
