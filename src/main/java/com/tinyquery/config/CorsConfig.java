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
 * <p>也就是说：收紧之后<b>正常使用完全不受影响</b>，受影响的只有
 * 「别的网站跨域访问本机 8080」—— 那正是要挡掉的东西。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")          // 对所有 /api 开头的接口生效
                // 用 allowedOriginPatterns 而不是 allowedOrigins，是因为端口写不死：
                // 8080 被占时程序会自动往后找，而本方法跑在端口绑定<b>之前</b> ——
                // 那一刻 ServerPortHolder 还没有值。
                // [*] 是 Spring 专门为端口位置提供的通配写法
                .allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);                  // 预检请求缓存 1 小时
    }
}
