package com.tinyquery.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存活探测。前端靠它判断「后台程序是不是已经退出了」。
 *
 * <h2>为什么需要这个接口</h2>
 *
 * <p>桌面外壳退出时（点控制窗口的「退出」、托盘菜单的「退出」、窗口右上角 ✕），
 * 后台的 java 进程会结束，但<b>浏览器里那个页面不会自己关掉</b> ——
 * 页面停在原处，之后任何操作都会失败，看起来像是程序坏了。</p>
 *
 * <p>网页要察觉「服务没了」，只能主动去问。这个接口就是那个最轻的问题：
 * 返回一个固定的小 JSON，不做任何数据库或业务动作，前端每 2 秒问一次，
 * 连着两次问不到就判定已经退出。</p>
 *
 * <p>刻意<b>不</b>复用已有的接口（比如 {@code /api/datasource}）当心跳：
 * 那些接口会真的去查数据源，拿它们做心跳既浪费又会在数据源出问题时误判。</p>
 *
 * <p>注意本接口同样受 {@code TokenFilter} 保护，需要令牌 —— 页面已经通过
 * {@code index.html} 注入拿到了令牌，所以这是透明的。</p>
 */
@Tag(name = "Lifecycle", description = "Liveness probe used by the page to notice the app has exited")
@RestController
@RequestMapping("/api/ping")
public class PingController {

    @Operation(summary = "Ping",
            description = "Cheap liveness probe. The page polls this to detect that the app has exited.")
    @GetMapping
    public DataSourceResponse<String> ping() {
        return DataSourceResponse.success("ok");
    }
}
