package com.tinyquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenFilter 令牌校验")
class TokenFilterTest {

    // ==================== 哪些请求需要令牌（纯函数） ====================

    @Test
    @DisplayName("/api 下的接口一律需要令牌，两种运行模式都一样")
    void apiPathsAlwaysRequireToken() {
        assertTrue(TokenFilter.requiresToken("GET", "/api/datasource", false));
        assertTrue(TokenFilter.requiresToken("GET", "/api/datasource", true));
        assertTrue(TokenFilter.requiresToken("POST", "/api/query", false));
        assertTrue(TokenFilter.requiresToken("POST", "/api/query", true));
        assertTrue(TokenFilter.requiresToken("GET", "/api", true), "/api 本身也算");
    }

    @Test
    @DisplayName("OPTIONS 预检一律放行 —— 浏览器发预检时不带任何自定义头")
    void optionsIsAlwaysAllowed() {
        assertFalse(TokenFilter.requiresToken("OPTIONS", "/api/query", true));
        assertFalse(TokenFilter.requiresToken("OPTIONS", "/api/query", false));
        assertFalse(TokenFilter.requiresToken("options", "/api/datasource", true), "方法名大小写不敏感");
    }

    @Test
    @DisplayName("取令牌的接口：开发模式放行，打包后拦掉")
    void devTokenPathOnlyOpenInDevelopment() {
        assertFalse(TokenFilter.requiresToken("GET", SecurityToken.DEV_TOKEN_PATH, false));
        assertTrue(TokenFilter.requiresToken("GET", SecurityToken.DEV_TOKEN_PATH, true));
    }

    @Test
    @DisplayName("接口文档：开发模式放行，打包后拦掉（不该跟着程序发出去）")
    void apiDocsOnlyOpenInDevelopment() {
        assertFalse(TokenFilter.requiresToken("GET", "/swagger-ui/index.html", false));
        assertFalse(TokenFilter.requiresToken("GET", "/v3/api-docs", false));

        assertTrue(TokenFilter.requiresToken("GET", "/swagger-ui/index.html", true));
        assertTrue(TokenFilter.requiresToken("GET", "/v3/api-docs", true));
        assertTrue(TokenFilter.requiresToken("GET", "/v3/api-docs/swagger-config", true));
    }

    @Test
    @DisplayName("首页与静态资源不受影响 —— 拦了它们用户就打不开界面了")
    void pageAndStaticResourcesAreUntouched() {
        assertFalse(TokenFilter.requiresToken("GET", "/", true));
        assertFalse(TokenFilter.requiresToken("GET", "/index.html", true));
        assertFalse(TokenFilter.requiresToken("GET", "/favicon.ico", true));
        assertFalse(TokenFilter.requiresToken("GET", "/assets/index-abc123.js", true));
    }

    @Test
    @DisplayName("只认 /api 这个完整前缀，不能误伤 /apixyz 之类的路径")
    void apiPrefixMustBeExact() {
        assertFalse(TokenFilter.requiresToken("GET", "/apixyz", true));
        assertFalse(TokenFilter.requiresToken("GET", "/apiary/report", true));
    }

    @Test
    @DisplayName("路径为 null / 空 / 空白时不拦，交给后面的处理器去 404")
    void blankPathIsNotBlocked() {
        assertFalse(TokenFilter.requiresToken("GET", null, true));
        assertFalse(TokenFilter.requiresToken("GET", "", true));
        assertFalse(TokenFilter.requiresToken("GET", "   ", true));
    }

    // ==================== Host 是否为本机（纯函数） ====================

    @Test
    @DisplayName("本机地址的各种写法都放行")
    void localHostsAreAccepted() {
        assertTrue(TokenFilter.isLocalHost("127.0.0.1:8080"));
        assertTrue(TokenFilter.isLocalHost("localhost:8080"));
        assertTrue(TokenFilter.isLocalHost("localhost"), "没有端口也算");
        assertTrue(TokenFilter.isLocalHost("127.0.0.1"), "没有端口也算");
        assertTrue(TokenFilter.isLocalHost("LocalHost:8080"), "大小写不敏感");
        assertTrue(TokenFilter.isLocalHost("  localhost:8080  "), "首尾空白要容忍");
        assertTrue(TokenFilter.isLocalHost("[::1]:8080"), "IPv6 回环写法");
        assertTrue(TokenFilter.isLocalHost("[::1]"));
    }

    @Test
    @DisplayName("外部域名一律拒绝 —— 这正是 DNS rebinding 的判据")
    void foreignHostsAreRejected() {
        assertFalse(TokenFilter.isLocalHost("evil.com:8080"));
        assertFalse(TokenFilter.isLocalHost("evil.com"));
        assertFalse(TokenFilter.isLocalHost("192.168.1.5:8080"), "局域网 IP 也不行");
        assertFalse(TokenFilter.isLocalHost("10.0.0.7:8080"));
        assertFalse(TokenFilter.isLocalHost("[2001:db8::1]:8080"));
    }

    @Test
    @DisplayName("后缀欺骗必须挡住 —— 所以只能用 equals，不能用 contains")
    void suffixSpoofingIsRejected() {
        assertFalse(TokenFilter.isLocalHost("localhost.evil.com"),
                "contains(\"localhost\") 会放行它，改用 equals 才挡得住");
        assertFalse(TokenFilter.isLocalHost("localhost.evil.com:8080"));
        assertFalse(TokenFilter.isLocalHost("evil.com/localhost"));
        assertFalse(TokenFilter.isLocalHost("127.0.0.1.evil.com"));
        assertFalse(TokenFilter.isLocalHost("notlocalhost"));
    }

    @Test
    @DisplayName("Host 头缺失或空白一律拒绝 —— HTTP/1.1 请求必带它")
    void missingHostIsRejected() {
        assertFalse(TokenFilter.isLocalHost(null));
        assertFalse(TokenFilter.isLocalHost(""));
        assertFalse(TokenFilter.isLocalHost("   "));
    }

    // ==================== 过滤器行为 ====================

    @Test
    @DisplayName("Host 非本机 → 403，且请求不再往下走（首页也一样拦）")
    void foreignHostReturnsForbidden() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 关键：连首页 / 都要拦。它就是令牌被读到的地方，
        // 只守 /api 的话攻击者拿到令牌后照样能查数据
        MockFilterChain chain = run("GET", "/", null, "evil.com:8080", response);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest(), "被拒绝的请求不应继续往下走");
        String body = response.getContentAsString();
        assertTrue(body.contains("403"), body);
    }

    @Test
    @DisplayName("Host 非本机时，即使令牌正确也拒绝 —— DNS rebinding 是同源攻击，令牌拦不住")
    void foreignHostIsRejectedEvenWithValidToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run("GET", "/api/datasource", SecurityToken.value(),
                "evil.com:8080", response);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("Host 非本机时连 OPTIONS 预检也拒绝")
    void foreignHostRejectsEvenOptions() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run("OPTIONS", "/api/query", null, "evil.com:8080", response);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("Host 是本机时一切照旧")
    void localHostBehavesNormally() throws Exception {
        MockHttpServletResponse ok = new MockHttpServletResponse();
        MockFilterChain chain1 = run("GET", "/api/datasource", SecurityToken.value(),
                "127.0.0.1:8080", ok);
        assertEquals(200, ok.getStatus());
        assertNotNull(chain1.getRequest());

        MockHttpServletResponse noToken = new MockHttpServletResponse();
        run("GET", "/api/datasource", null, "localhost:8080", noToken);
        assertEquals(401, noToken.getStatus(), "Host 对了，令牌该拦还是要拦");
    }

    @Test
    @DisplayName("不带令牌访问 /api → 401，且请求不再往下走")
    void missingTokenReturnsUnauthorized() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run("GET", "/api/datasource", null, response);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest(), "被拒绝的请求不应继续往下走");
        assertTrue(response.getContentType().startsWith("application/json"), response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("401"), body);
        assertTrue(body.contains("令牌"), body);
    }

    @Test
    @DisplayName("令牌不对 → 401")
    void wrongTokenReturnsUnauthorized() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run("GET", "/api/datasource", "definitely-not-the-token", response);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("令牌正确 → 放行")
    void correctTokenPassesThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run("GET", "/api/datasource", SecurityToken.value(), response);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "带对令牌的请求应继续往下走");
    }

    @Test
    @DisplayName("OPTIONS 预检不带令牌也放行 —— 否则跨域请求会全线失败")
    void optionsPassesWithoutToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run("OPTIONS", "/api/query", null, response);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("非 /api 路径不带令牌也放行")
    void nonApiPathPassesWithoutToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run("GET", "/index.html", null, response);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    // ==================== 辅助方法 ====================

    private static final String LOCAL_HOST = "127.0.0.1:8080";

    // 默认用本机 Host：过滤器现在对每条请求都先验 Host，
    // 不设的话（MockHttpServletRequest 不会自动生成 Host 头）会被 403 拦掉，
    // 那些用例就测不到令牌逻辑了
    private static MockFilterChain run(String method, String uri, String token,
                                       MockHttpServletResponse response) throws Exception {
        return run(method, uri, token, LOCAL_HOST, response);
    }

    private static MockFilterChain run(String method, String uri, String token,
                                       String host, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (token != null) {
            request.addHeader(SecurityToken.HEADER_NAME, token);
        }
        if (host != null) {
            request.addHeader(TokenFilter.HOST_HEADER, host);
        }
        MockFilterChain chain = new MockFilterChain();
        // 本测试跑在 classpath 上，RuntimeMode.isPackaged() 为 false，
        // 也就是「开发模式」那一条分支。打包模式的分支由上面的纯函数用例覆盖
        new TokenFilter().doFilter(request, response, chain);
        return chain;
    }
}
