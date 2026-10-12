package com.tinyquery.desktop;

import com.tinyquery.config.PathResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 桌面端主题的来源、持久化与颜色换算。
 *
 * <p>与 {@code DesktopLocaleTest} 同一套思路：{@link DesktopTheme} 存在的理由是
 * Swing 界面读不到 CSS 变量，所以它必须自己记一份颜色，还要跨重启记住。
 * 这里逐条验证那条链。</p>
 *
 * <p>跨文件的一致性（Java 的色值 vs {@code tokens.css} 的 {@code --accent}）
 * 由 {@code ThemeTokensTest} 负责，不在这里重复。</p>
 */
@DisplayName("DesktopTheme 桌面端主题来源")
class DesktopThemeTest {

    private String savedTheme;

    @BeforeEach
    void rememberTheme() {
        savedTheme = DesktopTheme.current();
    }

    @AfterEach
    void restoreTheme() throws IOException {
        // 还原内存与文件两边。只还原内存的话，后面跑到的用例会读到
        // 上一个用例留下的 theme.txt —— 那正是「测试互相污染」的典型
        DesktopTheme.switchTo(savedTheme);
        Files.deleteIfExists(PathResolver.getDataDir().resolve(DesktopTheme.FILE_NAME));
    }

    // ==================== 取值优先级 ====================

    @Test
    @DisplayName("都没有时用默认主题")
    void defaultsToDefaultTheme() {
        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.resolve(null, null));
        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.resolve("", ""));
        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.resolve("   ", "  "));
    }

    @Test
    @DisplayName("只有文件时用文件的值")
    void usesFileWhenNoEnv() {
        assertEquals("coffee", DesktopTheme.resolve(null, "coffee"));
        assertEquals("bamboo", DesktopTheme.resolve(null, "bamboo"));
    }

    @Test
    @DisplayName("环境变量压过文件 —— 显式指定的应当赢")
    void envWinsOverFile() {
        assertEquals("indigo", DesktopTheme.resolve("indigo", "coffee"));
        assertEquals("coffee", DesktopTheme.resolve("coffee", "indigo"));
    }

    @Test
    @DisplayName("环境变量是空白时不算指定，回落到文件")
    void blankEnvFallsBackToFile() {
        assertEquals("bamboo", DesktopTheme.resolve("   ", "bamboo"));
        assertEquals("bamboo", DesktopTheme.resolve("", "bamboo"));
    }

    @Test
    @DisplayName("认不出的值一律回落默认主题，不留脏数据")
    void unknownValueBecomesDefault() {
        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.resolve("chartreuse", null));
        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.resolve(null, "chartreuse"));
        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.normalize(null));
        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.normalize(""));
        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.normalize("  "));
    }

    @Test
    @DisplayName("大小写与首尾空白都能容忍 —— 用户手改文件时很常见")
    void normalizationIsLenientAboutCaseAndSpace() {
        // 与语言那边不同：语言标识是用户可见的自由输入（en-US、EN 都该认），
        // 而主题标识是内部枚举。但 theme.txt 是用户可以手改的，
        // 多一个空格就静默回落默认主题会很难查，所以这里宽容一点
        assertEquals("coffee", DesktopTheme.normalize("COFFEE"));
        assertEquals("coffee", DesktopTheme.normalize("  Coffee  "));
        assertEquals("bamboo", DesktopTheme.normalize("Bamboo"));
    }

    // ==================== 支持的主题 ====================

    @Test
    @DisplayName("支持的主题清单以默认主题打头，且顺序固定")
    void supportedStartsWithDefault() {
        // 顺序即展示顺序：设置页按这个顺序排色卡，桌面端与网页要一致。
        // 默认主题必须排第一 —— 前端 PRESET_MAIN_COLORS 也是这个约定
        var supported = DesktopTheme.supported();
        assertEquals(DesktopTheme.DEFAULT, supported.get(0),
                "默认主题不在第一位，设置页会把默认主题排在中间");
        assertEquals(4, supported.size(), "主题数量变了，前后端清单要一起改");
        assertEquals(supported.stream().distinct().count(), supported.size(),
                "清单里有重复的主题");
    }

    @Test
    @DisplayName("清单里的每个主题都能取到颜色，认不出的取到默认色")
    void accentLookupCoversEveryTheme() {
        for (String theme : DesktopTheme.supported()) {
            assertTrue(DesktopTheme.isSupported(theme), theme + " 不在支持清单里");
            assertNotNull(DesktopTheme.accentOf(theme));
        }
        assertFalse(DesktopTheme.isSupported("chartreuse"));
        assertFalse(DesktopTheme.isSupported(null));

        // 认不出的主题不能抛异常 —— 控制窗口的构造里会调它，
        // 抛出去就连退出入口都没有了
        assertDoesNotThrow(() -> DesktopTheme.accentOf("chartreuse"));
        assertEquals(DesktopTheme.accentOf(DesktopTheme.DEFAULT),
                DesktopTheme.accentOf("chartreuse"));
    }

    @Test
    @DisplayName("每个主题的强调色互不相同 —— 否则换了主题看不出来")
    void accentsAreDistinct() {
        var seen = new java.util.HashSet<Color>();
        for (String theme : DesktopTheme.supported()) {
            assertTrue(seen.add(DesktopTheme.accentOf(theme)),
                    theme + " 的强调色与别的主题重复");
        }
    }

    // ==================== 托盘悬停色 ====================

    @Test
    @DisplayName("托盘悬停色比强调色暗，且四套主题各不相同")
    void trayHoverIsDarkerThanAccent() {
        // 托盘菜单是黑底白字，悬停色必须压得足够暗，否则白字会发飘。
        // 这里只断言「确实更暗」与「各主题不同」，
        // 具体亮度是审美，不该由测试锁死
        for (String theme : DesktopTheme.supported()) {
            DesktopTheme.switchTo(theme);
            Color accent = DesktopTheme.accent();
            Color hover = DesktopTheme.trayHover();

            int accentSum = accent.getRed() + accent.getGreen() + accent.getBlue();
            int hoverSum = hover.getRed() + hover.getGreen() + hover.getBlue();
            assertTrue(hoverSum < accentSum,
                    theme + " 的托盘悬停色不比强调色暗：hover=" + hover + " accent=" + accent);

            // 也不能暗到看不出来 —— 纯黑会和菜单底色糊在一起
            assertTrue(hoverSum > 30,
                    theme + " 的托盘悬停色太暗，在黑底上看不出选中：" + hover);
        }
    }

    @Test
    @DisplayName("悬停色的比例是线性的 —— 直接验算法，不靠肉眼")
    void tintedAppliesRatio() {
        DesktopTheme.switchTo("violet");
        Color base = DesktopTheme.accentOf("violet");
        Color half = DesktopTheme.tinted(0.5);

        assertEquals(Math.round(base.getRed() * 0.5), half.getRed());
        assertEquals(Math.round(base.getGreen() * 0.5), half.getGreen());
        assertEquals(Math.round(base.getBlue() * 0.5), half.getBlue());

        // ratio = 0 就是纯黑，ratio = 1 就是原色
        assertEquals(new Color(0, 0, 0), DesktopTheme.tinted(0));
        assertEquals(base, DesktopTheme.tinted(1.0));
    }

    // ==================== 持久化 ====================

    @Test
    @DisplayName("切换会把主题真的写进 theme.txt —— 走真实实现，不是复刻规则")
    void switchPersistsToFile() throws IOException {
        // 用真实的用户数据目录跑一次 switchTo，再读文件验证。
        // 目录由 pom 里的 surefire 配置指向 target/test-home，
        // 不会碰开发者真实的 %APPDATA%\TinyQuery
        Path file = PathResolver.getDataDir().resolve(DesktopTheme.FILE_NAME);
        Files.deleteIfExists(file);

        DesktopTheme.switchTo("bamboo");

        assertTrue(Files.isRegularFile(file),
                "switchTo 没有写 theme.txt，重启后主题会丢失：" + file);
        assertEquals("bamboo", Files.readString(file, StandardCharsets.UTF_8).trim());

        // 再切一次应当覆盖旧值
        DesktopTheme.switchTo("coffee");
        assertEquals("coffee", Files.readString(file, StandardCharsets.UTF_8).trim(),
                "第二次切换没有覆盖旧值");
    }

    @Test
    @DisplayName("apply 真的去读文件，而不是把内存里的值原样返回")
    void applyActuallyReadsTheFile() throws IOException {
        // 【踩过】第一版写成 switchTo("indigo") → switchTo(DEFAULT) → apply()，
        // 指望 apply 读回 indigo。但 switchTo 自己会写文件，第二句把
        // theme.txt 覆盖成 violet 了，于是断言失败 —— 那是测试写错了，
        // 不是实现有问题。要证明「apply 确实读了文件」，就得让
        // 文件里的值与内存里的值不同：先切主题定住内存，再手改文件
        // （这也正是真实场景 —— theme.txt 是给用户改的），
        // 此时 apply 若返回内存里的值就说明它根本没读文件
        Path file = PathResolver.getDataDir().resolve(DesktopTheme.FILE_NAME);

        DesktopTheme.switchTo("violet");
        assertEquals("violet", DesktopTheme.current(), "前置条件不成立：内存里不是 violet");

        Files.writeString(file, "coffee" + System.lineSeparator(), StandardCharsets.UTF_8);

        assertEquals("coffee", DesktopTheme.apply(),
                "手改 theme.txt 之后 apply 没读到新值 —— 它没有真的读文件");
        assertEquals("coffee", DesktopTheme.current(), "apply 读了文件但没写回内存");
    }

    @Test
    @DisplayName("文件里认不出的值不会让 apply 抛异常，而是回落默认")
    void garbageFileFallsBackToDefault() throws IOException {
        Path file = PathResolver.getDataDir().resolve(DesktopTheme.FILE_NAME);
        Files.writeString(file, "not-a-theme" + System.lineSeparator(), StandardCharsets.UTF_8);

        assertEquals(DesktopTheme.DEFAULT, assertDoesNotThrow(DesktopTheme::apply),
                "theme.txt 里是垃圾值时应当回落默认主题，而不是抛异常");
    }

    @Test
    @DisplayName("版本号只在主题真的变了时才自增 —— 免得桌面端白白重绘")
    void versionOnlyBumpsOnRealChange() {
        DesktopTheme.switchTo("violet");

        long before = DesktopTheme.version();
        // 连着切三次同一个主题
        DesktopTheme.switchTo("violet");
        DesktopTheme.switchTo("violet");
        assertEquals(before, DesktopTheme.version(),
                "主题没变却自增了版本号，托盘菜单与控制窗口会被无谓地重建");

        DesktopTheme.switchTo("bamboo");
        assertTrue(DesktopTheme.version() > before,
                "主题变了却没自增版本号，桌面端不会刷新");
    }

    @Test
    @DisplayName("切到认不出的主题时，落盘的是回落后的值而不是原样写进去")
    void unknownThemePersistsNormalizedValue() throws IOException {
        // 否则 theme.txt 里会留一个永远认不出的值，
        // 下次启动虽然也能回落，但用户去看文件会一头雾水
        Path file = PathResolver.getDataDir().resolve(DesktopTheme.FILE_NAME);
        Files.deleteIfExists(file);

        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.switchTo("chartreuse"));
        assertEquals(DesktopTheme.DEFAULT,
                Files.readString(file, StandardCharsets.UTF_8).trim());
    }
}
