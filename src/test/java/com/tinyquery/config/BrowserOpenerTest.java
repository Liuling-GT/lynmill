package com.tinyquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BrowserOpener 浏览器打开命令")
class BrowserOpenerTest {

    private static final String URL = "http://localhost:8080";

    // ==================== Windows ====================

    @Test
    @DisplayName("Windows 使用 rundll32，且 url.dll,FileProtocolHandler 必须是单个参数")
    void windowsUsesRundll32() {
        assertArrayEquals(
                new String[]{"rundll32", "url.dll,FileProtocolHandler", URL},
                BrowserOpener.buildCommand("Windows 10", URL));
    }

    @Test
    @DisplayName("Windows 11 / Server 同样识别")
    void otherWindowsVersions() {
        assertArrayEquals(new String[]{"rundll32", "url.dll,FileProtocolHandler", URL},
                BrowserOpener.buildCommand("Windows 11", URL));
        assertArrayEquals(new String[]{"rundll32", "url.dll,FileProtocolHandler", URL},
                BrowserOpener.buildCommand("Windows Server 2022", URL));
    }

    // ==================== macOS ====================

    @Test
    @DisplayName("macOS 使用 open")
    void macUsesOpen() {
        assertArrayEquals(new String[]{"open", URL},
                BrowserOpener.buildCommand("Mac OS X", URL));
    }

    @Test
    @DisplayName("os.name 为 Darwin 时走 macOS，不能被 \"win\" 判据截胡")
    void darwinIsNotMistakenForWindows() {
        // "darwin" 里含有 "win"。若判据写成 contains("win")，
        // 这里会错误地返回 rundll32 —— 与 PathResolver 上踩过的是同一个坑
        assertArrayEquals(new String[]{"open", URL},
                BrowserOpener.buildCommand("Darwin", URL));
    }

    // ==================== Linux ====================

    @Test
    @DisplayName("Linux 使用 xdg-open")
    void linuxUsesXdgOpen() {
        assertArrayEquals(new String[]{"xdg-open", URL},
                BrowserOpener.buildCommand("Linux", URL));
    }

    // ==================== 其他 ====================

    @Test
    @DisplayName("无法识别的系统返回 null，交由 Desktop 兜底")
    void unknownOsReturnsNull() {
        assertNull(BrowserOpener.buildCommand("FreeBSD", URL));
        assertNull(BrowserOpener.buildCommand("", URL));
        assertNull(BrowserOpener.buildCommand(null, URL));
    }

    @Test
    @DisplayName("大小写不影响识别")
    void caseInsensitive() {
        assertArrayEquals(new String[]{"rundll32", "url.dll,FileProtocolHandler", URL},
                BrowserOpener.buildCommand("WINDOWS 10", URL));
        assertArrayEquals(new String[]{"open", URL},
                BrowserOpener.buildCommand("MAC OS X", URL));
    }

    // ==================== 核心契约 ====================

    @Test
    @DisplayName("URL 始终是独立的一个参数，不被拼进命令名")
    void urlIsAlwaysSeparateArgument() {
        // 段数因平台而异（Windows 三段，macOS / Linux 两段），
        // 真正的不变量是「URL 自成一段、且不混在命令名里」
        for (String os : new String[]{"Windows 10", "Mac OS X", "Linux"}) {
            String[] command = BrowserOpener.buildCommand(os, URL);

            assertEquals(URL, command[command.length - 1], os + " 的 URL 必须是最后一段");
            assertFalse(command[0].contains(URL), os + " 的命令名里不该混入 URL");
        }
    }

    @Test
    @DisplayName("空地址直接拒绝，不启动任何进程")
    void blankUrlRejected() {
        assertFalse(BrowserOpener.open(null));
        assertFalse(BrowserOpener.open(""));
        assertFalse(BrowserOpener.open("   "));
    }

    // ==================== 退出码检验：「假成功」的修复 ====================

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    /** 一个能启动、但立刻以非 0 退出码失败的命令 */
    private static String[] failingCommand() {
        return isWindows()
                ? new String[]{"cmd", "/c", "exit", "1"}
                : new String[]{"/bin/false"};
    }

    /** 一个正常退出（退出码 0）的命令 */
    private static String[] succeedingCommand() {
        return isWindows()
                ? new String[]{"cmd", "/c", "exit", "0"}
                : new String[]{"/bin/true"};
    }

    /** 一个必然还会继续存活一段时间的命令，用来走「仍在运行」那条分支 */
    private static String[] stillRunningCommand() {
        return isWindows()
                ? new String[]{"cmd", "/c", "ping", "-n", "5", "127.0.0.1"}
                : new String[]{"sleep", "5"};
    }

    @Test
    @DisplayName("命令以非 0 退出码失败时必须返回 false —— 这是 Linux「假成功」的修复点")
    void nonZeroExitIsFailure() {
        // 修复前的实现只看「进程有没有启动起来」，于是 /bin/false 这种
        // 「启动成功但立刻失败」的命令会被判成成功：
        // 浏览器没打开、日志却说打开了，Desktop 那条退路永远轮不到。
        // Linux 上没有设置默认浏览器时，xdg-open 正是这个行为。
        assertFalse(BrowserOpener.runAndCheck(failingCommand()));
    }

    @Test
    @DisplayName("命令以 0 退出算成功")
    void zeroExitIsSuccess() {
        assertTrue(BrowserOpener.runAndCheck(succeedingCommand()));
    }

    @Test
    @DisplayName("宽限时间内仍在运行算成功，且不能把它杀掉")
    void stillRunningCountsAsSuccess() {
        // 真实的 xdg-open 可能把请求交给浏览器后自己继续存活。
        // 这种情况必须算成功：destroy 掉它，杀掉的可能正是用户的浏览器。
        // 传 0 秒宽限，保证走到那条分支
        assertTrue(BrowserOpener.runAndCheck(stillRunningCommand(), 0));
    }

    @Test
    @DisplayName("命令不存在时返回 false，不抛异常")
    void missingCommandIsFailure() {
        assertFalse(BrowserOpener.runAndCheck(
                new String[]{"tinyquery-definitely-not-a-real-command"}));
    }

    // ==================== 失败时的补救指引 ====================

    @Test
    @DisplayName("Linux 给出可执行的默认浏览器设置指引")
    void linuxHasActionableHint() {
        String hint = BrowserOpener.manualHint("Linux");
        assertFalse(hint.isBlank(), "Linux 上两条路会一起失败，必须给指引");
        assertTrue(hint.contains("xdg-settings"), "指引里要带具体命令，实际是：" + hint);
    }

    @Test
    @DisplayName("Windows / macOS 不需要额外指引")
    void otherPlatformsHaveNoHint() {
        // 它们的 URL 关联由系统写死，几乎不会两条路一起失败
        assertEquals("", BrowserOpener.manualHint("Windows 10"));
        assertEquals("", BrowserOpener.manualHint("Mac OS X"));
        assertEquals("", BrowserOpener.manualHint("Darwin"));
        assertEquals("", BrowserOpener.manualHint(null));
    }
}
