package com.tinyquery.desktop;

import com.tinyquery.i18n.DesktopLocale;
import com.tinyquery.i18n.Messages;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * TinyQuery 的控制窗口 —— 一个极小的 Swing 窗口，是整个桌面外壳的主界面。
 *
 * <p>它存在的首要理由是让用户能退出。这个应用没有控制台，用户关掉浏览器之后，
 * 后台的 java 进程仍在跑；只靠系统托盘不行，因为 Linux 的 GNOME 3 默认没有托盘区。
 * 一个普通窗口在三个平台上都可靠，托盘则降级为「有更好、没有也行」的增强。</p>
 *
 * <p>附带好处：端口回退（8080 被占时改用 8081）导致访问地址不固定，此窗口把
 * 真实地址直接摆在用户眼前，不必去翻日志。</p>
 *
 * <p>本类只负责布局和事件转发，「打开浏览器」与「退出」的实际动作由构造参数传入 ——
 * 这样界面代码里没有逻辑，逻辑都留在可测的类里。</p>
 */
public class ControlWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    // 固定窗口宽度，让地址栏不会因 URL 长短而忽宽忽窄
    private static final int ADDRESS_WIDTH = 320;

    // 窗口与屏幕边缘（含任务栏区）保持的间距
    private static final int SCREEN_MARGIN = 24;

    /**
     * 「● 运行中」的颜色，跟随当前主题的强调色。
     *
     * <p>原来是绿色 {@code #1E7B34} —— 那是当年占位图标用的颜色，
     * 换成紫色标志后两边就对不上了。现在改为从 {@link DesktopTheme} 取，
     * 于是网页上换了主题，这里也跟着变。</p>
     *
     * <p>不再是 {@code static final}：主题可以在运行期变，
     * 所以每次取值都要重新问一遍。见 {@link #refreshAppearance()}。</p>
     *
     * <p>包可见是为了能直接测 —— {@code ControlWindow} 是 JFrame，
     * 无头环境里建不出来（理由同 {@link #runningText()} 那一段）。</p>
     */
    static Color runningColor() {
        return DesktopTheme.accent();
    }

    /**
     * 语言或主题任一版本变过就该刷新。
     *
     * <p>抽成包可见的纯函数是为了能直接测：{@code ControlWindow} 是 JFrame，
     * 无头环境里建不出来（同 {@link #runningText()} 那一段的理由），
     * 而「只变了主题时会不会刷新」恰恰是这里最容易写错的地方 ——
     * 把两个版本号合成一个（比如只比 {@code DesktopLocale.version()}）
     * 就会丢掉「只有主题变了」，颜色不更新，而语言切换看起来完全正常。</p>
     */
    static boolean needsRefresh(long appliedLocale, long appliedTheme,
                                long localeVersion, long themeVersion) {
        return localeVersion != appliedLocale || themeVersion != appliedTheme;
    }

    // 界面控件留引用，语言切换后要重新 setText（见 refreshTexts）
    private final JLabel statusLabel = new JLabel();
    private final JTextField addressField = new JTextField();
    private final JButton openButton = new JButton();
    private final JButton exitButton = new JButton();

    /** 当前界面文字对应的语言版本号，用来判断要不要刷新 */
    private long appliedVersion = DesktopLocale.version();

    /** 当前界面颜色对应的主题版本号。与语言分开记 —— 两者各变各的 */
    private long appliedThemeVersion = DesktopTheme.version();

    /**
     * 把窗口摆到屏幕右下角。
     *
     * <p>刻意不用 {@code setLocationRelativeTo(null)} —— 那是「屏幕居中」。
     * 居中会正好压在用户要看的东西上：这个窗口只是「程序在运行」的状态指示，
     * 真正的内容在自动打开的浏览器里，它挡在中间纯属碍事。
     * 右下角是系统托盘、通知气泡所在的角落，用户已经习惯去那里看状态。</p>
     *
     * <p>用 {@link GraphicsEnvironment#getMaximumWindowBounds()} 而不是
     * {@code Toolkit.getScreenSize()}：前者自动排除任务栏，
     * 后者不会 —— 用后者算出来的位置会把窗口压在任务栏下面，
     * 底部那条按钮正好被挡住，而且这个问题只在任务栏位于底部时才出现，
     * 换个设置就复现不了，很费时间。</p>
     *
     * <p>必须在 {@code pack()} 之后调用：那时 {@code getWidth()/getHeight()}
     * 才是最终尺寸，否则算出来的是 0，窗口会跑到屏幕右下角之外。</p>
     */
    private void placeAtBottomRight() {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        Point p = bottomRightPosition(usable, getWidth(), getHeight(), SCREEN_MARGIN);
        setLocation(p);
    }

    /**
     * 计算右下角坐标。抽成纯函数是为了能直接测 —— 真实屏幕尺寸、
     * 任务栏位置在测试里都拿不到，而这段算术恰恰是最容易写错的地方
     * （正负号、边界、窗口比屏幕还大）。
     *
     * @param usable  可用区域（已排除任务栏）
     * @param winW    窗口宽
     * @param winH    窗口高
     * @param margin  与边缘的间距
     * @return 窗口左上角坐标；空间不足时贴左上边界，绝不为负
     */
    static Point bottomRightPosition(Rectangle usable, int winW, int winH, int margin) {
        int x = usable.x + usable.width - winW - margin;
        int y = usable.y + usable.height - winH - margin;

        // 兜底：屏幕太小、或窗口比可用区域还大时，算出来会是负数，
        // 那会把标题栏推到屏幕外，用户既看不到也拖不回来。
        // 宁可贴边（且允许窗口超出右/下边界）也不要让左上角跑到可见区之外
        return new Point(Math.max(usable.x, x), Math.max(usable.y, y));
    }

    /**
     * 把窗口最小化到任务栏。
     *
     * <p>这是启动后窗口的默认形态：屏幕上不占地方，但任务栏里留着一个按钮，
     * 用户随时点一下就能调出来。</p>
     *
     * <p>顺序不能反：必须 {@code setState(ICONIFIED)} 在前、
     * {@code setVisible(true)} 在后。反过来（先显示再最小化）实测是个竞态 ——
     * 最小化请求发出时窗口可能还没被窗口管理器映射出来，请求就丢了，
     * 结果窗口要么留在屏幕上、要么干脆不出现。实测数据（三个全新 JVM，各跑 5 轮）：
     * 本顺序 15/15 成功；反过来 14/15，失败那次窗口就明晃晃留在屏幕上。
     * 先置状态再显示则不存在这个窗口期：窗口一出生就是最小化的。</p>
     *
     * <p>顺带的好处：正因为一出生就是最小化，窗口不会先在屏幕上闪一下。</p>
     *
     * <p>不要改成「先挪到屏幕外再显示」那种写法：窗口的还原位置取的就是
     * 最小化之前的位置，挪到 (-32000,-32000) 之后用户从任务栏把它调出来，
     * 它会出现在屏幕外面，等于又找不着了。</p>
     */
    public void minimizeToTaskbar() {
        setState(Frame.ICONIFIED);
        setVisible(true);
    }

    /**
     * 语言或主题切换后刷新界面上的文字与颜色。
     *
     * <p>Swing 控件在 {@code setText} / {@code setForeground} 之后不会自己察觉
     * 偏好变了 —— 语言表与主题表里改的是「下次取值时用什么」，
     * 而已经设进控件的文字与颜色是那一刻定死的。所以必须显式重设一遍。</p>
     *
     * <p>由 {@code DesktopSyncService} 在偏好变更后调（经
     * {@link javax.swing.SwingUtilities#invokeLater} 切到事件分发线程）。</p>
     */
    void refreshAppearance() {
        statusLabel.setText(runningText());
        statusLabel.setForeground(runningColor());
        addressField.setToolTipText(addressTipText());
        openButton.setText(openButtonText());
        exitButton.setText(exitButtonText());
        repaint();
    }

    /**
     * 语言或主题任一版本变过就刷新。
     *
     * <p>两个版本号分开比对、共用一个刷新动作：两者都会改到界面上的东西，
     * 但改的不是同一批 —— 语言动文字，主题动颜色。合成一个版本号会丢掉
     * 「只有主题变了」这种情况，那样颜色就不会更新。</p>
     */
    void refreshIfChanged() {
        long localeVersion = DesktopLocale.version();
        long themeVersion = DesktopTheme.version();
        if (!needsRefresh(appliedVersion, appliedThemeVersion, localeVersion, themeVersion)) {
            return;
        }
        appliedVersion = localeVersion;
        appliedThemeVersion = themeVersion;
        refreshAppearance();
    }

    /**
     * @param url            本实例的实际访问地址
     * @param onOpenBrowser  点「打开界面」时执行
     * @param onExit         点「退出」或右上角 ✕ 时执行
     */
    public ControlWindow(String url, Runnable onOpenBrowser, Runnable onExit) {
        super("TinyQuery");

        // 关闭动作交给我们自己处理，让 ✕ 走和「退出」按钮完全相同的优雅退出流程。
        // 刻意不做「最小化到托盘」—— 那会重新制造出「我明明关了它还在跑」的困惑，
        // 而这正是本窗口要解决的问题
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                onExit.run();
            }
        });

        setIconImage(AppIcons.create(64));
        setResizable(false);
        setContentPane(buildContent(url, onOpenBrowser, onExit));
        pack();
        // 放到右下角而不是居中。注意必须在 pack() 之后调用，
        // 否则窗口尺寸还是 0，算出位置会把窗口推到屏幕外
        placeAtBottomRight();
    }

    private JPanel buildContent(String url, Runnable onOpenBrowser, Runnable onExit) {
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.add(buildStatusArea(url), BorderLayout.CENTER);
        root.add(buildButtonArea(onOpenBrowser, onExit), BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildStatusArea(String url) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));

        // 用类字段而不是局部变量：语言切换后要重新 setText，得留着引用
        statusLabel.setText(runningText());
        statusLabel.setForeground(runningColor());
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));

        // 用只读文本框而不是标签：用户可以选中、Ctrl+C 复制地址。
        // 自动打开浏览器失败时（或被 tinyquery.browser.auto-open 关掉时），
        // 手动复制粘贴是最后的退路
        addressField.setText(url);
        addressField.setEditable(false);
        addressField.setBorder(null);
        addressField.setOpaque(false);
        addressField.setForeground(Color.GRAY);
        addressField.setPreferredSize(new Dimension(ADDRESS_WIDTH, 24));
        addressField.setToolTipText(addressTipText());

        panel.add(statusLabel, BorderLayout.NORTH);
        panel.add(addressField, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildButtonArea(Runnable onOpenBrowser, Runnable onExit) {
        openButton.setText(openButtonText());
        openButton.addActionListener(event -> onOpenBrowser.run());

        exitButton.setText(exitButtonText());
        exitButton.addActionListener(event -> onExit.run());

        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 0));
        panel.add(openButton);
        panel.add(exitButton);
        return panel;
    }

    // ==================== 文案 ====================
    //
    // 抽成包可见的方法而不是内联 Messages.get(...)，是为了让测试够得着：
    // ControlWindow 是 JFrame，构造它需要真实显示设备，无头环境里建不出来，
    // 而「界面上的字有没有跟着语言走」恰恰必须在无头 CI 上也能验。
    // 这几个方法把「取哪条文案」独立出来，测试直接调它们即可。

    static String runningText() {
        return Messages.get("desktop.window.running");
    }

    static String addressTipText() {
        return Messages.get("desktop.window.addressTip");
    }

    static String openButtonText() {
        return Messages.get("desktop.window.open");
    }

    static String exitButtonText() {
        return Messages.get("desktop.window.exit");
    }
}
