package com.tinyquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
