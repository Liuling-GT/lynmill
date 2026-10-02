package com.tinyquery.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 平台选择逻辑的测试（对应交接文档 5.3 的坑 ②）。
 *
 * <p>这里验的核心不是「选得对不对」，而是<b>「任何情况下都恰好只有一个
 * CredentialStore Bean」</b>。原来的写法是每个实现各挂一个
 * {@code @ConditionalOnProperty(matchIfMissing = true)}，属性缺省时会在 macOS 上
 * 同时创建 Windows 和 macOS 两个实现，而 {@code DataSourceManager} 是按类型注入
 * 单个的 → 抛 {@code NoUniqueBeanDefinitionException} → <b>程序直接起不来</b>。</p>
 *
 * <p>这类错误在编译期完全看不出来，只有真的启动一次才知道，所以要有测试盯住。</p>
 */
@DisplayName("CredentialStoreConfig · 按平台选择（坑 ②）")
class CredentialStoreConfigTest {

    private final CredentialStoreConfig config = new CredentialStoreConfig();

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    // ==================== 显式覆盖 ====================

    @Test
    @DisplayName("显式配置优先于平台判断")
    void explicitOverrideWins() {
        assertInstanceOf(NoOpCredentialStore.class, config.credentialStore("none"),
                "none 应当显式关掉保存密码");
        assertInstanceOf(WindowsCredentialStore.class, config.credentialStore("windows"));
        assertInstanceOf(MacCredentialStore.class, config.credentialStore("mac"));
    }

    @Test
    @DisplayName("取值大小写与空格不敏感")
    void overrideIsNormalized() {
        assertInstanceOf(MacCredentialStore.class, config.credentialStore("  MAC  "));
        assertInstanceOf(NoOpCredentialStore.class, config.credentialStore("None"));
    }

    // ==================== 自动判断 ====================

    @Test
    @DisplayName("不指定属性时按操作系统自动选")
    void autoDetectByOs() {
        CredentialStore chosen = config.credentialStore("");

        assertNotNull(chosen, "无论如何都必须有一个实现，否则 DataSourceManager 注入失败");
        if (os().contains("mac") || os().contains("darwin")) {
            assertInstanceOf(MacCredentialStore.class, chosen,
                    "本机是 macOS，应当选 macOS 实现（而不是像改之前那样选 Windows 的然后置灰）");
        } else if (os().contains("windows")) {
            assertInstanceOf(WindowsCredentialStore.class, chosen,
                    "Windows 上的行为必须与改动前完全一致");
        } else {
            assertInstanceOf(NoOpCredentialStore.class, chosen,
                    "其它平台应当优雅退化");
        }
    }

    // ==================== 坑 ② 的回归保护 ====================

    @Test
    @DisplayName("★ 属性缺省时也只产生一个实现 —— 不会再出现两个 Bean 冲突")
    void exactlyOneImplementationWhenPropertyMissing() {
        // 之所以用「多次调用返回同一个类型」来验：真正会导致启动失败的是
        // Spring 容器里同时存在两个 CredentialStore 类型的 Bean。
        // 这里能保证的是「选择函数是单值的、不会同时选中两个」
        CredentialStore first = config.credentialStore(null);
        CredentialStore second = config.credentialStore("");

        assertEquals(first.getClass(), second.getClass(),
                "属性为 null 或空串必须得到同样的结果，不能一个走默认一个走平台判断");
    }

    @Test
    @DisplayName("macOS 分支选出来的是 MacCredentialStore，不是 Windows 那个")
    void macBranchDoesNotPickWindowsImplementation() {
        CredentialStore chosen = config.credentialStore("mac");

        assertInstanceOf(MacCredentialStore.class, chosen);
        assertFalse(chosen instanceof WindowsCredentialStore,
                "这正是坑 ② 的表现：属性缺省时选到了 Windows 实现，于是 macOS 上功能等于白写");
    }

    // ==================== 退化 ====================

    @Test
    @DisplayName("无法识别的取值不静默关掉功能，而是退回按平台判断")
    void unknownValueFallsBackToPlatform() {
        // 故意写一个不存在的值。若实现是「不认识就 NoOp」，用户一个笔误
        // 就会让「保存密码」永久变灰，而且日志里只有一行警告，很难查
        CredentialStore chosen = config.credentialStore("linuх");

        assertNotNull(chosen);
        if (os().contains("mac") || os().contains("darwin")) {
            assertInstanceOf(MacCredentialStore.class, chosen,
                    "笔误不该把功能关掉，应当退回平台判断");
        }
    }

    @Test
    @DisplayName("任何取值都不会抛异常 —— 凭据存储的问题不能让程序起不来")
    void neverThrows() {
        for (String v : new String[]{null, "", "  ", "windows", "mac", "none", "MAC",
                "不认识", "linux", "12345"}) {
            assertDoesNotThrow(() -> {
                CredentialStore s = config.credentialStore(v);
                assertNotNull(s);
                assertNotNull(s.getClass());
            }, "取值「" + v + "」不应导致异常");
        }
    }
}
