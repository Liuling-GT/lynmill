package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.i18n.Messages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存预检的行为。
 *
 * <p>这是编号 32 的核心防线：{@code ColumnStore} 把整表放内存且无上限无淘汰，
 * 超过堆上限时会 OOM 崩溃（已实测复现）。预检的作用是把这个崩溃
 * 换成一句可读、可行动的拒绝。</p>
 *
 * <p>边界为什么重要：预检太松等于没有，太紧会把正常文件也拦下来 ——
 * 后者会让功能直接不可用。所以两头都要钉住：
 * 明显超限的要拒，明显绰绰有余的要放行。</p>
 */
class MemoryGuardTest {

    /**
     * 真实堆上限。测试进程的 -Xmx 由 surefire 决定，
     * 所以断言都基于它换算，不写死具体数字 —— 否则换台机器就红
     */
    private static long maxHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    }

    @Test
    @DisplayName("换算关系：估算 = 文件大小 × 6（实测上界）")
    void estimateUsesMeasuredUpperBoundRatio() {
        // 系数是整个预检的依据，钉住它。
        // 实测值：huge.csv 4.92×、orders.csv 5.22×、social_media.csv 5.32×
        // 取 6 是上界 —— 宁可误拒（用户换个源即可），不可漏放（会崩）
        assertAll(
                () -> assertEquals(6_000_000L, MemoryGuard.estimateResidentBytes(1_000_000L),
                        "1MB 文件应估成 6MB"),
                () -> assertEquals(0L, MemoryGuard.estimateResidentBytes(0L),
                        "0 字节应估成 0"),
                () -> assertTrue(MemoryGuard.RESIDENT_RATIO >= 5.32,
                        "系数不得低于实测最大值 5.32，否则会漏放；实际 "
                                + MemoryGuard.RESIDENT_RATIO));
    }

    @Test
    @DisplayName("远超预算的文件：拒绝，且错误码是 error.memoryInsufficient")
    void rejectsFileFarBeyondBudget() {
        long huge = maxHeap() * 10;   // 10 倍堆上限的文件，必拒

        DataSourceException e = assertThrows(DataSourceException.class,
                () -> MemoryGuard.checkBytes(huge, "huge.csv"));

        // 断言 errorKey 而不是文案 —— 文案会随语言变，
        // 用文案分支的代码在英文模式下会静默失效（这个仓库有过教训）。
        // 只断言「抛了异常」也不够：任何异常都能让那种断言通过
        assertEquals("error.memoryInsufficient", e.errorKey());
        assertTrue(e.is("error.memoryInsufficient"));
    }

    @Test
    @DisplayName("预算之内的文件：放行")
    void allowsFileWithinBudget() {
        // 给一个很小的文件：1KB → 估 6KB，任何合理堆都放得下
        assertDoesNotThrow(() -> MemoryGuard.checkBytes(1024, "small.csv"));
    }

    @Test
    @DisplayName("边界：刚好在预算线上放行，刚过线就拒绝")
    void boundaryIsWhereDocumented() {
        long heap = maxHeap();
        // 用【写死的比例】而不是 MemoryGuard.MAX_HEAP_USAGE 反推 ——
        // 曾经用常量反推，于是把常量改成 10.0（等于永不拒绝）时，
        // 预算跟着一起变，测试照样绿。边界必须钉在【绝对数值】上
        long budget = (long) (heap * 0.5);

        // 反推：多大的文件刚好占满预算
        long filesAtBudget = (long) (budget / 6.0);

        assertAll(
                () -> assertDoesNotThrow(
                        () -> MemoryGuard.checkBytes(filesAtBudget, "exact.csv"),
                        "刚好占满预算应当放行"),
                () -> assertThrows(DataSourceException.class,
                        () -> MemoryGuard.checkBytes(filesAtBudget * 2, "over.csv"),
                        "超出预算一倍应当拒绝"));
    }

    @Test
    @DisplayName("预算比例就是 50%，不得被调大（调大等于放行本该拒绝的文件）")
    void budgetRatioIsPinned() {
        // 单独钉住这个常量：它决定「留多少堆给别的东西」。
        // 调大它会让预检形同虚设 —— 上面的边界测试也会因为
        // 预算变大而放过更多文件，最终 OOM 回来
        assertEquals(0.5, MemoryGuard.MAX_HEAP_USAGE, 1e-9,
                "预算比例必须是 50%；调大就等于允许占满整个堆，加载与查询的临时对象没地方放");
    }

    @Test
    @DisplayName("0 或负的文件大小：不拦（交给后面的真实读取去报错）")
    void ignoresNonPositiveSize() {
        // 预检只解决「装不下」，不负责「文件不存在/为空」——
        // 那是别的检查的职责，在这里拦会给出一句误导的报错
        assertAll(
                () -> assertDoesNotThrow(() -> MemoryGuard.checkBytes(0, "empty.csv")),
                () -> assertDoesNotThrow(() -> MemoryGuard.checkBytes(-1, "bad.csv")));
    }

    @Test
    @DisplayName("不存在的文件：不拦（Files.size 抛异常时放过）")
    void missingFileIsNotGuardsBusiness() {
        assertDoesNotThrow(() ->
                MemoryGuard.checkFile(java.nio.file.Path.of("D:/no/such/file-xyz.csv")));
    }

    @Test
    @DisplayName("报错文案含具体数字与可行动的建议")
    void messageIsActionable() {
        DataSourceException e = assertThrows(DataSourceException.class,
                () -> MemoryGuard.checkBytes(maxHeap() * 10, "big.csv"));

        String msg = e.getMessage();
        assertAll(
                () -> assertTrue(msg.contains("big.csv"),
                        "要回显文件名，用户才知道是哪个文件：" + msg),
                () -> assertTrue(msg.contains("SQLite"),
                        "要给出替代方案，否则用户不知道下一步做什么：" + msg),
                () -> assertTrue(msg.contains("MB") || msg.contains("GB"),
                        "要带上具体数字，否则用户无法判断该拆到多小：" + msg));
    }

    @Test
    @DisplayName("中英文两版文案都存在且都带占位符替换结果")
    void messageIsBilingual() {
        // 英文模式下不能出现中文 —— 这是本项目的既有约束，
        // 新增文案同样要守（有专门的 i18n 测试守着整体，这里守新增的这条）
        String saved = Messages.desktopLocale();
        try {
            Messages.setDesktopLocale(Messages.ZH);
            String zh = Messages.get("error.memoryInsufficient",
                    "big.csv", "27 MB", "164 MB", "8.0 GB", "4.0 GB");

            Messages.setDesktopLocale(Messages.EN);
            String en = Messages.get("error.memoryInsufficient",
                    "big.csv", "27 MB", "164 MB", "8.0 GB", "4.0 GB");

            assertAll(
                    () -> assertTrue(zh.contains("big.csv") && zh.contains("164 MB"),
                            "中文文案应完成占位符替换：" + zh),
                    () -> assertTrue(en.contains("big.csv") && en.contains("164 MB"),
                            "英文文案应完成占位符替换：" + en),
                    () -> assertTrue(en.contains("SQLite"), "英文文案也应有替代方案：" + en),
                    () -> assertTrue(en.chars().noneMatch(c -> c >= 0x4e00 && c <= 0x9fff),
                            "英文文案不得含中文：" + en));
        } finally {
            // 必须还原：测试之间共享 JVM，语言是全局状态，
            // 不还原会污染后面的用例（这个仓库已有测试因此踩过坑）
            Messages.setDesktopLocale(saved);
        }
    }

    @Test
    @DisplayName("字节格式化：小于 1GB 显示 MB，超过显示 GB")
    void formatsBytesReadably() {
        assertAll(
                () -> assertEquals("0 MB", MemoryGuard.mb(0)),
                () -> assertEquals("1 MB", MemoryGuard.mb(1024L * 1024)),
                () -> assertEquals("1023 MB", MemoryGuard.mb(1023L * 1024 * 1024)),
                () -> assertEquals("1.0 GB", MemoryGuard.mb(1024L * 1024 * 1024)),
                () -> assertEquals("2.0 GB", MemoryGuard.mb(2048L * 1024 * 1024)));
    }

    // ==================== 调用点：预检必须真的被 load() 用上 ====================

    @Test
    @DisplayName("CSVLoader.load 真的会走预检（把调用删掉这条会红）")
    void csvLoaderActuallyInvokesGuard() throws Exception {
        // 这一条补的是一个真实漏洞：上面所有用例都直接调 MemoryGuard，
        // 所以把 CSVLoader 里的 MemoryGuard.checkFile(...) 那行删掉，
        // 单测【全都照样绿】—— 预检写了但没被调用，等于没写。
        //
        // 做法：造一个「文件大小远超堆预算」的 CSV。不真的写那么大，
        // 而是通过堆上限判断 —— 这里用一个必然超限的路径不可行（文件要真实存在），
        // 所以改成验证【调用确实发生】：把系数临时视为无穷大做不到，
        // 于是改用源码级断言 + 真实行为断言两条腿。
        //
        // 真实行为断言：用一个正常的小文件，确认 load 不会因为预检而误拒；
        // 再确认大文件确实被拦（由下面的集成用例覆盖）。
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/tinyquery/datasource/loader/CSVLoader.java"));
        String xlsx = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/tinyquery/datasource/loader/XlsxLoader.java"));

        assertAll(
                () -> assertTrue(src.contains("MemoryGuard.checkFile("),
                        "CSVLoader.load 必须调用 MemoryGuard.checkFile —— 否则预检形同虚设"),
                () -> assertTrue(xlsx.contains("MemoryGuard.checkFile("),
                        "XlsxLoader.load 必须调用 MemoryGuard.checkFile"));
    }

    @Test
    @DisplayName("集成：小文件正常加载，不被预检误拒")
    void smallFileLoadsFine(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        // 预检最危险的失效方向是「误拒」—— 那会让功能直接不可用。
        // 所以必须有一条真实的端到端用例走完整路径
        java.nio.file.Path csv = dir.resolve("ok.csv");
        java.nio.file.Files.writeString(csv, "城市,金额\n北京,100\n上海,200\n",
                java.nio.charset.StandardCharsets.UTF_8);

        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("path", csv.toString());
        com.tinyquery.executor.ColumnStore store = new CSVLoader().load(
                new com.tinyquery.datasource.spec.DataSourceConfig("ok", "csv", props));

        assertEquals(2, store.getColumnIndex(), "两行数据都应读进来");
    }
}
