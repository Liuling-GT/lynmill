package com.tinyquery.config;

import java.security.CodeSource;
import java.util.Locale;

/**
 * 判断当前是「开发运行」还是「打包后的应用」。
 *
 * <p>两者的正确行为不同：开发时前端跑在 Vite 的开发服务器上（默认 5173），
 * 后端 8080 只有 API，自动打开浏览器只会让人看到一个 404；打包后前端的 dist
 * 被打进 jar，8080 同时提供页面和 API，这时自动打开浏览器才是用户要的。</p>
 *
 * <p>判断依据是类的加载来源：开发时（{@code mvn spring-boot:run}、IDE 运行）
 * classpath 是 {@code target/classes} 目录；打包后是 jar 文件 ——
 * Spring Boot 可执行 jar 里的类位于 {@code BOOT-INF/classes}，
 * 但资源定位串中仍带着 {@code .jar}。</p>
 */
public final class RuntimeMode {

    private static final String JAR_MARKER = ".jar";

    private RuntimeMode() {
        // 工具类，不实例化
    }

    /**
     * 当前是否以打包产物（jar）运行。
     *
     * @return true 表示打包后的应用；false 表示开发运行或无法判断
     */
    public static boolean isPackaged() {
        try {
            CodeSource source = RuntimeMode.class.getProtectionDomain().getCodeSource();
            return source != null && looksPackaged(source.getLocation().toString());
        } catch (RuntimeException e) {
            // 拿不到加载来源时按开发处理：宁可少开一次浏览器，
            // 也不要让开发的人每次启动都被弹一个 404
            return false;
        }
    }

    /**
     * 纯函数：由类加载来源判断是否以 jar 形式运行。
     *
     * <p>包可见，仅用于测试 —— 这样两种真实运行形态（{@code target/classes} 目录
     * 与 Spring Boot 可执行 jar）都能被断言，不必真的打一次包。</p>
     *
     * @param codeSourceLocation {@code getProtectionDomain().getCodeSource().getLocation()}
     */
    static boolean looksPackaged(String codeSourceLocation) {
        return codeSourceLocation != null
                && codeSourceLocation.toLowerCase(Locale.ROOT).contains(JAR_MARKER);
    }
}
