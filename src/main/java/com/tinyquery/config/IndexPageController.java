package com.tinyquery.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 首页：把访问令牌注入到前端页面里再发出去。
 *
 * <p>这是「令牌怎么送到前端」的答案。前端是从同一个服务加载的，
 * 所以后端有机会在自己发出的 HTML 里把令牌塞进去 —— 前端只要能读到
 * {@code window.__TINYQUERY_TOKEN__} 就行，而<b>跨域的恶意网页读不到这个页面</b>，
 * 也就拿不到令牌。</p>
 *
 * <p>放在 {@code config} 包而不是 {@code api}：它不属于业务接口，
 * 只服务于安全加固，和 {@link SecurityToken}、{@link TokenFilter} 是一组。</p>
 *
 * <p><b>开发模式下这里拿不到页面</b>：前端由 Vite 提供，
 * {@code static/index.html} 要等前端构建产物被复制进来才存在（打包阶段的事）。
 * 那种情况下本方法返回 404，页面照旧由 Vite 提供，令牌改走
 * {@link DevTokenController}。</p>
 */
@Controller
public class IndexPageController {

    /**
     * 构建产物里首页的位置。
     * 由 pom.xml 的 copy-resources 从 {@code tinyquery-frontend/dist} 复制过来
     */
    private static final String INDEX_LOCATION = "static/index.html";

    private static final String HEAD_END = "</head>";

    /** 前端读取令牌用的全局变量名。与 {@code tinyquery-frontend/src/api/token.js} 必须一致 */
    static final String TOKEN_VARIABLE = "window.__TINYQUERY_TOKEN__";

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<String> index() {
        String html = readIndex();
        if (html == null) {
            // 前端还没构建（开发模式的常态）。返回 404 而不是抛异常 ——
            // 没有首页不影响后端 API 可用
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                // 显式带上 UTF-8：页面里全是中文，靠默认字符集容易出乱码
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(injectToken(html));
    }

    /**
     * 在 {@code </head>} 之前插入一行赋值脚本。
     *
     * <p><b>纯函数</b>：不读文件、不碰运行时，便于直接喂字符串测试。</p>
     *
     * @param html 原始页面，允许为 null
     * @return 注入后的页面；没有 {@code </head>} 时原样返回
     */
    static String injectToken(String html) {
        if (html == null || !html.contains(HEAD_END)) {
            return html;
        }
        // 令牌是 URL-safe Base64（只有 A-Za-z0-9-_），不含引号或反斜杠，
        // 因此不需要做 JS 字符串转义
        String script = "<script>" + TOKEN_VARIABLE + "='" + SecurityToken.value() + "';</script>";
        // 用 String.replace（字面量替换）而不是 replaceFirst：
        // 后者会把 </head> 和令牌里的字符当成正则语法
        return html.replace(HEAD_END, script + HEAD_END);
    }

    private static String readIndex() {
        ClassPathResource resource = new ClassPathResource(INDEX_LOCATION);
        if (!resource.exists()) {
            return null;
        }
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
