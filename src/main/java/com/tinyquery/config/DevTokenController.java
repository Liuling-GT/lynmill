package com.tinyquery.config;

import com.tinyquery.api.DataSourceResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 取令牌的接口，<b>只在开发模式可用</b>。
 *
 * <p>打包运行时首页由后端自己发出，令牌已经注入在 {@code index.html} 里
 * （见 {@link IndexPageController}），前端根本不需要这个接口 ——
 * 而且 {@link TokenFilter} 在打包模式会把它一并拦掉（回 401），
 * 所以它不会跟着程序发出去。</p>
 *
 * <p><b>开发模式为什么需要它：</b>那时页面由 Vite（5173）提供，
 * 后端注入不到那个页面，令牌只能另找一条路送过去。</p>
 *
 * <p><b>它为什么不是漏洞：</b>能读到它的只有本机进程，而本机进程本来就能直接读日志、
 * 读运行地址文件，甚至直接读内存 —— 令牌要防的是「浏览器里的恶意网页」，
 * 不是「本机上已经能跑代码的程序」。网页跨域读它会先被 CORS 拦下。</p>
 */
@RestController
public class DevTokenController {

    @GetMapping(SecurityToken.DEV_TOKEN_PATH)
    public DataSourceResponse<Map<String, String>> token() {
        return DataSourceResponse.success(Map.of("token", SecurityToken.value()));
    }
}
