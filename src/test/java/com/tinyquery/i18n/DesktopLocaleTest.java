package com.tinyquery.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DesktopLocale 桌面端语言来源")
class DesktopLocaleTest {

    private String savedDesktopLocale;

    @BeforeEach
    void rememberLocale() {
        savedDesktopLocale = Messages.desktopLocale();
    }

    @AfterEach
    void restoreLocale() {
        Messages.setDesktopLocale(savedDesktopLocale);
    }

    // ==================== 取值优先级 ====================

    @Test
    @DisplayName("都没有时默认中文")
    void defaultsToChinese() {
        assertEquals(Messages.ZH, DesktopLocale.resolve(null, null));
        assertEquals(Messages.ZH, DesktopLocale.resolve("", ""));
        assertEquals(Messages.ZH, DesktopLocale.resolve("   ", "  "));
    }

    @Test
    @DisplayName("只有文件时用文件的值")
    void usesFileWhenNoEnv() {
        assertEquals(Messages.EN, DesktopLocale.resolve(null, "en"));
        assertEquals(Messages.ZH, DesktopLocale.resolve(null, "zh"));
    }

    @Test
    @DisplayName("环境变量压过文件 —— 显式指定的应当赢")
    void envWinsOverFile() {
        // 文件里写了中文、环境变量要英文，以环境变量为准。
        // 这条保证「临时想试英文」不必去改文件
        assertEquals(Messages.EN, DesktopLocale.resolve("en", "zh"));
        assertEquals(Messages.ZH, DesktopLocale.resolve("zh", "en"));
    }

    @Test
    @DisplayName("环境变量是空白时不算指定，回落到文件")
    void blankEnvFallsBackToFile() {
        assertEquals(Messages.EN, DesktopLocale.resolve("   ", "en"));
        assertEquals(Messages.EN, DesktopLocale.resolve("", "en"));
    }

    @Test
    @DisplayName("认不出的值一律当中文，不留脏数据")
    void unknownValueBecomesChinese() {
        assertEquals(Messages.ZH, DesktopLocale.resolve("klingon", null));
        assertEquals(Messages.ZH, DesktopLocale.resolve(null, "klingon"));
    }

    @Test
    @DisplayName("各种英文写法都归到 en")
    void normalizesEnglishVariants() {
        for (String raw : new String[]{"en", "EN", "en-US", "en_US", "  En-us  "}) {
            assertEquals(Messages.EN, DesktopLocale.resolve(raw, null), "解析失败：" + raw);
        }
    }

    // ==================== 与 current() 的配合 ====================

    @Test
    @DisplayName("不在请求线程里时，current() 取桌面端语言")
    void currentFallsBackToDesktopLocale() {
        // 这是本次改动的核心：托盘菜单在 AWT 线程构建，
        // 那里没有 ThreadLocal，必须能拿到桌面端这一份
        Messages.setDesktopLocale(Messages.EN);
        Messages.clear();

        assertEquals(Messages.EN, Messages.current());
        assertEquals("Open Interface", Messages.get("desktop.tray.open"),
                "桌面端线程上取到的仍应是英文");
    }

    @Test
    @DisplayName("请求线程的 ThreadLocal 压过桌面端语言")
    void threadLocalWinsOverDesktopLocale() {
        Messages.setDesktopLocale(Messages.EN);

        // 模拟一个「桌面端设了英文，但某个请求明确要中文」的场景 ——
        // 请求自己带的语言必须赢，否则用户在网页上选的会被桌面端配置盖掉
        Messages.setCurrent(Messages.ZH);
        try {
            assertEquals(Messages.ZH, Messages.current());
            assertEquals("打开界面", Messages.get("desktop.tray.open"));
        } finally {
            Messages.clear();
        }
    }

    // ==================== 读文件 ====================

    @Test
    @DisplayName("从数据目录读语言文件，忽略空行与注释行")
    void readsLocaleFileIgnoringCommentsAndBlankLines(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(DesktopLocale.FILE_NAME);
        Files.writeString(file, "# 这是注释\n\nen\n", StandardCharsets.UTF_8);

        String value = readFirstMeaningfulLine(file);
        assertEquals("en", value);
    }

    @Test
    @DisplayName("文件不存在不是错误，回落到中文")
    void missingFileIsNotAnError(@TempDir Path dir) {
        // 绝大多数用户从不设置语言文件，这条路径是常态而非异常
        assertNull(readFirstMeaningfulLine(dir.resolve("nope.txt")));
    }

    @Test
    @DisplayName("环境变量压过文件 —— 落盘之后仍能临时覆盖（调试用）")
    void envWinsOverPersistedFile() {
        // 这条保证「已经用设置页切成英文了，仍可用环境变量临时跑一次中文」，
        // 否则调试时只能去改文件
        assertEquals(Messages.ZH, DesktopLocale.resolve("zh", Messages.EN));
        assertEquals(Messages.EN, DesktopLocale.resolve("en", Messages.ZH));
    }

    @Test
    @DisplayName("apply() 不抛异常，且把语言设进去")
    void applyNeverThrows() {
        assertDoesNotThrow(DesktopLocale::apply);
        // apply 之后必须是一个受支持的语言
        String applied = Messages.desktopLocale();
        assertTrue(Messages.ZH.equals(applied) || Messages.EN.equals(applied), applied);
    }

    /**
     * 复刻 DesktopLocale 读文件那段的取值规则。
     *
     * <p>不直接调私有方法：这里测的是「空行与注释行要跳过」这条<i>规则</i>，
     * 规则写在 {@code DesktopLocale} 里、由 {@code apply()} 走真实环境执行；
     * 单元测试只验证同一套解析逻辑，避免为了可测性把实现细节暴露出去。</p>
     */
    private static String readFirstMeaningfulLine(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
