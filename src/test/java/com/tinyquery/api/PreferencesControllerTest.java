package com.tinyquery.api;

import com.tinyquery.config.PathResolver;
import com.tinyquery.desktop.DesktopTheme;
import com.tinyquery.i18n.DesktopLocale;
import com.tinyquery.i18n.Messages;
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

/**
 * 语言与主题的同步接口。
 *
 * <p>这些用例直接调 controller 方法而不是走 {@code MockMvc}：
 * 本接口的逻辑全在「切换 → 落盘 → 通知桌面端」这条链上，
 * 没有参数校验、没有状态码分支，起一个 HTTP 栈只是把测试变慢。
 * 序列化与路由由 Spring 负责，那是框架的事，不是这里要测的。</p>
 *
 * <p>语言与主题放在同一个类里，是因为它们共用
 * {@code DesktopSyncService} 这一条通知链 —— 最该防的缺陷正是
 * 「切一个顺手把另一个也动了」，拆成两个类反而测不到这一点。</p>
 */
@DisplayName("PreferencesController 语言与主题同步")
class PreferencesControllerTest {

    private String savedLocale;
    private String savedTheme;

    @BeforeEach
    void rememberState() {
        savedLocale = Messages.desktopLocale();
        savedTheme = DesktopTheme.current();
    }

    @AfterEach
    void restoreState() throws IOException {
        Messages.setDesktopLocale(savedLocale);
        // 主题也要还原，并清掉用例写出来的 theme.txt ——
        // 只还原内存的话，下一个用例的 apply() 会读到上一个用例留下的文件
        DesktopTheme.switchTo(savedTheme);
        Files.deleteIfExists(PathResolver.getDataDir().resolve(DesktopTheme.FILE_NAME));
    }

    private static PreferencesController controller() {
        return new PreferencesController(new com.tinyquery.desktop.DesktopSyncService());
    }

    // ==================== 读写 ====================

    @Test
    @DisplayName("切到英文后，读取接口返回 en")
    void setThenGetReturnsEnglish() {
        PreferencesController c = controller();

        c.setLocale(new PreferencesController.LocaleRequest("en"));

        assertEquals(Messages.EN, Messages.desktopLocale());
        assertEquals(Messages.EN, c.getLocale().data().get("locale"));
    }

    @Test
    @DisplayName("切回中文后，读取接口返回 zh")
    void setThenGetReturnsChinese() {
        PreferencesController c = controller();

        c.setLocale(new PreferencesController.LocaleRequest("en"));
        c.setLocale(new PreferencesController.LocaleRequest("zh"));

        assertEquals(Messages.ZH, Messages.desktopLocale());
        assertEquals(Messages.ZH, c.getLocale().data().get("locale"));
    }

    @Test
    @DisplayName("响应体里回传的是实际生效的语言，不是请求值")
    void responseEchoesAppliedNotRequested() {
        // 前端拿这个回执更新本地状态，回传请求值会在
        // 「请求了认不出的值」时让前后端不一致
        PreferencesController c = controller();

        var response = c.setLocale(new PreferencesController.LocaleRequest("klingon"));

        assertEquals(Messages.ZH, response.data().get("locale"),
                "认不出的语言应当回落中文，并把回落后的值回传");
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("请求体为 null、或 locale 为 null 时一律当中文，不报错")
    void nullInputFallsBackToChinese() {
        PreferencesController c = controller();

        assertDoesNotThrow(() -> c.setLocale(null));
        assertEquals(Messages.ZH, Messages.desktopLocale());

        assertDoesNotThrow(() -> c.setLocale(new PreferencesController.LocaleRequest(null)));
        assertEquals(Messages.ZH, Messages.desktopLocale());
    }

    @Test
    @DisplayName("各种英文写法都能识别")
    void englishVariantsAreAccepted() {
        PreferencesController c = controller();

        for (String variant : new String[]{"en", "EN", "en-US", "en_US", "En"}) {
            c.setLocale(new PreferencesController.LocaleRequest(variant));
            assertEquals(Messages.EN, Messages.desktopLocale(), "未识别：" + variant);
        }
    }

    @Test
    @DisplayName("支持的语言列表含中文与英文，且中文在前")
    void localeListIsStable() {
        var list = controller().listLocales().data();

        assertEquals(2, list.size());
        // 顺序即展示顺序，中文在前是默认语言的体现，不该被无意改掉
        assertEquals(Messages.ZH, list.get(0).get("value"));
        assertEquals(Messages.EN, list.get(1).get("value"));
    }

    @Test
    @DisplayName("切完之后，服务端返回的用户可见文案真的变成英文")
    void messagesActuallySwitch() {
        // 这是这个接口存在的意义：让服务端后续返回的文案跟着变。
        // 只断言 desktopLocale 这个字段是不够的 ——
        // 那个字段对、但 Messages.get 没读它的话，用户看到的还是中文
        PreferencesController c = controller();
        c.setLocale(new PreferencesController.LocaleRequest("en"));

        String text = Messages.get("desktop.tray.open");
        assertFalse(text.matches(".*[\\u4e00-\\u9fff].*"),
                "切到英文后文案仍是中文：" + text);
        assertEquals("Open Interface", text);
    }

    // ==================== 持久化 ====================

    @Test
    @DisplayName("切换会把语言真的写进 locale.txt —— 走真实实现，不是复刻规则")
    void switchPersistsToFile() throws IOException {
        // 用真实的用户数据目录跑一次 switchTo，然后读文件验证。
        //
        // 这条最初写成「自己写文件、自己读回来」——那只验证了序列化约定，
        // 把 switchTo 里那句 writeFileQuietly 删掉它照样绿（对照实验实测到的假绿）。
        // 必须真的调 switchTo，才测得到「到底有没有写」。
        //
        // 目录由 pom 里的 surefire 配置指向 target/test-home，
        // 不会碰开发者真实的 %APPDATA%\TinyQuery
        Path file = PathResolver.getDataDir().resolve(DesktopLocale.FILE_NAME);
        Files.deleteIfExists(file);

        DesktopLocale.switchTo(Messages.EN);

        assertTrue(Files.isRegularFile(file),
                "switchTo 没有写 locale.txt，重启后语言会丢失：" + file);
        assertEquals(Messages.EN, Files.readString(file, StandardCharsets.UTF_8).trim());

        // 切回中文应当覆盖掉刚才的 en
        DesktopLocale.switchTo(Messages.ZH);
        assertEquals(Messages.ZH, Files.readString(file, StandardCharsets.UTF_8).trim(),
                "第二次切换没有覆盖旧值");
    }

    @Test
    @DisplayName("落盘的语言能被 apply 读回来 —— 首尾闭环")
    void persistedLocaleIsReadBackByApply() throws IOException {
        // 单独测「写」和「读」都可能出现两边格式对不上的问题，
        // 这条把首尾接起来跑一遍，模拟一次真实的重启
        Path file = PathResolver.getDataDir().resolve(DesktopLocale.FILE_NAME);
        Files.deleteIfExists(file);

        DesktopLocale.switchTo(Messages.EN);

        // 模拟重启：先把内存里的语言抹掉，再让 apply 从文件读
        Messages.setDesktopLocale(Messages.ZH);
        assertEquals(Messages.EN, DesktopLocale.apply(),
                "switchTo 写出去的东西，apply 读不回来 —— 重启后语言会退回默认");
    }

    @Test
    @DisplayName("落盘之后仍能再切回去 —— 不会被文件里的旧值锁死")
    void canSwitchBackAfterPersisting() throws IOException {
        // resolve 是包可见的，直接测它的那条在 DesktopLocaleTest 里。
        // 这里验行为：先落盘 en，再切回 zh 应当覆盖成功
        Path file = PathResolver.getDataDir().resolve(DesktopLocale.FILE_NAME);
        Files.deleteIfExists(file);

        DesktopLocale.switchTo(Messages.EN);
        assertEquals(Messages.ZH, DesktopLocale.switchTo(Messages.ZH),
                "落盘为 en 之后仍应能切回中文");
        assertEquals(Messages.ZH, Files.readString(file, StandardCharsets.UTF_8).trim(),
                "文件里应当已经改成 zh");
    }

    @Test
    @DisplayName("版本号只在语言真的变了时才自增 —— 免得桌面端白白重建")
    void versionOnlyBumpsOnRealChange() {
        PreferencesController c = controller();
        c.setLocale(new PreferencesController.LocaleRequest("zh"));

        long before = DesktopLocale.version();
        // 连着切三次同一个语言
        c.setLocale(new PreferencesController.LocaleRequest("zh"));
        c.setLocale(new PreferencesController.LocaleRequest("zh"));
        assertEquals(before, DesktopLocale.version(),
                "语言没变却自增了版本号，托盘菜单会被无谓地重建");

        c.setLocale(new PreferencesController.LocaleRequest("en"));
        assertTrue(DesktopLocale.version() > before, "语言变了却没自增版本号，桌面端不会刷新");
    }

    // ==================== 主题 ====================

    @Test
    @DisplayName("切主题后，读取接口返回新主题")
    void setThenGetTheme() {
        PreferencesController c = controller();

        c.setTheme(new PreferencesController.ThemeRequest("bamboo"));

        assertEquals("bamboo", DesktopTheme.current());
        assertEquals("bamboo", c.getTheme().data().get("theme"));
    }

    @Test
    @DisplayName("主题响应体里回传的是实际生效的值，不是请求值")
    void themeResponseEchoesAppliedNotRequested() {
        // 与语言同理：前端拿回执更新本地状态，回传请求值会在
        // 「请求了认不出的值」时让前后端不一致
        PreferencesController c = controller();

        var response = c.setTheme(new PreferencesController.ThemeRequest("chartreuse"));

        assertEquals(DesktopTheme.DEFAULT, response.data().get("theme"),
                "认不出的主题应当回落默认，并把回落后的值回传");
    }

    @Test
    @DisplayName("主题请求体为 null、或 theme 为 null 时回落默认主题，不报错")
    void nullThemeFallsBackToDefault() {
        PreferencesController c = controller();

        assertDoesNotThrow(() -> c.setTheme(null));
        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.current());

        assertDoesNotThrow(() -> c.setTheme(new PreferencesController.ThemeRequest(null)));
        assertEquals(DesktopTheme.DEFAULT, DesktopTheme.current());
    }

    @Test
    @DisplayName("支持的主题列表非空、默认主题在前、每项都带强调色")
    void themeListIsStable() {
        var list = controller().listThemes().data();

        assertEquals(4, list.size());
        assertEquals(DesktopTheme.DEFAULT, list.get(0).get("value"),
                "默认主题不在第一位，设置页会把默认主题排在中间");
        // 顺序必须与 DesktopTheme.supported() 一致 —— 设置页按它排色卡
        assertEquals(DesktopTheme.supported(),
                list.stream().map(m -> m.get("value")).toList());

        for (var item : list) {
            String accent = item.get("accent");
            assertNotNull(accent, item.get("value") + " 没有 accent");
            assertTrue(accent.matches("#[0-9a-f]{6}"),
                    item.get("value") + " 的 accent 不是 #rrggbb 形式：" + accent);
        }
    }

    @Test
    @DisplayName("主题列表里的强调色与 DesktopTheme 取到的一致")
    void themeListAccentsMatchDesktopTheme() {
        // 列表里的 accent 是给设置页画色点用的。它要是和实际生效的颜色不同，
        // 用户会看到「色点是绿的、切过去是蓝的」
        for (var item : controller().listThemes().data()) {
            String expected = String.format("#%06x",
                    DesktopTheme.accentOf(item.get("value")).getRGB() & 0xFFFFFF);
            assertEquals(expected, item.get("accent"),
                    item.get("value") + " 的 accent 与实际强调色不一致");
        }
    }

    @Test
    @DisplayName("切主题会把主题真的写进 theme.txt —— 走真实实现")
    void themeSwitchPersistsToFile() throws IOException {
        Path file = PathResolver.getDataDir().resolve(DesktopTheme.FILE_NAME);
        Files.deleteIfExists(file);

        controller().setTheme(new PreferencesController.ThemeRequest("coffee"));

        assertTrue(Files.isRegularFile(file),
                "setTheme 没有写 theme.txt，重启后主题会丢失：" + file);
        assertEquals("coffee", Files.readString(file, StandardCharsets.UTF_8).trim());
    }

    @Test
    @DisplayName("语言与主题互不干扰 —— 切一个不该动另一个")
    void localeAndThemeAreIndependent() throws IOException {
        // 两者共用 DesktopSyncService，很容易写成「切语言顺手把主题也重置了」。
        // 那条路走错的话，用户在设置页切语言会发现颜色也变了
        PreferencesController c = controller();
        c.setTheme(new PreferencesController.ThemeRequest("indigo"));
        long themeVersion = DesktopTheme.version();

        c.setLocale(new PreferencesController.LocaleRequest("en"));

        assertEquals("indigo", DesktopTheme.current(), "切语言把主题也改掉了");
        assertEquals(themeVersion, DesktopTheme.version(), "切语言推进了主题版本号");
        assertEquals(Messages.EN, Messages.desktopLocale());

        // 反过来：切主题不该动语言
        c.setTheme(new PreferencesController.ThemeRequest("coffee"));
        assertEquals(Messages.EN, Messages.desktopLocale(), "切主题把语言也改掉了");
    }
}
