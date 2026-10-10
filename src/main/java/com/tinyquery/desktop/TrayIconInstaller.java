package com.tinyquery.desktop;

import com.tinyquery.i18n.DesktopLocale;
import com.tinyquery.i18n.Messages;

import com.tinyquery.config.BrowserOpener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.LineBorder;
import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
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
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

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
 *
 * <h2>收起菜单：为什么不能只靠 AWT 全局监听</h2>
 *
 * <p>菜单弹出来之后，点<b>别的地方</b>就该收起它。直觉做法是挂一个
 * {@code Toolkit.addAWTEventListener}，但实测<b>不管用</b>：这个监听器只能收到
 * <b>本程序自己窗口</b>的鼠标事件。用户点桌面或任务栏时，事件根本不在本进程里，
 * 监听器一次都不会被触发 —— 菜单就一直留在屏幕上，只能点菜单项或点本程序窗口才消失。</p>
 *
 * <p>所以改为在菜单四周铺 4 块近乎全透明的窗口（{@link #surroundingStrips}），
 * 由它们去接住菜单外面的点击。这些窗口<b>不与菜单重叠</b>，所以不存在
 * 「谁盖住谁」的层级问题 —— 这是刻意选的方案，比赌两个置顶窗口的先后顺序可靠。</p>
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

    // 菜单文字走词表，语言由 Messages.desktopLocale() 在启动时定下。
    //
    // 原来是 static final 常量，改成方法是被迫的：常量在**类加载时**求值，
    // 而语言要到启动流程里才确定（读环境变量或 locale.txt），
    // 常量会永远停在默认值上。同理，这里的取值不能提到构造器或静态块里。
    static String menuLabelOpen() {
        return Messages.get("desktop.tray.open");
    }

    static String menuLabelShow() {
        return Messages.get("desktop.tray.show");
    }

    static String menuLabelExit() {
        return Messages.get("desktop.tray.exit");
    }

    /**
     * 菜单里出现的全部文字，供测试校验字体覆盖。
     *
     * <p>拿的是当前语言下的实际文字 —— 中文两个字、英文两个词，
     * 字体覆盖要求也不同（英文只要拉丁字形，中文要 CJK 字形），
     * 用一份写死的样本文本会漏掉另一种语言。</p>
     */
    static String menuTextSample() {
        return menuLabelOpen() + menuLabelShow() + menuLabelExit();
    }

    /**
     * 当前弹出的菜单，供 {@link #dismissMenu()} 在退出时收起它。
     *
     * <p>用 volatile：写发生在 EDT 或 AWT 线程上，读可能发生在退出线程上。</p>
     */
    private static volatile MenuPopup activeMenu;

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
        for (char c : menuTextSample().toCharArray()) {
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

            MenuPopup popup = new MenuPopup(url, () -> showWindow(window), onExit);
            activeMenu = popup;

            TrayIcon icon = new TrayIcon(AppIcons.create(TRAY_ICON_SIZE), "TinyQuery");
            // 让操作系统按托盘实际尺寸缩放，避免高 DPI 下图标糊掉
            icon.setImageAutoSize(true);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    maybeShowMenu(event, popup);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    maybeShowMenu(event, popup);
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
     * 收起当前弹出的托盘菜单。
     *
     * <p>退出流程会调用：{@link ApplicationShutdown#shutdown()} 走完 Spring 关闭后直接
     * {@code System.exit}，菜单若还留在屏幕上，会一直挂到进程真正结束，
     * 用户看到的就是「程序都退了，菜单还浮在那里」。</p>
     */
    public static void dismissMenu() {
        MenuPopup current = activeMenu;
        if (current == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            current.dismiss();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(current::dismiss);
        } catch (InterruptedException e) {
            // 退出流程里被打断不奇怪，恢复中断标记即可
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            log.debug("收起托盘菜单时出错", e);
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

        menu.add(menuItem(menuLabelOpen(), font, () -> BrowserOpener.open(url)));
        menu.add(menuItem(menuLabelShow(), font, onShowWindow));
        menu.add(menuSeparator());
        menu.add(menuItem(menuLabelExit(), font, onExit));
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

    // ==================== 弹出与收起 ====================

    // 右键的判定时机各平台不同，pressed 与 released 都要检查 isPopupTrigger()
    private static void maybeShowMenu(MouseEvent event, MenuPopup popup) {
        if (event.isPopupTrigger()) {
            // 语言可能在网页上被改过（设置页会同步到服务端）。菜单项文字是构建时定死的，
            // 所以每次弹出前对一下版本号，变过就重建 —— 这样用户切完语言
            // 下次右键就能看到新语言，不必重启程序
            popup.refreshIfLocaleChanged();
            popup.show();
        }
    }

    /**
     * 弹出菜单 + 接住菜单外面点击的那层透明窗口。
     *
     * <p>菜单本身仍是 {@link JPopupMenu}（理由见类注释），外面的点击由
     * {@link #surroundingStrips} 铺出来的 4 条透明窗口接住。</p>
     */
    private static final class MenuPopup {

        /**
         * 菜单的构建参数。菜单项文字依赖当前语言，而语言可以在运行期被切换
         * （网页上改了设置会同步过来），所以这些参数要留着，以便按需重建菜单。
         */
        private final String url;
        private final Runnable onShowWindow;
        private final Runnable onExit;

        /**
         * 当前菜单，以及它构建时所用的语言版本号。
         *
         * <p>不每次右键都重建：菜单里有自定义 UI、字体探测这类一次性开销，
         * 每次重建会让右键变慢。只在语言真的变过之后才重建 ——
         * 靠 {@link DesktopLocale#version()} 比对。</p>
         */
        private JPopupMenu menu;
        private long builtAtVersion;
        private final List<JWindow> strips = new ArrayList<>();

        MenuPopup(String url, Runnable onShowWindow, Runnable onExit) {
            this.url = url;
            this.onShowWindow = onShowWindow;
            this.onExit = onExit;
            buildAndWire();
        }

        /**
         * 构建菜单并挂上「点菜单外面就收起」的守卫。
         *
         * <p>守卫必须跟着菜单实例走 —— {@code installDismissGuard} 把监听器挂在
         * 具体的 {@link JPopupMenu} 上，重建后不重新挂就等于失去了这个行为。
         * 所以构建与挂载放在一起，避免将来有人在重建时漏掉一步。</p>
         */
        private void buildAndWire() {
            this.menu = buildMenu(url, onShowWindow, onExit);
            this.builtAtVersion = DesktopLocale.version();
            installDismissGuard(menu, this);
        }

        /**
         * 语言变过就重建菜单，让菜单项显示新语言。
         *
         * <p>必须在<b>弹出之前</b>、且在事件分发线程上调用 ——
         * 重建会替换掉 {@link #menu} 这个被多处引用的对象。</p>
         */
        void refreshIfLocaleChanged() {
            long current = DesktopLocale.version();
            if (current == builtAtVersion) {
                return;
            }

            // 先把旧的收起来：它可能正显示着，直接换掉会留下一个孤儿窗口
            dismiss();

            long previous = builtAtVersion;
            buildAndWire();
            log.debug("界面语言已变更（版本 {} → {}），托盘菜单已重建", previous, current);
        }

        JPopupMenu menu() {
            return menu;
        }

        void show() {
            // 托盘鼠标事件的坐标是相对任务栏的，不能用；必须取鼠标的屏幕绝对坐标
            Point location = pointerLocation();
            if (location == null) {
                return;
            }
            // 先收掉上一次的，避免连点右键留下两套接住层
            hideStrips();

            menu.setLocation(location.x, location.y);
            // JPopupMenu 没有 invoker 时不会被当成「桌面弹出菜单」（JDK-6192557），
            // 把 invoker 指向自己是最常用的绕法
            menu.setInvoker(menu);
            menu.setVisible(true);

            // 菜单尺寸要等它显示出来才准，弹出前是 0
            showStrips(new Rectangle(menu.getLocationOnScreen(), menu.getSize()));
        }

        void dismiss() {
            hideStrips();
            menu.setVisible(false);
        }

        // ---------- 接住层 ----------

        private void showStrips(Rectangle menuBounds) {
            // 菜单尺寸拿不到时<b>绝不能</b>铺接住层：那时算出来的 4 条会把整个屏幕
            // 都盖上，包括菜单自己所在的位置 —— 菜单就点不动了，
            // 比「点别处不消失」严重得多。宁可退回原来的行为
            if (menuBounds.width <= 0 || menuBounds.height <= 0) {
                log.debug("菜单尺寸尚未确定（{}），本次不铺接住层", menuBounds);
                return;
            }
            Rectangle screen = screenBoundsContaining(menuBounds);
            if (screen == null) {
                return;
            }
            for (Rectangle bounds : surroundingStrips(menuBounds, screen)) {
                JWindow strip = createStrip(bounds);
                if (strip != null) {
                    strips.add(strip);
                }
            }
        }

        private void hideStrips() {
            for (JWindow strip : strips) {
                strip.setVisible(false);
                strip.dispose();
            }
            strips.clear();
        }

        private JWindow createStrip(Rectangle bounds) {
            if (bounds.width <= 0 || bounds.height <= 0) {
                // 菜单贴着屏幕边时这一条宽度或高度为 0，没有存在的必要
                return null;
            }
            JWindow strip = new JWindow();
            // 不抢焦点：抢了会把用户正在用的窗口切走，而它只是个透明的接住层
            strip.setFocusableWindowState(false);
            strip.setAlwaysOnTop(true);
            strip.setBounds(bounds);
            // 近乎全透明。alpha 必须 > 0 —— 完全透明（alpha=0）的窗口在 Windows 上
            // 会被当成「点击穿透」，鼠标事件根本落不到它身上，就接不住点击了
            strip.setBackground(new Color(0, 0, 0, 1));
            strip.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    dismiss();
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    dismiss();
                }
            });
            strip.setVisible(true);
            return strip;
        }
    }

    /**
     * 菜单四周那 4 块「接住点击」的区域（不含菜单自身）。
     *
     * <p>拆成上下左右 4 条、而不是一整块盖住全屏，是为了<b>绝不与菜单重叠</b>：
     * 一整块全屏窗口和菜单都是置顶窗口，谁压在谁上面取决于层级细节，
     * 赌错就变成「菜单点不动」这种严重问题。4 条围着放，这个风险根本不存在。</p>
     *
     * <p>纯函数，可直接测。</p>
     *
     * @param menu   菜单在屏幕上的矩形
     * @param screen 菜单所在的那块屏幕
     * @return 4 个矩形；菜单贴边时可能含宽或高为 0 的项，调用方需自行跳过
     */
    static List<Rectangle> surroundingStrips(Rectangle menu, Rectangle screen) {
        int menuLeft = menu.x;
        int menuTop = menu.y;
        int menuRight = menu.x + menu.width;
        int menuBottom = menu.y + menu.height;
        int screenRight = screen.x + screen.width;
        int screenBottom = screen.y + screen.height;

        List<Rectangle> strips = new ArrayList<>(4);
        // 上：整屏宽，屏幕顶 → 菜单顶
        strips.add(new Rectangle(screen.x, screen.y, screen.width, menuTop - screen.y));
        // 下：整屏宽，菜单底 → 屏幕底
        strips.add(new Rectangle(screen.x, menuBottom, screen.width, screenBottom - menuBottom));
        // 左：只取菜单高度那一段，避免与上下两条重叠
        strips.add(new Rectangle(screen.x, menuTop, menuLeft - screen.x, menu.height));
        // 右
        strips.add(new Rectangle(menuRight, menuTop, screenRight - menuRight, menu.height));
        return strips;
    }

    /**
     * 找出包含目标矩形的那块屏幕。
     *
     * <p>多显示器时不能简单用主屏 —— 托盘图标在副屏上时，接住层会铺到主屏去，
     * 副屏上的点击就接不住。</p>
     */
    static Rectangle screenBoundsContaining(Rectangle target) {
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice device : env.getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            if (bounds.contains(target.x, target.y)) {
                return bounds;
            }
        }
        // 找不到就退回主屏，至少还盖得住菜单所在的位置
        return env.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    /**
     * 兜底：本程序自己窗口范围内的点击也要能收起菜单。
     *
     * <p>接住层只铺在菜单外面，但用户点的可能是<b>本程序自己的窗口</b>
     * （比如控制窗口）。那种点击是能收到 AWT 事件的，直接收掉即可，
     * 不必依赖接住层。</p>
     */
    private static void installDismissGuard(JPopupMenu menu, MenuPopup popup) {
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
                    popup.dismiss();
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
            // 窗口平时是「最小化到任务栏」的形态，光 setVisible(true) 是调不出来的 ——
            // 它本来就是「可见」的，只是被最小化了。必须显式恢复成 NORMAL，
            // 否则用户点「显示窗口」会觉得点了没反应
            if (window instanceof Frame frame && frame.getState() == Frame.ICONIFIED) {
                frame.setState(Frame.NORMAL);
            }
            window.toFront();
        });
    }
}
