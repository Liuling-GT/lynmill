package com.tinyquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IndexPageController 首页令牌注入")
class IndexPageControllerTest {

    private static final String PAGE =
            "<!DOCTYPE html><html><head><title>LynMill</title></head><body><div id=\"app\"></div></body></html>";

    // ==================== 注入位置 ====================

    @Test
    @DisplayName("脚本插在 </head> 之前，且用的是本进程的真实令牌")
    void injectsTokenBeforeHeadEnd() {
        String injected = IndexPageController.injectToken(PAGE);

        int scriptAt = injected.indexOf(IndexPageController.TOKEN_VARIABLE);
        int headEndAt = injected.indexOf("</head>");

        assertTrue(scriptAt > 0, "应插入令牌变量：" + injected);
        assertTrue(headEndAt > 0, injected);
        assertTrue(scriptAt < headEndAt, "脚本必须在 </head> 之前：" + injected);
        assertTrue(injected.contains(SecurityToken.value()), "注入的应是真实令牌：" + injected);
    }

    @Test
    @DisplayName("脚本是完整的赋值语句，令牌外面带引号和分号")
    void injectedScriptIsWellFormed() {
        String injected = IndexPageController.injectToken("<head></head>");

        // 断言完整的脚本片段，而不是「结果以 <script> 开头」——
        // 脚本是插在 </head> 之前的，整段 HTML 并不会以它开头
        assertTrue(injected.contains("<script>" + IndexPageController.TOKEN_VARIABLE
                + "='" + SecurityToken.value() + "';</script>"), injected);
    }

    @Test
    @DisplayName("原页面其余内容一字不改")
    void keepsOriginalContent() {
        String injected = IndexPageController.injectToken(PAGE);

        assertTrue(injected.startsWith("<!DOCTYPE html><html><head>"), injected);
        assertTrue(injected.endsWith("<body><div id=\"app\"></div></body></html>"), injected);
        assertTrue(injected.contains("<title>LynMill</title>"), injected);
    }

    // ==================== 边界情况 ====================

    @Test
    @DisplayName("没有 </head> 时原样返回，不硬塞到错误的位置")
    void withoutHeadEndReturnsUnchanged() {
        String html = "<html><body>没有 head</body></html>";

        assertEquals(html, IndexPageController.injectToken(html));
    }

    @Test
    @DisplayName("html 为 null 时返回 null，不抛异常")
    void nullHtmlIsTolerated() {
        assertNull(IndexPageController.injectToken(null));
    }

    // ==================== 拿不到页面时 ====================

    @Test
    @DisplayName("拿不到首页也不能把程序搞挂")
    void indexNeverThrows() {
        // 首页来自前端构建产物（打包阶段才复制进 classpath），
        // 测试 classpath 上通常没有。这里只要求「不抛异常」——
        // 没有首页不该影响后端 API 可用
        assertDoesNotThrow(() -> new IndexPageController().index());
    }
}
