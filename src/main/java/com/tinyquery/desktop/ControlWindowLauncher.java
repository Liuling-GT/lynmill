package com.tinyquery.desktop;

import com.tinyquery.config.BrowserOpener;
import com.tinyquery.config.ServerPortHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;

/**
 * 启动完成后拉起桌面外壳：控制窗口 +（若系统支持）托盘图标。
 *
 * <p>时机选在 {@code ApplicationReadyEvent}：此时 Web 服务器已在监听、
 * {@link ServerPortHolder} 也已写入实际端口，窗口里显示的地址才是真的。</p>
 *
 * <p>整个界面创建过程都在事件分发线程（EDT）上执行 —— Swing 不是线程安全的，
 * 在别的线程上建组件会得到难以复现的诡异问题。</p>
 */
@Component
public class ControlWindowLauncher implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ControlWindowLauncher.class);

    private final ServerPortHolder portHolder;
    private final ApplicationShutdown shutdown;

    public ControlWindowLauncher(ServerPortHolder portHolder, ApplicationShutdown shutdown) {
        this.portHolder = portHolder;
        this.shutdown = shutdown;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("当前环境没有图形界面，不显示控制窗口；请用日志中的地址访问");
            return;
        }
        SwingUtilities.invokeLater(this::createWindow);
    }

    private void createWindow() {
        try {
            applySystemLookAndFeel();

            String url = portHolder.getBaseUrl();
            ControlWindow window = new ControlWindow(
                    url,
                    () -> BrowserOpener.open(url),
                    shutdown::shutdown);

            // 先把窗口建出来，再决定它以什么形态出现。
            //
            // 顺序不能反 —— 必须先尝试装托盘，因为托盘装不上时，
            // 这个窗口是用户唯一能点到「退出」的地方（见下）。
            boolean trayReady = TrayIconInstaller.install(window, url, shutdown::shutdown);

            if (trayReady) {
                // 托盘可用：窗口最小化到任务栏。
                //
                // 刻意不是 setVisible(false) —— 那样窗口在任务栏里也不留痕迹，
                // 用户想找它的时候会以为程序根本没起来（这是实测踩过的坑）。
                // 最小化则两头兼顾：屏幕上不占地方，任务栏里点一下就能调出来。
                window.minimizeToTaskbar();
            } else {
                // 托盘不可用（Linux 的 GNOME 3 默认移除了托盘区，需要额外装扩展）时，
                // 窗口必须完整显示出来，否则用户没有任何退出入口，
                // 只能去任务管理器杀进程 —— 那正是这个窗口当初存在要解决的问题
                log.info("托盘不可用，改为显示控制窗口作为退出入口");
                window.setVisible(true);
            }
        } catch (Throwable t) {
            // 这里刻意捕获 Throwable 而不是 Exception：
            // 没有图形界面（如无 DISPLAY 的 Linux）时，AWT 抛的是 AWTError，
            // 它继承自 Error 而非 Exception。界面起不来不该让服务停摆 ——
            // Web 部分已经就绪，用户照样能用，只是没有窗口可关
            log.warn("无法创建控制窗口，本实例将无界面运行", t);
        }
    }

    private void applySystemLookAndFeel() {
        try {
            // 不设的话 Swing 会用跨平台的 Metal 外观，看起来像上世纪的东西
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.debug("无法启用系统外观，沿用默认外观", e);
        }
    }
}
