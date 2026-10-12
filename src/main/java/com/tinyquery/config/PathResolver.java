package com.tinyquery.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 解析 TinyQuery 的用户数据目录。
 *
 * <p>锁文件（单实例）、日志等「程序自己的东西」都必须落在这里，原因是两条硬约束：</p>
 * <ul>
 *   <li>不能放程序安装目录旁边 —— jpackage 装到 {@code C:\Program Files} 后该目录对普通用户只读；</li>
 *   <li>不能放当前工作目录（{@code .}）—— 用户从桌面双击、从开始菜单、从命令行启动时，
 *       工作目录是三个不同的地方，今天写的文件明天就找不到。</li>
 * </ul>
 *
 * <p>三个平台各按自己的规范存放：Windows 用 {@code %APPDATA%}，
 * macOS 用 {@code ~/Library/Application Support}，Linux 用 XDG 的
 * {@code $XDG_DATA_HOME}（未设置时退回 {@code ~/.local/share}）。</p>
 *
 * <p>设置环境变量 {@code TINYQUERY_HOME} 可整体覆盖，指向哪里就用哪里，
 * 不再追加应用目录名 —— 便于测试与便携部署。</p>
 */
public final class PathResolver {

    // 三个平台统一使用这一个目录名
    private static final String APP_DIR_NAME = "TinyQuery";

    // 覆盖用户数据目录的环境变量
    private static final String HOME_ENV = "TINYQUERY_HOME";

    // 锁文件名。单实例保护（SingleInstanceGuard）使用
    private static final String LOCK_FILE_NAME = "tinyquery.lock";

    // 日志子目录名。启动失败的反馈与事后排查都靠它
    private static final String LOG_DIR_NAME = "logs";

    // 日志文件名。启动失败的反馈与事后排查都靠它
    private static final String LOG_FILE_NAME = "tinyquery.log";

    // 数据源配置文件名。数据源持久化（DataSourceStore）使用
    private static final String DATA_FILE_NAME = "datasources.json";

    private PathResolver() {
        // 工具类，不实例化
    }

    /**
     * 用户数据目录，不存在则创建。
     *
     * @return 形如 {@code C:\Users\Lenovo\AppData\Roaming\TinyQuery} 的绝对路径
     * @throws UncheckedIOException 目录无法创建（磁盘满、权限不足等）
     */
    public static Path getDataDir() {
        Path dir = resolveDataDir(
                System.getProperty("os.name", ""),
                System.getenv(HOME_ENV),
                System.getenv("APPDATA"),
                System.getenv("XDG_DATA_HOME"),
                System.getProperty("user.home"));
        createDirectories(dir);
        return dir;
    }

    /**
     * 单实例锁文件路径。仅给出路径，不创建文件 —— 加锁由 SingleInstanceGuard 负责。
     */
    public static Path getLockFile() {
        return getDataDir().resolve(LOCK_FILE_NAME);
    }

    /**
     * 日志目录，不存在则创建。
     */
    public static Path getLogDir() {
        Path dir = getDataDir().resolve(LOG_DIR_NAME);
        createDirectories(dir);
        return dir;
    }

    /**
     * 日志文件路径。日志目录不存在时一并创建。
     *
     * <p>把文件名收在这里、而不是让调用方各自拼，是因为它有两个使用方：
     * 启动类要把它设进 {@code logging.file.name}，失败提示要把它显示给用户。
     * 两处必须指向同一个文件 —— 否则用户按提示去翻日志会扑空。</p>
     */
    public static Path getLogFile() {
        return getLogDir().resolve(LOG_FILE_NAME);
    }

    /**
     * 数据源配置文件路径。仅给出路径，不创建文件 —— 创建由 DataSourceStore 负责。
     *
     * <p>文件不存在是完全正常的状态（首次启动、用户从未注册过数据源），
     * 调用方必须把它当作「没有已保存的数据源」而不是错误。</p>
     */
    public static Path getDataSourceFile() {
        return getDataDir().resolve(DATA_FILE_NAME);
    }

    /**
     * 由操作系统名与环境变量推出用户数据目录。
     *
     * <p>这是一个纯函数：所有输入都从参数传入，不读系统环境、不碰文件系统。
     * 这样测试可以直接喂 {@code "Mac OS X"}、{@code "Linux"} 等值，
     * 在任意一台机器上验证全部三个平台分支 —— 否则另外两个分支永远跑不到，
     * 等于「写了但从没执行过」的代码。</p>
     *
     * <p>包可见，仅用于测试；生产代码请走 {@link #getDataDir()}。</p>
     *
     * @param osName        {@code os.name} 系统属性
     * @param override      {@code TINYQUERY_HOME}，非空时直接采用且不再追加应用目录名
     * @param appData       Windows 的 {@code APPDATA}
     * @param xdgDataHome  Linux 的 {@code XDG_DATA_HOME}
     * @param userHome     {@code user.home} 系统属性
     * @return 用户数据目录，可能尚不存在
     */
    static Path resolveDataDir(String osName, String override, String appData,
                               String xdgDataHome, String userHome) {
        if (isNotBlank(override)) {
            return Path.of(override);
        }

        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        Path home = Path.of(userHome == null ? "" : userHome);

        if (os.contains("windows")) {
            // 判据必须是完整的 "windows" 而不是 "win"：
            // macOS 的 os.name 可能是 "Darwin"，而 "darwin" 里恰好含有 "win"，
            // 用 "win" 会把 macOS 误判成 Windows。此坑由 PathResolverTest 捕获。
            // 另外 APPDATA 在个别服务化运行场景下可能缺失，此时按 Windows 的既定位置兜底
            Path roaming = isNotBlank(appData) ? Path.of(appData) : home.resolve("AppData/Roaming");
            return roaming.resolve(APP_DIR_NAME);
        }

        if (os.contains("mac") || os.contains("darwin")) {
            return home.resolve("Library/Application Support").resolve(APP_DIR_NAME);
        }

        // Linux（以及 nix / nux 之类的别名）。
        // 未知系统也走这里：退回一个可写目录，比抛异常让程序起不来更稳
        Path dataHome = isNotBlank(xdgDataHome) ? Path.of(xdgDataHome) : home.resolve(".local/share");
        return dataHome.resolve(APP_DIR_NAME);
    }

    private static void createDirectories(Path dir) {
        try {
            // createDirectories 对已存在的目录是幂等的，不会抛异常
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建用户数据目录：" + dir, e);
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
