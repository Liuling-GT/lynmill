package com.tinyquery.desktop;

import com.tinyquery.config.BrowserOpener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.LineBorder;
import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 安装系统托盘图标。
 *
 * <p>托盘是<b>增强而非依赖</b>：Linux 的 GNOME 3 默认移除了托盘区（需要额外装扩展），
 * {@link SystemTray#isSupported()} 会返回 false。那种情况下控制窗口仍然是完整的退出入口，
 * 这里只记一条日志然后返回 false，绝不抛异常。</p>
 *
 * <h2>为什么菜单用 Swing 的 {@link JPopupMenu} 而不是 AWT 的 {@code PopupMenu}</h2>
 *
 * <p>{@link TrayIcon} 的构造器只接受 AWT 的 {@code PopupMenu}，但<b>那条路走不通</b>：
 * Windows 上 AWT 菜单是<b>原生菜单</b>，{@code MenuItem.setFont()} 对原生菜单无效
 * （JDK 官方在 {@code MenuComponent.setFont} 的文档里就承认「部分平台不支持设置菜单字体」
 * ），菜单会用系统里的 Segoe UI 一类<b>不含中日韩字形</b>的物理字体渲染，
 * 结果是每个汉字显示成一个方块。</p>
 *
 * <p>因此这里不设置 tray icon 的 popup，改为自己监听鼠标事件，右键时弹出一个
 * Swing 的 {@link JPopupMenu} —— 它由 Swing 自己绘制，字体来自外观（中文 Windows 上是
 * Microsoft YaHei UI），中文正常显示。</p>
 *
 * <h2>外观：三处都必须自己接管，不能沿用外观默认值</h2>
 *
 * <ol>
 *   <li><b>菜单项 UI</b> —— 换成 {@link CenteredMenuItemUI}，否则文字贴着右边不居中</li>
 *   <li><b>底色字色</b> —— 黑底白字，写成普通 {@link Color} 避免被外观覆盖</li>
 *   <li><b>分隔线</b> —— 换成 {@link #menuSeparator()}，原因见该方法注释</li>
 * </ol>
 */
public final class TrayIconInstaller {

    private static final Logger log = LoggerFactory.getLogger(TrayIconInstaller.class);

    private static final int TRAY_ICON_SIZE = 16;
    private static final int MENU_FONT_SIZE = 12;

    /** 深色菜单在浅色桌面上需要一条边界，否则看起来是「浮」着的 */
    private static final Color MENU_BORDER = new Color(0x666666);

    /** 分隔线所占的高度（线只画中间一行，上下留白） */
    static final int SEPARATOR_HEIGHT = 7;

    /** 分隔线左右各留出的空隙，避免顶到菜单边框 */
    private static final int SEPARATOR_INSET = 8;

    private static final Color SEPARATOR_COLOR = new Color(0x555555);

    // 菜单文字定义成常量，测试才能校验「字体覆盖了菜单里真实出现的每一个字」，
    // 而不是校验一个和实际菜单无关的字符串
    static final String MENU_LABEL_OPEN = "打开界面";
    static final String MENU_LABEL_SHOW = "显示窗口";
    static final String MENU_LABEL_EXIT = "退出";

    /** 菜单里出现的全部文字，供测试校验字体覆盖 */
    static final String MENU_TEXT_SAMPLE = MENU_LABEL_OPEN + MENU_LABEL_SHOW + MENU_LABEL_EXIT;

    private TrayIconInstaller() {
        // 工具类，不实例化
    }

    /**
     * 托盘菜单使用的字体。
     *
     * <p>优先跟随当前外观（{@code PopupMenu.font}），观感最贴近系统；外观尚未设置、
     * 或该字体显示不了菜单里的汉字时，退回逻辑字体 {@link Font#SANS_SERIF} ——
     * 它是复合字体，带完整的中文回退链。</p>
     *
     * <p>这里做的是<b>运行时探测</b>而不是写死字体名：{@code TrayIconInstallerTest}
     * 会逐个汉字校验覆盖情况，选错字体测试即失败。</p>
     */
    static Font menuFont() {
        Font lookAndFeelFont = UIManager.getFont("PopupMenu.font");
        if (lookAndFeelFont != null && canDisplayAll(lookAndFeelFont)) {
            return lookAndFeelFont;
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, MENU_FONT_SIZE);
    }

    private static boolean canDisplayAll(Font font) {
        for (char c : MENU_TEXT_SAMPLE.toCharArray()) {
            if (!font.canDisplay(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 尝试安装托盘图标。
     *
     * @param window 托盘菜单「显示窗口」要唤起的控制窗口
     * @param url    本实例的访问地址，供托盘菜单「打开界面」使用
     * @param onExit 托盘菜单「退出」要执行的动作
     * @return 是否安装成功；false 表示当前系统不支持托盘，调用方无需处理
     */
    public static boolean install(Window window, String url, Runnable onExit) {
        if (!SystemTray.isSupported()) {
            log.info("当前系统不支持托盘图标（Linux 的 GNOME 3 常见），仅使用控制窗口");
            return false;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();

            JPopupMenu menu = buildMenu(url, () -> showWindow(window), onExit);
            installDismissGuard(menu);

            TrayIcon icon = new TrayIcon(AppIcons.create(TRAY_ICON_SIZE), "TinyQuery");
            // 让操作系统按托盘实际尺寸缩放，避免高 DPI 下图标糊掉
            icon.setImageAutoSize(true);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    maybeShowMenu(event, menu);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    maybeShowMenu(event, menu);
                }
            });
            // 双击（部分平台是单击）托盘图标即显示控制窗口
            icon.addActionListener(event -> showWindow(window));

            tray.add(icon);
            log.info("已添加系统托盘图标");
            return true;
        } catch (AWTException | RuntimeException e) {
            // 托盘装不上不影响任何核心功能，降级为「只有控制窗口」
            log.warn("添加托盘图标失败，仅使用控制窗口", e);
            return false;
        }
    }

    /**
     * 构建托盘菜单。
     *
     * <p>包可见且不依赖 {@link Window}，这样测试可以不创建真实窗口就断言
     * 菜单项文字、字体与配色。</p>
     */
    static JPopupMenu buildMenu(String url, Runnable onShowWindow, Runnable onExit) {
        Font font = menuFont();

        JPopupMenu menu = new JPopupMenu();
        // 声明为不透明。注意：本平台下这并不会让菜单真的填充背景
        // （JPopupMenu.paintComponent 是空的），未被菜单项盖住的区域仍要靠
        // menuSeparator() 自己填，否则会透出桌面
        menu.setOpaque(true);
        menu.setFont(font);
        menu.setBackground(CenteredMenuItemUI.BACKGROUND);
        menu.setForeground(CenteredMenuItemUI.FOREGROUND);
        menu.setBorder(new LineBorder(MENU_BORDER));

        menu.add(menuItem(MENU_LABEL_OPEN, font, () -> BrowserOpener.open(url)));
        menu.add(menuItem(MENU_LABEL_SHOW, font, onShowWindow));
        menu.add(menuSeparator());
        menu.add(menuItem(MENU_LABEL_EXIT, font, onExit));
        return menu;
    }

    private static JMenuItem menuItem(String label, Font font, Runnable action) {
        JMenuItem item = new JMenuItem(label);

        // 必须替换掉外观自带的菜单项 UI：Windows 的 WindowsMenuItemUI 无视居中属性，
        // 文字会贴着右边（实测数据见 CenteredMenuItemUI）
        item.setUI(new CenteredMenuItemUI());

        // 字体和配色逐个显式设置：菜单容器的字体不会自动传给子项；
        // 颜色写成普通 Color 而不是外观的 UIResource，外观就不会再把它覆盖回去
        item.setOpaque(true);
        item.setFont(font);
        item.setBackground(CenteredMenuItemUI.BACKGROUND);
        item.setForeground(CenteredMenuItemUI.FOREGROUND);

        item.addActionListener(event -> action.run());
        return item;
    }

    /**
     * 菜单分隔线。
     *
     * <p><b>刻意不用 {@code JPopupMenu.Separator}</b>。它由外观绘制，实测结果：
     * 首选尺寸是 10 像素高，并且会<b>用白色背景把整条铺满</b>（截图里连着 10 行、
     * 每行 99 个亮像素，横跨整个菜单宽度）—— 在纯黑菜单上就是一根刺眼的白条。</p>
     *
     * <p>这里改成自己画：只画中间一行细线，颜色 {@code #555555}，左右各留
     * {@link #SEPARATOR_INSET} 像素空隙。</p>
     */
    private static JComponent menuSeparator() {
        JComponent separator = new JComponent() {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(0, SEPARATOR_HEIGHT);
            }

            @Override
            public Dimension getMaximumSize() {
                // 宽度必须放开，否则 BoxLayout 只能给它 0 宽 —— 实测过，宽度会是 0，线根本画不出来；
                // 高度必须锁死，否则它会把菜单多余的空间吃掉、被撑高
                return new Dimension(Integer.MAX_VALUE, SEPARATOR_HEIGHT);
            }

            @Override
            protected void paintComponent(Graphics g) {
                // 必须自己把这块区域填成菜单底色：JPopupMenu 的 paintComponent 是空的，
                // 即使 setOpaque(true) 也不会填背景。弹出菜单窗口是半透明的，不填的话
                // 这条缝会直接透出后面的桌面（实测：同一行里不同列颜色还不一样）
                g.setColor(CenteredMenuItemUI.BACKGROUND);
                g.fillRect(0, 0, getWidth(), getHeight());

                g.setColor(SEPARATOR_COLOR);
                int y = getHeight() / 2;
                g.drawLine(SEPARATOR_INSET, y, getWidth() - SEPARATOR_INSET - 1, y);
            }
        };
        // 非透明：不画自己的背景，让菜单的黑底透上来
        separator.setOpaque(false);
        return separator;
    }

    // 右键的判定时机各平台不同，pressed 与 released 都要检查 isPopupTrigger()
    private static void maybeShowMenu(MouseEvent event, JPopupMenu menu) {
        if (event.isPopupTrigger()) {
            showMenu(menu);
        }
    }

    private static void showMenu(JPopupMenu menu) {
        // 托盘鼠标事件的坐标是相对任务栏的，不能用；必须取鼠标的屏幕绝对坐标
        Point location = pointerLocation();
        if (location == null) {
            return;
        }
        menu.setLocation(location.x, location.y);
        // JPopupMenu 没有 invoker 时不会被当成「桌面弹出菜单」（JDK-6192557），
        // 点击别处不会自动关闭；把 invoker 指向自己是最常用的绕法
        menu.setInvoker(menu);
        menu.setVisible(true);
    }

    /**
     * 兜底：确保弹出菜单在点击别处时关闭。
     *
     * <p>上面那个 {@code setInvoker(menu)} 在部分 JDK 与平台上仍不足以让菜单自动关闭，
     * 因此这里再挂一个全局鼠标监听：菜单可见时，只要在菜单范围之外按下鼠标就关掉它。</p>
     */
    private static void installDismissGuard(JPopupMenu menu) {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof MouseEvent mouse) || mouse.getID() != MouseEvent.MOUSE_PRESSED) {
                return;
            }
            if (!menu.isVisible()) {
                return;
            }
            Point pointer = pointerLocation();
            if (pointer == null) {
                return;
            }
            try {
                // 点在菜单自己身上时不动它 —— 那一击是给菜单项处理的
                Rectangle bounds = new Rectangle(menu.getLocationOnScreen(), menu.getSize());
                if (!bounds.contains(pointer)) {
                    menu.setVisible(false);
                }
            } catch (RuntimeException e) {
                // 菜单正在关闭时可能取不到屏幕坐标，这种时候交给 Swing 自己收尾
                log.debug("读取弹出菜单位置失败，跳过本次关闭判定", e);
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
    }

    private static Point pointerLocation() {
        PointerInfo info = MouseInfo.getPointerInfo();
        return info == null ? null : info.getLocation();
    }

    // 托盘回调运行在 AWT 自己的线程上，改界面必须切回事件分发线程（EDT）
    private static void showWindow(Window window) {
        SwingUtilities.invokeLater(() -> {
            window.setVisible(true);
            window.toFront();
        });
    }
}
