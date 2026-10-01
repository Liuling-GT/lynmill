package com.tinyquery.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StartupFailureDialog 启动失败提示")
class StartupFailureDialogTest {

    private static final String HEADLINE = "TinyQuery 启动失败，无法继续运行。";

    // ==================== 文案 ====================

    @Test
    @DisplayName("提示里同时给出异常类型、异常消息和日志路径")
    void messageContainsCauseAndLogPath(@TempDir Path dir) {
        Path logFile = dir.resolve("tinyquery.log");
        IOException failure = new IOException("无法创建用户数据目录：D:\\tinyquery-home");

        String message = StartupFailureDialog.buildMessage(HEADLINE, failure, logFile);

        assertTrue(message.startsWith(HEADLINE), message);
        assertTrue(message.contains("java.io.IOException"), message);
        assertTrue(message.contains("无法创建用户数据目录"), message);
        assertTrue(message.contains(logFile.toString()), message);
    }

    @Test
    @DisplayName("异常没有 message 时只显示类型名，不能出现 null 字样")
    void messageWithoutTextHasNoNullLiteral() {
        String message = StartupFailureDialog.buildMessage(HEADLINE, new NullPointerException(), null);

        assertTrue(message.contains("java.lang.NullPointerException"), message);
        assertFalse(message.contains("null"), "没有 message 时不应打印 null：" + message);
    }

    @Test
    @DisplayName("异常被 Spring 包装过时，同时给出「原因」和「根本原因」")
    void wrappedFailureShowsRootCause(@TempDir Path dir) {
        IOException root = new IOException("Address already in use");
        RuntimeException wrapper = new IllegalStateException("创建 WebServer 失败", root);

        String message = StartupFailureDialog.buildMessage(HEADLINE, wrapper, dir.resolve("tinyquery.log"));

        assertTrue(message.contains("原因："), message);
        assertTrue(message.contains("java.lang.IllegalStateException"), message);
        assertTrue(message.contains("根本原因："), message);
        assertTrue(message.contains("java.io.IOException"), message);
        assertTrue(message.indexOf("创建 WebServer 失败") < message.indexOf("Address already in use"),
                "最外层原因应排在根本原因之前：" + message);
    }

    @Test
    @DisplayName("只有一层异常时不出现「根本原因」，避免把同一句话写两遍")
    void singleLayerFailureHasNoRootSection(@TempDir Path dir) {
        String message = StartupFailureDialog.buildMessage(
                HEADLINE, new IOException("磁盘已满"), dir.resolve("tinyquery.log"));

        assertFalse(message.contains("根本原因："), message);
    }

    @Test
    @DisplayName("日志文件写不出来时提示用户截图，而不是留一段空白")
    void missingDetailFileTellsUserToScreenshot() {
        String message = StartupFailureDialog.buildMessage(HEADLINE, new IOException("磁盘已满"), null);

        assertTrue(message.contains("截图"), message);
    }

    @Test
    @DisplayName("failure 为 null 时也能拼出完整文案，不抛异常")
    void nullFailureIsTolerated() {
        String message = StartupFailureDialog.buildMessage(HEADLINE, null, null);

        assertTrue(message.contains(HEADLINE), message);
        assertTrue(message.contains("没有可用的错误信息"), message);
    }

    // ==================== 异常链 ====================

    @Test
    @DisplayName("rootCause 追到异常链的最末端")
    void rootCauseReachesTheEnd() {
        IOException root = new IOException("最底层");

        assertSame(root, StartupFailureDialog.rootCause(new RuntimeException("外层", root)));
        assertSame(root, StartupFailureDialog.rootCause(
                new RuntimeException("更外层", new RuntimeException("外层", root))));
    }

    @Test
    @DisplayName("异常链自引用时不会死循环")
    void selfReferencingCauseTerminates() {
        SelfCausingException failure = new SelfCausingException("自引用");

        assertSame(failure, StartupFailureDialog.rootCause(failure));
    }

    @Test
    @DisplayName("两个异常互相引用时不会死循环")
    void mutuallyReferencingCausesTerminate() {
        LoopingException first = new LoopingException("甲");
        LoopingException second = new LoopingException("乙");
        first.setPartner(second);
        second.setPartner(first);

        // getCause() 可以被任意子类改写，不能假设它一定能走到 null ——
        // 深度上限就是为这种情况准备的
        assertNotNull(StartupFailureDialog.rootCause(first));
        assertNotNull(StartupFailureDialog.rootCause(second));
    }

    // ==================== 崩溃报告 ====================

    @Test
    @DisplayName("崩溃报告写进指定目录，文件名固定")
    void crashReportIsWrittenWithFixedName(@TempDir Path dir) {
        Path written = StartupFailureDialog.writeCrashReport(new IOException("磁盘已满"), dir);

        assertNotNull(written, "报告应写入成功");
        assertEquals(dir.resolve(StartupFailureDialog.CRASH_FILE_NAME), written);
        assertTrue(Files.exists(written));
    }

    @Test
    @DisplayName("崩溃报告里带完整堆栈，事后能定位到出错位置")
    void crashReportKeepsStackTrace(@TempDir Path dir) throws IOException {
        Path written = StartupFailureDialog.writeCrashReport(new IOException("磁盘已满"), dir);

        assertNotNull(written);
        String content = Files.readString(written);
        assertTrue(content.contains("java.io.IOException: 磁盘已满"), content);
        assertTrue(content.contains("StartupFailureDialogTest"), "堆栈里应出现本测试类：" + content);
    }

    @Test
    @DisplayName("目标目录不存在时自动创建")
    void crashReportCreatesMissingDirectory(@TempDir Path dir) {
        Path nested = dir.resolve("a").resolve("b").resolve("c");

        Path written = StartupFailureDialog.writeCrashReport(new IOException("磁盘已满"), nested);

        assertNotNull(written);
        assertTrue(Files.isDirectory(nested));
    }

    @Test
    @DisplayName("目录不可写时返回 null 而不抛异常 —— 它自己崩了会把真正的原因盖掉")
    void unwritableDirectoryReturnsNull(@TempDir Path dir) throws IOException {
        // 把一个「文件」当成目录传进去：三个平台都会失败，
        // 而且不需要真的去改权限（改权限的测试在 Windows 上常常做不出来）
        Path notADirectory = dir.resolve("not-a-directory");
        Files.writeString(notADirectory, "我是文件，不是目录");

        assertNull(StartupFailureDialog.writeCrashReport(new IOException("磁盘已满"), notADirectory));
    }

    @Test
    @DisplayName("目录为 null 时返回 null")
    void nullDirectoryReturnsNull() {
        assertNull(StartupFailureDialog.writeCrashReport(new IOException("磁盘已满"), null));
    }

    @Test
    @DisplayName("failure 为 null 时报告仍能写出")
    void nullFailureStillProducesReport(@TempDir Path dir) {
        Path written = StartupFailureDialog.writeCrashReport(null, dir);

        assertNotNull(written);
        assertTrue(Files.exists(written));
    }

    // ==================== 环境 ====================

    @Test
    @DisplayName("isHeadless 在真实环境里可调用且不抛异常")
    void headlessCheckIsSafe() {
        assertDoesNotThrow(StartupFailureDialog::isHeadless);
    }

    // ==================== 测试替身 ====================

    /** getCause() 指向自己。现实中不会发生，但要保证提示逻辑不会被它挂死 */
    private static final class SelfCausingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SelfCausingException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    /** 两个异常互相指向。同上，验证深度上限真的在起作用 */
    private static final class LoopingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private Throwable partner;

        LoopingException(String message) {
            super(message);
        }

        void setPartner(Throwable partner) {
            this.partner = partner;
        }

        @Override
        public synchronized Throwable getCause() {
            return partner;
        }
    }
}
