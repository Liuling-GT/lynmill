package com.tinyquery.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存活探测。前端靠它判断后台程序是不是已经退出了。
 *
 * <p>桌面外壳退出时（控制窗口的「退出」、托盘菜单的「退出」、窗口右上角 ✕），
 * 后台 java 进程会结束，但浏览器里那个页面不会自己关掉，之后任何操作都会失败。
 * 网页要察觉服务没了只能主动去问：本接口返回一个固定的小 JSON，不做任何数据库
 * 或业务动作，前端每 2 秒问一次，连着两次问不到就判定已退出。
 *
 * <p>不复用已有接口（如 {@code /api/datasource}）当心跳：那些接口会真的去查数据源，
 * 既浪费又会在数据源出问题时误判。
 *
 * <p>本接口同样受 {@code TokenFilter} 保护，需要令牌 —— 页面已通过
 * {@code index.html} 注入拿到令牌。
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
