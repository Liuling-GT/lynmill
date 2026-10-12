package com.tinyquery.desktop;

import com.tinyquery.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 桌面端「英文模式下不得出现中文」的守门。
 *
 * <p>单独一个类而不是并进 {@code NoChineseInEnglishModeTest}：桌面端的文案
 * 取值方法（{@code TrayIconInstaller.menuLabelOpen()}、
 * {@code ControlWindow.runningText()}、{@code StartupFailureDialog.buildMessage()}）
 * 都是包可见的，只有 {@code com.tinyquery.desktop} 包里的测试够得着。
 * 放在 i18n 包里会直接编译不过（报「不是公开的，无法从外部程序包中对其进行访问」）。</p>
 *
 * <p>这里一律驱动真实取值方法并断言它们产出的字符串，不查词表：
 * 查词表只能证明词表里有英文，证明不了界面用了词表。实测把
 * {@code ControlWindow} 里的 {@code Messages.get("desktop.window.running")}
 * 换回硬编码 {@code "● 运行中"}，只查词表的守门照样全绿 —— 词表是对的，
 * 只是没人用它。</p>
 */
@DisplayName("桌面端英文模式守门")
class DesktopEnglishModeTest {

    private static final String CJK = ".*[\\u4e00-\\u9fff].*";

    private String savedLocale;

    @BeforeEach
    void pinEnglish() {
        savedLocale = Messages.desktopLocale();
        Messages.setDesktopLocale(Messages.EN);
    }

    @AfterEach
    void restoreLocale() {
        Messages.setDesktopLocale(savedLocale);
    }

    // ==================== 托盘菜单 ====================

    @Test
    @DisplayName("托盘菜单三项文字全英文 —— 走真实取值方法")
    void trayMenuLabelsAreEnglish() {
        assertNoChinese("托盘·打开界面", TrayIconInstaller.menuLabelOpen());
        assertNoChinese("托盘·显示窗口", TrayIconInstaller.menuLabelShow());
        assertNoChinese("托盘·退出", TrayIconInstaller.menuLabelExit());
        assertNoChinese("托盘·字体校验样本", TrayIconInstaller.menuTextSample());
    }

    @Test
    @DisplayName("托盘菜单真的跟着语言变 —— 防止被写死或缓存首次取值")
    void trayMenuLabelsFollowLocale() {
        String en = TrayIconInstaller.menuLabelOpen();

        Messages.setDesktopLocale(Messages.ZH);
        String zh = TrayIconInstaller.menuLabelOpen();

        assertNotEquals(zh, en,
                "切换桌面端语言后托盘菜单文字没变，说明没走词表（被写死或被缓存了）");
    }

    // ==================== 控制窗口 ====================

    @Test
    @DisplayName("控制窗口四处文字全英文 —— 走真实取值方法")
    void controlWindowTextsAreEnglish() {
        // ControlWindow 是 JFrame，无头环境建不出实例，
        // 所以文案取值被抽成了包可见的静态方法，这里直接调
        assertNoChinese("控制窗口·运行状态", ControlWindow.runningText());
        assertNoChinese("控制窗口·地址提示", ControlWindow.addressTipText());
        assertNoChinese("控制窗口·打开按钮", ControlWindow.openButtonText());
        assertNoChinese("控制窗口·退出按钮", ControlWindow.exitButtonText());
    }

    @Test
    @DisplayName("控制窗口文字真的跟着语言变")
    void controlWindowTextsFollowLocale() {
        String en = ControlWindow.runningText();

        Messages.setDesktopLocale(Messages.ZH);
        String zh = ControlWindow.runningText();

        assertNotEquals(zh, en, "切换语言后控制窗口文字没变");
    }

    // ==================== 启动失败对话框 ====================

    @Test
    @DisplayName("启动失败对话框整段正文全英文 —— 走真实入口")
    void failureDialogBodyIsEnglish() {
        // buildMessage 是纯函数，不需要真的制造一次启动失败。
        // 用一个带 cause 的异常，让「原因」与「根本原因」两段都走进来
        String message = StartupFailureDialog.buildMessage(
                Messages.get("desktop.failure.headline"),
                new IllegalStateException("boom", new RuntimeException("root boom")),
                null);

        assertNoChinese("启动失败对话框正文", message);
        assertTrue(message.contains("Reason:"), message);
        assertTrue(message.contains("Root cause:"), message);
    }

    @Test
    @DisplayName("启动失败对话框给出日志路径时也全英文")
    void failureDialogBodyWithLogPathIsEnglish() {
        String message = StartupFailureDialog.buildMessage(
                Messages.get("desktop.failure.headline"),
                new IllegalStateException("boom"),
                Path.of("C:/tmp/tinyquery.log"));

        assertNoChinese("启动失败对话框正文（含日志路径）", message);
        assertTrue(message.contains("Detailed log:"), message);
    }

    @Test
    @DisplayName("异常为 null 时的兜底描述是英文")
    void describeNullIsEnglish() {
        // 这条分支藏在 describe() 里，不在 buildMessage 的字面量中，容易漏
        assertNoChinese("describe(null)", StartupFailureDialog.describe(null));
    }

    @Test
    @DisplayName("中文模式下同一批文字确实是中文 —— 两侧不是同一份")
    void textsAreChineseInChineseMode() {
        Messages.setDesktopLocale(Messages.ZH);

        assertTrue(ControlWindow.runningText().matches(CJK),
                "中文模式下控制窗口状态却是英文：" + ControlWindow.runningText());
        assertTrue(TrayIconInstaller.menuLabelExit().matches(CJK),
                "中文模式下托盘菜单却是英文：" + TrayIconInstaller.menuLabelExit());
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("异常自身的 message 不翻译 —— 技术细节与日志必须对得上")
    void exceptionMessageIsNotTranslated() {
        // 这是本功能刻意划的边界：界面文案翻译，异常自带的 message 不翻。
        // 翻了会让用户看到的报错与日志里那条对不上，反而更难排查
        String message = StartupFailureDialog.buildMessage(
                Messages.get("desktop.failure.headline"),
                new java.io.IOException("磁盘已满"),
                null);

        assertTrue(message.contains("磁盘已满"),
                "异常自身的 message 应当原样保留：\n" + message);
    }

    private static void assertNoChinese(String what, String text) {
        if (text == null) {
            return;
        }
        assertFalse(text.matches(CJK),
                what + " 在英文模式下出现了中文：「" + text + "」");
    }
}
