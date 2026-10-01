package com.tinyquery.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 优雅退出。
 *
 * <p>这是整个桌面外壳唯一的退出出口：控制窗口的「退出」按钮、窗口右上角的 ✕、
 * 托盘菜单的「退出」都调它。集中在一处才能保证只执行一次、且走完整的关闭流程。</p>
 *
 * <p>走 {@link SpringApplication#exit} 而不是直接 {@code System.exit}：
 * 前者会先关闭 Spring 上下文，让 Tomcat 释放端口、按顺序销毁各组件；
 * 后者会跳过所有清理动作。</p>
 */
@Component
public class ApplicationShutdown {

    private static final Logger log = LoggerFactory.getLogger(ApplicationShutdown.class);

    private final ApplicationContext context;

    // 退出请求可能同时来自多个入口（比如点了「退出」按钮，窗口随后又触发一次关闭事件），
    // 用 CAS 保证真正的退出流程只跑一次
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public ApplicationShutdown(ApplicationContext context) {
        this.context = context;
    }

    /**
     * 关闭应用。重复调用是安全的 —— 第二次及以后直接返回。
     *
     * <p>注意本方法<b>不会返回</b>：走到最后会调用 {@link System#exit} 结束进程。
     * 这是必要的 —— Swing 会留下非守护的 AWT 线程，不显式退出的话 JVM 不会自己结束。</p>
     */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            log.debug("已在退出过程中，忽略重复的退出请求");
            return;
        }

        log.info("正在退出 TinyQuery…");
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }
}
