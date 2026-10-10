package com.tinyquery.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 保存 Web 服务器实际监听的端口。
 *
 * <p>端口由启动类在启动前选定（首选 8080，被占用则递增回退，全部占用时退回 0 交系统分配），
 * 因此「配置里写的值」与「真正监听的值」可能不同。凡是需要拼访问地址的地方
 * （如自动打开浏览器）都必须读这里，不能读配置。</p>
 *
 * <p>时序保证：本监听器在 {@code WebServerInitializedEvent} 时写入，该事件早于
 * {@code ApplicationReadyEvent} —— 后者正是打开浏览器的时机，因此到那时端口必定已就绪。</p>
 */
@Component
public class ServerPortHolder implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ServerPortHolder.class);

    // 本机访问地址。
    //
    // 用 127.0.0.1 而不是 localhost：启动类现在把服务绑在 127.0.0.1 上
    // （见 TinyQueryApplication.LOOPBACK_ADDRESS，只监听 IPv4 回环，不暴露局域网），
    // 而 localhost 在不少系统上会先解析到 IPv6 的 ::1 —— 那里没有人监听。
    // 主流浏览器会自动回退到 IPv4，但那是浏览器行为，本地客户端（curl、HttpClient）
    // 未必照做。对外公布的地址就应该是实际监听的地址
    private static final String HOST = "127.0.0.1";

    // 由事件线程写入、可能被后续请求线程读取，故用 volatile
    private volatile int port;

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int actualPort = event.getWebServer().getPort();
        this.port = actualPort;

        // 显式打印完整访问地址：端口可能因回退而非 8080，
        // 只打端口号的话用户仍不知道该怎么拼 URL
        log.info("TinyQuery 已就绪，访问地址：http://{}:{}", HOST, actualPort);

        // 顺带打一行构建标识。「我看到的到底是哪一版」是排查问题时问得最多的一句，
        // 日志里能直接看到就不用再去翻 jar 的时间（那个也不准，见 BuildInfo 的说明）
        String build = BuildInfo.display();
        if (!build.isEmpty()) {
            log.info("构建版本：{}", build);
        }
    }

    /**
     * 实际监听端口。
     *
     * @throws IllegalStateException Web 服务器尚未初始化
     */
    public int getPort() {
        int current = port;
        if (current <= 0) {
            throw new IllegalStateException(
                    "Web 服务器尚未初始化，端口不可用。"
                            + "请确认本方法在 WebServerInitializedEvent 之后调用");
        }
        return current;
    }

    /**
     * 由端口拼出本机访问地址，形如 {@code http://localhost:8081}。
     *
     * <p>公开是必要的：启动类注册 {@code WebServerInitializedEvent} 监听器时，
     * 要把「服务器实际监听的端口」写成同样的地址供单实例的第二个进程读取，
     * 而那个时刻 Spring 容器尚未就绪，拿不到本类的实例。</p>
     */
    public static String baseUrlFor(int port) {
        return "http://" + HOST + ":" + port;
    }

    /**
     * 本机访问地址，形如 {@code http://localhost:8081}。
     *
     * @throws IllegalStateException Web 服务器尚未初始化
     */
    public String getBaseUrl() {
        return baseUrlFor(getPort());
    }
}
