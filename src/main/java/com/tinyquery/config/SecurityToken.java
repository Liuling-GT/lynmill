package com.tinyquery.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 本进程的访问令牌。
 *
 * <p>要防的是 CSRF：LynMill 是个监听本地端口的 Web 服务，于是任何网页都能向
 * {@code http://localhost:8080} 发请求，其中「简单请求」（比如不带自定义头的 POST）
 * 连预检都不会触发，浏览器会老老实实把请求发出去。也就是说：在浏览器里打开一个
 * 恶意网站，那个网站的脚本就能指挥本机的 LynMill 干活。</p>
 *
 * <p>光靠 CORS 挡不住这件事：CORS 限制的是「读响应」，不限制「发请求」。所以还需要
 * 一个对方猜不到的东西 —— 就是这个令牌：请求必须带上它，而恶意网页拿不到
 * （令牌只出现在后端自己发出的首页里，跨域读不到）。</p>
 *
 * <p>实现要点：</p>
 *
 * <ul>
 *   <li>进程内只生成一次：每次启动换一个，重启即失效；不落盘、不写进任何文件，
 *       因此也没有「令牌文件忘了删」这类问题。</li>
 *   <li>恒定时间比较：用 {@link MessageDigest#isEqual}，而不是
 *       {@code String.equals}。后者在第一个不同的字符处就会返回，
 *       攻击者能靠响应时间一个字符一个字符地把令牌试出来。</li>
 * </ul>
 */
public final class SecurityToken {

    /** 携带令牌的请求头名。前后端必须一致 */
    public static final String HEADER_NAME = "X-LynMill-Token";

    /**
     * 取令牌的接口路径。
     *
     * <p>只在开发模式可用，见 {@link TokenFilter}。</p>
     */
    public static final String DEV_TOKEN_PATH = "/api/dev/token";

    // 32 字节 = 256 位随机数，Base64 之后是 43 个字符，暴力猜解不现实
    private static final int TOKEN_BYTES = 32;

    // 类加载时生成一次：同一进程内所有请求共用一个令牌，重启就换
    private static final String VALUE = generate();

    private SecurityToken() {
        // 工具类，不实例化
    }

    /** 本进程的令牌 */
    public static String value() {
        return VALUE;
    }

    /**
     * 校验请求带来的令牌。
     *
     * @param candidate 请求头里的值，允许为 null
     * @return 是否匹配
     */
    public static boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                VALUE.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        // URL-safe 且去掉尾部填充：令牌要放进 HTML 与请求头，
        // 不能出现 + / = 这些在 URL 或 HTML 里需要转义的字符
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
