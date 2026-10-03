package com.tinyquery.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * 决定用哪个 {@link CredentialStore} 实现：<b>默认按操作系统自动选</b>，
 * 配置属性 {@code tinyquery.credential.store} 仅作为手动覆盖。
 *
 * <h2>为什么必须这样选，而不是每个实现各挂一个 @ConditionalOnProperty</h2>
 *
 * <p>原来的写法是给 {@link WindowsCredentialStore} 挂
 * {@code @ConditionalOnProperty(…, havingValue = "windows", matchIfMissing = true)}。
 * 而 {@code tinyquery.credential.store} 这个属性<b>项目里从来没有被设置过</b>，
 * 全靠 {@code matchIfMissing} 生效。</p>
 *
 * <p>如果照着给 macOS 实现也加一个 {@code matchIfMissing = true} 的条件，
 * 那么在属性缺省（也就是默认情况）下<b>两个 Bean 都会被创建</b>。
 * 而 {@code DataSourceManager} 是按类型注入单个 {@code CredentialStore} 的，
 * 于是 Spring 抛 {@code NoUniqueBeanDefinitionException} ——
 * <b>程序直接起不来</b>，这违反了「可选能力的缺失不能拖垮主流程」这条硬性要求。</p>
 *
 * <p>反过来，如果新实现不加 {@code matchIfMissing}，那 macOS 上默认仍然只会创建
 * Windows 那个（然后因平台不匹配置灰）—— 等于白写。</p>
 *
 * <p>所以把「按属性选」改成「按平台选、属性可覆盖」，并且<b>把三个实现类上的
 * Spring 注解去掉</b>，保证任何情况下这个 Bean 恰好只有一个。</p>
 *
 * <h2>判断平台时的坑</h2>
 *
 * <p><b>不能用 {@code os.name.contains("win")}</b> —— 字符串 {@code "darwin"}
 * 里就含 {@code "win"}，Mac 会被误判成 Windows。必须用 {@code contains("windows")}。
 * macOS 的 {@code os.name} 实测为 {@code "Mac OS X"}，所以同时认 {@code mac} 和
 * {@code darwin} 两种写法更稳。</p>
 *
 * <h2>退化保证</h2>
 *
 * <p>任何一步出问题（属性值不认识、构造抛错）都落到 {@link NoOpCredentialStore}，
 * 它一定可用作 Bean，只是 {@code isAvailable()} 返回 false，界面把「保存密码」
 * 置灰。程序照常启动。</p>
 */
@Configuration
public class CredentialStoreConfig {

    /** 手动覆盖用的配置属性。留空 = 按平台自动判断 */
    public static final String STORE_PROPERTY = "tinyquery.credential.store";

    private static final Logger log = LoggerFactory.getLogger(CredentialStoreConfig.class);

    /**
     * 整个应用唯一的 {@link CredentialStore} Bean。
     *
     * @param override 属性值；属性不存在时 Spring 注入空串
     */
    @Bean
    public CredentialStore credentialStore(
            @Value("${" + STORE_PROPERTY + ":}") String override) {
        try {
            CredentialStore chosen = choose(override);
            log.info("凭据存储实现：{}（{}=「{}」）",
                    chosen.getClass().getSimpleName(), STORE_PROPERTY, override);
            return chosen;
        } catch (Throwable t) {
            // 兜底必须覆盖到 Throwable：构造 MacCredentialStore 时若碰到 JNA
            // 加载失败，抛的是 Error（UnsatisfiedLinkError）而不是 Exception。
            // 凭据存不了只是少个功能，绝不能让整个应用起不来
            log.warn("凭据存储初始化失败，将禁用「保存密码」", t);
            return new NoOpCredentialStore(
                    "凭据存储初始化失败：" + t.getClass().getSimpleName());
        }
    }

    private CredentialStore choose(String override) {
        switch (normalize(override)) {
            case "windows":
                return new WindowsCredentialStore();
            case "mac":
                return new MacCredentialStore();
            case "linux":
                return new LinuxCredentialStore();
            case "none":
                return new NoOpCredentialStore(
                        "已通过配置禁用保存密码（" + STORE_PROPERTY + "=none）");
            case "":
                break;      // 没指定 → 按平台判断
            default:
                // 不因为一个拼错的属性值就悄悄把功能关掉，退回按平台判断
                log.warn("无法识别的 {} 取值「{}」，改为按操作系统自动判断",
                        STORE_PROPERTY, override);
                break;
        }

        String os = System.getProperty("os.name", "");
        String lower = os.toLowerCase(Locale.ROOT);
        // 注意顺序无所谓，但两者都用 contains 而不是 equals：
        // Windows 上是 "Windows 11"/"Windows 10"，macOS 上是 "Mac OS X"
        if (lower.contains("windows")) {
            return new WindowsCredentialStore();
        }
        if (lower.contains("mac") || lower.contains("darwin")) {
            return new MacCredentialStore();
        }
        // Linux 单独判，判据只用 "linux"。
        // 刻意不写成 contains("nix") 之类的宽判据 —— 那会把别的 Unix
        // 也当成「能存密码」，而那里根本没有 libsecret。
        // 判错的代价是用户看到「保存密码」可勾却永远存不上，比判否更糟
        if (lower.contains("linux")) {
            return new LinuxCredentialStore();
        }
        return new NoOpCredentialStore("当前系统（" + os + "）暂不支持保存密码");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
