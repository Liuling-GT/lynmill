package com.tinyquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PathResolver 用户数据目录解析")
class PathResolverTest {

    // 测试只调用纯函数 resolveDataDir，因此不会在真实磁盘上创建任何目录。
    // 期望值一律用 Path.resolve 现拼，保证在 Windows / macOS / Linux 上跑出的结果一致
    private static final String WIN_HOME = "C:\\Users\\tester";
    private static final String WIN_APPDATA = "C:\\Users\\tester\\AppData\\Roaming";
    private static final String UNIX_HOME = "/home/tester";
    private static final String MAC_HOME = "/Users/tester";

    // ==================== Windows ====================

    @Test
    @DisplayName("Windows 优先使用 APPDATA")
    void windowsPrefersAppData() {
        Path actual = PathResolver.resolveDataDir("Windows 10", null, WIN_APPDATA, null, WIN_HOME);

        assertEquals(Path.of(WIN_APPDATA).resolve("TinyQuery"), actual);
    }

    @Test
    @DisplayName("Windows 缺 APPDATA 时退回 AppData\\Roaming，不抛异常")
    void windowsFallsBackWhenAppDataMissing() {
        Path actual = PathResolver.resolveDataDir("Windows 10", null, null, null, WIN_HOME);

        assertEquals(Path.of(WIN_HOME).resolve("AppData/Roaming").resolve("TinyQuery"), actual);
    }

    @Test
    @DisplayName("APPDATA 是空白串时按缺失处理，不能拼出空路径")
    void blankAppDataTreatedAsMissing() {
        Path actual = PathResolver.resolveDataDir("Windows 10", null, "   ", null, WIN_HOME);

        assertEquals(Path.of(WIN_HOME).resolve("AppData/Roaming").resolve("TinyQuery"), actual);
    }

    // ==================== macOS ====================

    @Test
    @DisplayName("macOS 使用 ~/Library/Application Support")
    void macUsesApplicationSupport() {
        Path actual = PathResolver.resolveDataDir("Mac OS X", null, null, null, MAC_HOME);

        assertEquals(Path.of(MAC_HOME).resolve("Library/Application Support").resolve("TinyQuery"), actual);
    }

    @Test
    @DisplayName("os.name 为 Darwin 时同样识别为 macOS")
    void darwinRecognizedAsMac() {
        Path actual = PathResolver.resolveDataDir("Darwin", null, null, null, MAC_HOME);

        assertEquals(Path.of(MAC_HOME).resolve("Library/Application Support").resolve("TinyQuery"), actual);
    }

    // ==================== Linux ====================

    @Test
    @DisplayName("Linux 优先使用 XDG_DATA_HOME")
    void linuxPrefersXdgDataHome() {
        Path actual = PathResolver.resolveDataDir("Linux", null, null, "/data/tester", UNIX_HOME);

        assertEquals(Path.of("/data/tester").resolve("TinyQuery"), actual);
    }

    @Test
    @DisplayName("Linux 未设 XDG_DATA_HOME 时退回 ~/.local/share")
    void linuxFallsBackToLocalShare() {
        Path actual = PathResolver.resolveDataDir("Linux", null, null, null, UNIX_HOME);

        assertEquals(Path.of(UNIX_HOME).resolve(".local/share").resolve("TinyQuery"), actual);
    }

    @Test
    @DisplayName("XDG_DATA_HOME 是空白串时按未设置处理")
    void blankXdgTreatedAsUnset() {
        Path actual = PathResolver.resolveDataDir("Linux", null, null, "", UNIX_HOME);

        assertEquals(Path.of(UNIX_HOME).resolve(".local/share").resolve("TinyQuery"), actual);
    }

    // ==================== 未知系统 ====================

    @Test
    @DisplayName("未知系统走 Linux 分支兜底，不让程序起不来")
    void unknownOsFallsBackToLinuxLayout() {
        Path actual = PathResolver.resolveDataDir("FreeBSD", null, null, null, UNIX_HOME);

        assertEquals(Path.of(UNIX_HOME).resolve(".local/share").resolve("TinyQuery"), actual);
    }

    // ==================== TINYQUERY_HOME 覆盖 ====================

    @Test
    @DisplayName("TINYQUERY_HOME 优先于平台规则，且不再追加 TinyQuery")
    void overrideWinsAndIsNotNested() {
        Path actual = PathResolver.resolveDataDir(
                "Windows 10", "D:\\tinyquery-home", WIN_APPDATA, null, WIN_HOME);

        assertEquals(Path.of("D:\\tinyquery-home"), actual);
    }

    @Test
    @DisplayName("TINYQUERY_HOME 为空白串时视为未设置，回落到平台规则")
    void blankOverrideIsIgnored() {
        Path actual = PathResolver.resolveDataDir("Windows 10", "  ", WIN_APPDATA, null, WIN_HOME);

        assertEquals(Path.of(WIN_APPDATA).resolve("TinyQuery"), actual);
    }

    // ==================== 核心契约 ====================

    @Test
    @DisplayName("三个平台解析出的目录名都叫 TinyQuery —— 这是日志与锁文件路径稳定的前提")
    void allPlatformsShareSameDirName() {
        Path win = PathResolver.resolveDataDir("Windows 10", null, WIN_APPDATA, null, WIN_HOME);
        Path mac = PathResolver.resolveDataDir("Mac OS X", null, null, null, MAC_HOME);
        Path linux = PathResolver.resolveDataDir("Linux", null, null, null, UNIX_HOME);

        assertEquals("TinyQuery", win.getFileName().toString());
        assertEquals("TinyQuery", mac.getFileName().toString());
        assertEquals("TinyQuery", linux.getFileName().toString());
    }

    // ==================== 真实环境（唯一会写磁盘的测试） ====================

    @Test
    @DisplayName("真实环境下 getDataDir 会创建目录，锁文件与日志目录都在其下")
    void realEnvironmentCreatesDataDir() {
        // 本用例刻意走生产路径（读真实环境变量 + 建目录），
        // 因此会在当前用户的真实数据目录下创建 TinyQuery 与 TinyQuery/logs。
        // 这是覆盖「环境变量读取 → 目录创建」这段接线的唯一方式：
        // 上面所有用例都只调纯函数，碰不到这部分
        Path dataDir = PathResolver.getDataDir();

        assertTrue(Files.isDirectory(dataDir), "数据目录应被创建：" + dataDir);
        assertTrue(PathResolver.getLockFile().startsWith(dataDir),
                "锁文件必须位于数据目录内：" + PathResolver.getLockFile());
        assertTrue(PathResolver.getLogDir().startsWith(dataDir),
                "日志目录必须位于数据目录内：" + PathResolver.getLogDir());
        assertTrue(Files.isDirectory(PathResolver.getLogDir()), "日志目录应被创建");
    }

    @Test
    @DisplayName("日志文件是日志目录下的 tinyquery.log —— 启动类与失败提示必须指向同一个文件")
    void logFileSitsDirectlyUnderLogDir() {
        Path logFile = PathResolver.getLogFile();

        assertEquals(PathResolver.getLogDir(), logFile.getParent(), "日志文件应直接位于日志目录下");
        assertEquals("tinyquery.log", logFile.getFileName().toString());
    }
}
