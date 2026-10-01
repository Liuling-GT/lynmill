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
            window.setVisible(true);

            TrayIconInstaller.install(window, url, shutdown::shutdown);
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
