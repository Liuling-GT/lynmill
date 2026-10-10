package com.tinyquery.desktop;

import com.tinyquery.i18n.DesktopLocale;
import com.tinyquery.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把「语言变了」这件事通知到桌面外壳。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>用户在网页设置里切语言时，服务端会把语言记下来（{@code locale.txt}），
 * 但<b>已经显示出来的</b> Swing 控件不会自己更新 —— 它们上面的文字是创建时
 * {@code setText} 定死的。托盘菜单、控制窗口都得被告知一声。</p>
 *
 * <h2>为什么是「拉」而不是「推」</h2>
 *
 * <p>这里<b>不</b>在语言变更时主动去重建界面，只更新一个版本号；
 * 各界面自己在合适的时机（托盘菜单弹出前、控制窗口收到通知时）比对版本号决定要不要刷新。</p>
 *
 * <p>理由是线程：接口调用发生在 Tomcat 的工作线程上，而 Swing 只能在事件分发线程（EDT）
 * 上碰。主动推送意味着每个界面都要自己处理线程切换与「窗口还没创建出来」的时序，
 * 出错方式还都很隐蔽（偶发的界面错乱）。让界面在自己已知安全的时刻拉一下，
 * 时序问题就自然消失了。</p>
 *
 * <p>控制窗口那一份额外做了 EDT 切换 —— 它是「被通知」的一方，
 * 而通知来自 Tomcat 线程。</p>
 */
@Service
public class LocaleSyncService {

    private static final Logger log = LoggerFactory.getLogger(LocaleSyncService.class);

    /**
     * 当前控制窗口。
     *
     * <p>用静态字段：本服务由 Spring 管理，而控制窗口是在 {@code SpringApplication.run}
     * 返回<b>之后</b>才创建的（见 {@code ControlWindowLauncher}），
     * 两者生命周期对不上，注入不进来。本地单实例应用，一个进程一个窗口，静态够用。</p>
     *
     * <p>用 {@link AtomicReference} 而不是普通字段：写在启动线程、
     * 读在 Tomcat 线程，需要可见性保证。</p>
     */
    private static final AtomicReference<ControlWindow> WINDOW = new AtomicReference<>();

    /** 由 {@code ControlWindowLauncher} 在窗口创建后登记 */
    public static void registerWindow(ControlWindow window) {
        WINDOW.set(window);
    }

    /** 窗口销毁后清掉，避免向一个已释放的窗口发刷新 */
    public static void unregisterWindow() {
        WINDOW.set(null);
    }

    /**
     * 语言变更后调用。让已存在的桌面界面跟上新语言。
     *
     * <p>没有窗口（无头环境、窗口创建失败）时静默跳过 —— 那不是错误。</p>
     */
    public void onLocaleChanged() {
        ControlWindow window = WINDOW.get();
        if (window == null) {
            // 无头模式跑（服务器 / CI）或托盘与控制窗口都没起来。
            // 托盘菜单不需要在这里处理：它每次弹出前自己比对版本号
            log.debug("没有控制窗口可刷新，跳过；托盘菜单会在下次弹出时自行刷新");
            return;
        }

        if (SwingUtilities.isEventDispatchThread()) {
            window.refreshTextsIfLocaleChanged();
            return;
        }

        // 从 Tomcat 线程切到 EDT。用 invokeLater 而不是 invokeAndWait：
        // 接口调用方不该为一个界面刷新而阻塞，刷新失败也只影响观感
        SwingUtilities.invokeLater(() -> {
            try {
                window.refreshTextsIfLocaleChanged();
            } catch (RuntimeException e) {
                // 窗口可能正在销毁。刷新失败不该让接口报错
                log.debug("刷新控制窗口文字失败", e);
            }
        });
    }

    /** 当前登记的控制窗口，测试用 */
    static ControlWindow currentWindow() {
        return WINDOW.get();
    }

    /** 当前桌面端语言。接口读它时用，顺带把「为什么是拉」这件事集中在一处 */
    public String currentLocale() {
        return Messages.desktopLocale();
    }
}
