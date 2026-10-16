package com.tinyquery.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域访问策略：只放行本机来源。
 *
 * <p>这不是「为了开发方便」的配置，而是安全边界的一部分。桌面版的页面由后端自己发出，
 * 属于同源请求、根本不走 CORS；开发模式下前端虽然跑在 5173，
 * 但它通过 Vite 的代理访问 {@code /api}，浏览器眼里同样是同源。</p>
 *
 * <p>也就是说：收紧之后正常使用完全不受影响，受影响的只有
 * 「别的网站跨域访问本机 8080」—— 那正是要挡掉的东西。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许前端 JS 读取的响应头。
     *
     * <p>跨域响应里，浏览器默认只把少数几个「安全」响应头交给 JS，
     * {@code Content-Disposition} 不在其中 —— 它在其他响应头被隐藏时会变成
     * {@code null}。导出功能要靠这个头拿文件名，拿不到就只能退化成
     * {@code export.csv} 这种兜底名，用户看到的是一次「文件名不对」的导出。</p>
     *
     * <p>这个头不是安全敏感信息（就是服务端自己生成的文件名），
     * 暴露它不影响上面那道「只放行本机来源」的边界。</p>
     */
    static final String[] EXPOSED_HEADERS = {"Content-Disposition"};

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")          // 对所有 /api 开头的接口生效
                // 用 allowedOriginPatterns 而不是 allowedOrigins，是因为端口写不死：
                // 8080 被占时程序会自动往后找，而本方法跑在端口绑定之前 ——
                // 那一刻 ServerPortHolder 还没有值。
                // [*] 是 Spring 专门为端口位置提供的通配写法
                .allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(EXPOSED_HEADERS)  // 见上方说明：导出要靠它读文件名
                .maxAge(3600);                  // 预检请求缓存 1 小时
    }
}
