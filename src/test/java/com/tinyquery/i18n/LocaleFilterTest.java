package com.tinyquery.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LocaleFilter} 的语言解析。
 *
 * <h2>这里守的是什么</h2>
 *
 * <p>核心是一条容易写错、且错了很难发现的规则：<b>没带语言头时，
 * 应当用程序设置的语言，而不是硬编码回中文。</b></p>
 *
 * <p>写错的后果很隐蔽：用户在网页上把语言切成英文、托盘菜单也跟着变了，
 * 但任何不带 {@code X-TinyQuery-Locale} 的请求（curl 调试、Swagger、
 * 第三方脚本）仍然返回中文 —— 同一个程序两套语言。</p>
 */
@DisplayName("LocaleFilter 请求语言解析")
class LocaleFilterTest {

    private final LocaleFilter filter = new LocaleFilter();

    private String savedLocale;

    @BeforeEach
    void rememberLocale() {
        savedLocale = Messages.desktopLocale();
        Messages.clear();
    }

    @AfterEach
    void restoreLocale() {
        Messages.clear();
        Messages.setDesktopLocale(savedLocale);
    }

    /** 跑一次过滤器，返回链下游看到的语言 */
    private String localeSeenByChain(MockHttpServletRequest request) throws Exception {
        String[] seen = new String[1];
        filter.doFilter(request, new MockHttpServletResponse(),
                new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
                    @Override
                    public void service(jakarta.servlet.ServletRequest req,
                                        jakarta.servlet.ServletResponse res) {
                        seen[0] = Messages.current();
                    }
                }));
        return seen[0];
    }

    // ==================== 带头的请求 ====================

    @Test
    @DisplayName("带了语言头就以头为准")
    void headerWinsWhenPresent() throws Exception {
        Messages.setDesktopLocale(Messages.ZH);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(Messages.LOCALE_HEADER, "en");
        assertEquals(Messages.EN, localeSeenByChain(req));
    }

    @Test
    @DisplayName("请求头可以压过程序设置的语言")
    void headerOverridesProgramLocale() throws Exception {
        // 程序设了英文，某个请求偏偏要中文 —— 这是调用方的明确要求，应当满足
        Messages.setDesktopLocale(Messages.EN);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(Messages.LOCALE_HEADER, "zh");
        assertEquals(Messages.ZH, localeSeenByChain(req));
    }

    // ==================== 没带头的请求（本轮修的那条）====================

    @Test
    @DisplayName("没带语言头时跟随程序设置 —— 不是硬编码回中文")
    void headerlessRequestFollowsProgramLocale() throws Exception {
        Messages.setDesktopLocale(Messages.EN);

        // 这是修复的核心。原先无头请求会被强制成中文，
        // 于是「网页切了英文」在无头调用路径上完全不生效
        assertEquals(Messages.EN, localeSeenByChain(new MockHttpServletRequest()),
                "没带语言头时应当跟随程序设置的语言");
    }

    @Test
    @DisplayName("没带语言头 + 程序语言是中文 → 中文")
    void headerlessRequestWithChineseProgramLocale() throws Exception {
        Messages.setDesktopLocale(Messages.ZH);
        assertEquals(Messages.ZH, localeSeenByChain(new MockHttpServletRequest()));
    }

    @Test
    @DisplayName("空白语言头按没带处理")
    void blankHeaderTreatedAsAbsent() throws Exception {
        Messages.setDesktopLocale(Messages.EN);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(Messages.LOCALE_HEADER, "   ");
        assertEquals(Messages.EN, localeSeenByChain(req),
                "空白头不该覆盖程序设置");
    }

    // ==================== 线程复用 ====================

    @Test
    @DisplayName("请求结束后语言被清理，不泄漏给下一个请求")
    void localeDoesNotLeakAcrossRequests() throws Exception {
        // Tomcat 复用线程。不清理的表现是「偶尔弹一句英文」，极难复现
        Messages.setDesktopLocale(Messages.ZH);

        MockHttpServletRequest withEnglish = new MockHttpServletRequest();
        withEnglish.addHeader(Messages.LOCALE_HEADER, "en");
        filter.doFilter(withEnglish, new MockHttpServletResponse(), new MockFilterChain());

        // 下一个请求没带头，不该继承上一个的英文
        assertEquals(Messages.ZH, localeSeenByChain(new MockHttpServletRequest()),
                "上一个请求的英文泄漏到了下一个请求");
    }

    @Test
    @DisplayName("带头发英文后，下一个无头请求回到程序设置而不是留在英文")
    void requestLocaleDoesNotOverrideProgramLocalePersistently() throws Exception {
        Messages.setDesktopLocale(Messages.ZH);

        MockHttpServletRequest en = new MockHttpServletRequest();
        en.addHeader(Messages.LOCALE_HEADER, "en");
        filter.doFilter(en, new MockHttpServletResponse(), new MockFilterChain());

        // 关键：无头请求看到的是【程序设置】的中文，而不是上一个请求留下的英文。
        // 这说明「没带头」确实被当成了「没表态」，而不是「表态要中文」——
        // 两种实现都能让这条通过，但如果实现是 setCurrent(normalize(null))，
        // 就会把程序设置那份盖住，下面这条断言会失败
        assertEquals(Messages.ZH, localeSeenByChain(new MockHttpServletRequest()));
    }
}
