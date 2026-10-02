package com.tinyquery.desktop;

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
 * <p>它存在的首要理由是<b>让用户能退出</b>。这个应用没有控制台，用户关掉浏览器之后，
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
     * 「● 运行中」的颜色，用应用主色紫（与图标、网页主题一致）。
     *
     * <p>原来是绿色 {@code #1E7B34} —— 那是当年占位图标用的颜色，
     * 换成紫色标志后两边就对不上了。</p>
     */
    private static final Color RUNNING_COLOR = new Color(0x6D4AFF);

    /**
     * 把窗口摆到屏幕右下角。
     *
     * <p>刻意<b>不用</b> {@code setLocationRelativeTo(null)} —— 那是「屏幕居中」。
     * 居中会正好压在用户要看的东西上：这个窗口只是「程序在运行」的状态指示，
     * 真正的内容在自动打开的浏览器里，它挡在中间纯属碍事。
     * 右下角是系统托盘、通知气泡所在的角落，用户已经习惯去那里看状态。</p>
     *
     * <p>用 {@link GraphicsEnvironment#getMaximumWindowBounds()} 而不是
     * {@code Toolkit.getScreenSize()}：前者<b>自动排除任务栏</b>，
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
     * 计算右下角坐标。抽成<b>纯函数</b>是为了能直接测 —— 真实屏幕尺寸、
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
     * <p><b>顺序不能反</b>：必须 {@code setState(ICONIFIED)} 在前、
     * {@code setVisible(true)} 在后。反过来（先显示再最小化）实测是个<b>竞态</b> ——
     * 最小化请求发出时窗口可能还没被窗口管理器映射出来，请求就丢了，
     * 结果窗口要么留在屏幕上、要么干脆不出现。实测数据（三个全新 JVM，各跑 5 轮）：
     * 本顺序 15/15 成功；反过来 14/15，失败那次窗口就明晃晃留在屏幕上。
     * 先置状态再显示则不存在这个窗口期：窗口一出生就是最小化的。</p>
     *
     * <p>顺带的好处：正因为一出生就是最小化，窗口不会先在屏幕上闪一下。</p>
     *
     * <p><b>不要</b>改成「先挪到屏幕外再显示」那种写法：窗口的还原位置取的就是
     * 最小化之前的位置，挪到 (-32000,-32000) 之后用户从任务栏把它调出来，
     * 它会出现在屏幕外面，等于又找不着了。</p>
     */
    public void minimizeToTaskbar() {
        setState(Frame.ICONIFIED);
        setVisible(true);
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

        JLabel status = new JLabel("● 运行中");
        status.setForeground(RUNNING_COLOR);
        status.setFont(status.getFont().deriveFont(Font.BOLD));

        // 用只读文本框而不是标签：用户可以选中、Ctrl+C 复制地址。
        // 自动打开浏览器失败时（或被 tinyquery.browser.auto-open 关掉时），
        // 手动复制粘贴是最后的退路
        JTextField address = new JTextField(url);
        address.setEditable(false);
        address.setBorder(null);
        address.setOpaque(false);
        address.setForeground(Color.GRAY);
        address.setPreferredSize(new Dimension(ADDRESS_WIDTH, 24));
        address.setToolTipText("可选中后复制");

        panel.add(status, BorderLayout.NORTH);
        panel.add(address, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildButtonArea(Runnable onOpenBrowser, Runnable onExit) {
        JButton open = new JButton("打开界面");
        open.addActionListener(event -> onOpenBrowser.run());

        JButton exit = new JButton("退出");
        exit.addActionListener(event -> onExit.run());

        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 0));
        panel.add(open);
        panel.add(exit);
        return panel;
    }
}
