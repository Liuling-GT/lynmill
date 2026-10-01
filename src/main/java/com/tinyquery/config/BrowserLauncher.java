package com.tinyquery.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 启动完成后自动用系统默认浏览器打开 TinyQuery 界面。
 *
 * <p>时机选在 {@code ApplicationReadyEvent}：此时 Web 服务器已在监听、
 * {@link ServerPortHolder} 也已写入实际端口，打开浏览器不会落到还没起来的端口上。</p>
 *
 * <p>本类只负责「在正确的时机发起这件事」；具体怎么打开（系统命令 / AWT Desktop）
 * 由 {@link BrowserOpener} 承担。这样单实例的退出路径 —— 那条路径上 Spring 容器
 * 还不存在 —— 也能复用同一套打开逻辑，不必把平台命令表抄第二遍。</p>
 *
 * <p>打不开浏览器不是致命错误：服务已经就绪，用户手动访问日志里打印的地址即可。</p>
 *
 * <p><b>只在打包产物中生效。</b>开发时前端跑在 Vite 的开发服务器上（默认 5173），
 * 后端 8080 只有 API 没有页面，自动打开浏览器只会让人看到一个 404。
 * 判定依据见 {@link RuntimeMode}。开发时想开浏览器，点控制窗口里的「打开界面」即可。</p>
 */
@Component
@ConditionalOnProperty(prefix = "tinyquery.browser", name = "auto-open",
        havingValue = "true", matchIfMissing = true)
public class BrowserLauncher implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private final ServerPortHolder portHolder;

    public BrowserLauncher(ServerPortHolder portHolder) {
        this.portHolder = portHolder;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!RuntimeMode.isPackaged()) {
            log.info("开发运行，跳过自动打开浏览器（打包后才自动打开）。后端 API：{}",
                    portHolder.getBaseUrl());
            return;
        }

        String url = portHolder.getBaseUrl();

        if (BrowserOpener.open(url)) {
            log.info("已打开浏览器：{}", url);
        } else {
            log.warn("未能自动打开浏览器，请手动访问：{}", url);
        }
    }
}
