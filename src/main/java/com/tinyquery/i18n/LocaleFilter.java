package com.tinyquery.i18n;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把请求头里的语言写进 {@link Messages}，请求结束后清理。
 *
 * <p>{@code @Order} 排在 {@code TokenFilter} 之前：令牌被拒时过滤器会直接写一段
 * 中文 JSON 错误体，那段内容也想按语言走，所以语言得先就位。</p>
 *
 * <p>没有请求头时用程序设置的语言（{@link Messages#desktopLocale()}），
 * 而不是硬编码回中文。理由是「一个程序一套语言设置」：用户在网页设置里选了英文，
 * 那个选择会被同步到服务端并落盘。这时如果某个请求没带
 * {@code X-LynMill-Locale} 头（curl 调试、Swagger、第三方脚本），
 * 它拿到的应当同样是英文，否则同一个程序会同时存在两套语言，
 * 而「有没有带头」这种实现细节不该决定用户看到什么语言。带了头就仍然以头为准：
 * 那是调用方对这一次请求的明确要求，优先级高于程序设置。</p>
 *
 * <p>这里刻意用 {@link Messages#clear()} 而不是 {@code setCurrent(default)}：
 * 「没带头」不该被记成「请求表态要中文」，否则会把程序设置那份盖掉。
 * 详见 {@link Messages#current()} 的取值顺序说明。</p>
 *
 * <p>{@code finally} 里的清理不是可选项：Tomcat 复用线程，
 * 不清理会把语言泄漏给下一个请求。</p>
 */
@Component
@Order(0)
public class LocaleFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(Messages.LOCALE_HEADER);

        if (header == null || header.isBlank()) {
            // 没表态：清掉（而非设成中文），让 current() 回落到程序设置的语言。
            // 显式 clear 是为了不继承同一个线程上一个请求残留的值
            Messages.clear();
        } else {
            Messages.setCurrent(Messages.normalize(header));
        }

        try {
            chain.doFilter(request, response);
        } finally {
            Messages.clear();
        }
    }
}
