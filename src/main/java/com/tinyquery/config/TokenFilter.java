package com.tinyquery.config;

import com.tinyquery.i18n.Messages;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * 令牌校验过滤器：拦下所有没带对令牌的 {@code /api/**} 请求，回 401；
 * 并拦下所有 Host 不指向本机的请求，回 403。
 *
 * <p>这是安全加固的核心。为什么光收窄 CORS 不够，
 * 见 {@link SecurityToken} 的类注释。</p>
 *
 * <h2>四个容易做错的地方</h2>
 *
 * <ol>
 *   <li><b>Host 校验必须在令牌校验之前。</b>它防的是 DNS rebinding：
 *       攻击者的域名先解析到自己服务器、页面加载后再改指向 127.0.0.1，
 *       此时浏览器认为请求同源，<b>CORS 根本不介入</b>，
 *       而页面又是「同源」的，令牌也可以照常取到 —— 令牌这一关拦不住它。
 *       详见 {@link #isLocalHost}。</li>
 *   <li><b>{@code OPTIONS} 预检必须放行。</b>浏览器发预检时<b>不带任何自定义头</b>
 *       （只发 {@code Origin} 和 {@code Access-Control-Request-*}），
 *       把它拦下来会让所有跨域请求在预检阶段就失败，而且报错信息非常难懂 ——
 *       界面上只会看到一句「网络错误」。</li>
 *   <li><b>令牌比对必须恒定时间。</b>见 {@link SecurityToken#matches}。</li>
 *   <li><b>开发模式要放行「取令牌的接口」和「接口文档」。</b>开发模式下前端由 Vite 提供，
 *       后端注入不到那个页面，令牌只能另想办法拿；打包后页面由后端自己发、令牌直接注入，
 *       那个接口就该关掉，接口文档也不该跟着程序发出去。</li>
 * </ol>
 *
 * <p>{@link #requiresToken} 与 {@link #isLocalHost} 刻意写成<b>纯函数</b>
 * （外部状态一律由参数传入），这样测试可以在同一台机器上穷举两种运行模式、
 * 各种 Host 写法的全部分支，而不是只能验证当前碰巧处于的那一种。</p>
 */
@Component
public class TokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenFilter.class);

    static final String HOST_HEADER = "Host";

    // 判定一次就够：程序运行期间不会从「打包」变成「没打包」
    private final boolean packaged = RuntimeMode.isPackaged();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Host 校验放在最前面，对所有路径生效 —— 首页 / 不带令牌也能访问，
        // 攻击者正是靠它读到 window.__TINYQUERY_TOKEN__ 的，只守 /api 不够
        String host = request.getHeader(HOST_HEADER);
        if (!isLocalHost(host)) {
            log.warn("拒绝 Host 非本机的请求：{} {} Host={}",
                    request.getMethod(), request.getRequestURI(), host);
            rejectHost(response);
            return;
        }

        if (!requiresToken(request.getMethod(), request.getRequestURI(), packaged)
                || SecurityToken.matches(request.getHeader(SecurityToken.HEADER_NAME))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("拒绝未携带有效令牌的请求：{} {}", request.getMethod(), request.getRequestURI());
        reject(response);
    }

    /**
     * Host 头是否指向本机。
     *
     * <p><b>为什么需要这一条。</b>服务绑在 127.0.0.1 上只能挡住「局域网直连」，
     * 挡不住 DNS rebinding：攻击者的域名先解析到自己的服务器、页面加载完成后再把
     * DNS 改指向 127.0.0.1。此时请求确实发到了本机，浏览器也认为它与页面同源，
     * 于是 CORS 不介入、首页照常返回、令牌照常读到。唯一能分辨真假的，是
     * <b>Host 头</b> —— 浏览器不允许页面用 JS 伪造它，它由目标 URL 决定。</p>
     *
     * <p><b>必须用 equals 精确比对，不能用 contains。</b>
     * {@code contains("localhost")} 会放行 {@code localhost.evil.com} 这种后缀欺骗。
     * 同理也不能只认「以 127. 开头」之外的任何放宽写法。</p>
     *
     * <p><b>纯函数</b>：只吃一个字符串，不读请求、不碰运行时。</p>
     *
     * @param hostHeader {@code Host} 请求头的原始值，形如 {@code localhost:8080}
     * @return true 表示是本机地址
     */
    static boolean isLocalHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            // HTTP/1.1 请求必带 Host。缺了就说明不是正常浏览器发来的，一律拒绝
            return false;
        }

        String host = hostHeader.trim().toLowerCase(Locale.ROOT);

        // 去掉端口。IPv6 写作 [::1]:8080，方括号内的冒号不是端口分隔符，
        // 不能简单按第一个 ':' 切
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close < 0 ? host : host.substring(0, close + 1);
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
        }

        // 服务只绑在 127.0.0.1 上，因此实际会出现的只有前两种；
        // 后两种是浏览器对 localhost / [::1] 的写法留的余量
        return "127.0.0.1".equals(host)
                || "localhost".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host);
    }

    /**
     * 这个请求是否需要令牌。
     *
     * <p><b>纯函数</b>：所有外部状态都由参数传入，不读系统属性、不碰运行时。</p>
     *
     * @param method    HTTP 方法
     * @param path      请求路径
     * @param packaged  是否打包运行
     * @return true 表示必须带对令牌才放行
     */
    static boolean requiresToken(String method, String path, boolean packaged) {
        if (path == null || path.isBlank()) {
            return false;
        }
        // 预检请求不带自定义头，拦下来会让跨域请求在预检阶段全线失败
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return false;
        }
        if (!isApiPath(path) && !isDocPath(path)) {
            return false;
        }
        if (!packaged && (SecurityToken.DEV_TOKEN_PATH.equals(path) || isDocPath(path))) {
            // 开发模式：取令牌的接口与接口文档都放行。
            // 它们对开发有用，而打包后都不该存在 —— 所以只在「非打包」时开这个口子
            return false;
        }
        return true;
    }

    // 不能简单用 startsWith("/api")：那会把 /apixyz 这类无关路径一并拦下来
    private static boolean isApiPath(String path) {
        return path.equals("/api") || path.startsWith("/api/");
    }

    // springdoc 的两个入口。打包后不该跟着程序发出去 —— 它会把全部接口结构暴露出来
    private static boolean isDocPath(String path) {
        return path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui");
    }

    // 用 403 而不是 401：语义不同 —— 「你缺令牌」和「这个地址我不服务」是两回事。
    // 状态码分清楚，排查时才能一眼看出是被哪一关拦的
    private static void rejectHost(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":403,\"message\":\"" + Messages.get("security.hostRejected") + "\"}");
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        // 手工拼 JSON：过滤器里拿不到 MVC 的消息转换器，
        // 而这段内容是固定的，不值得为它引一个序列化依赖进来。
        // 文案按语言取（LocaleFilter 排在本过滤器之前，此处语言已就位）
        response.getWriter().write(
                "{\"code\":401,\"message\":\"" + Messages.get("security.tokenRejected") + "\"}");
    }
}
