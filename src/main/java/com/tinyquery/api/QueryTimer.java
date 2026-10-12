package com.tinyquery.api;

import java.util.function.Supplier;

/**
 * 查询耗时计时。
 *
 * <p>用 {@link System#nanoTime()} 而不是 {@code System.currentTimeMillis()}：
 * 后者是墙上时间，用来回答「现在是几点」，不适合测时间间隔。
 * 它的粒度跟着系统时钟走（Windows 传统时钟中断周期 15.6ms），而本项目查询
 * 常见耗时就在这个量级 —— 实测同一句 SQL 连打 10 次，真实耗时 15~33ms，
 * 而 {@code currentTimeMillis} 报出来是 {@code [5, 2, 2, 2, 1, 1, 1, 0, 8, 0]}，
 * 两次报 0，会让调用方以为根本没查。另外它会往回走：用户改系统时间或 NTP 校时
 * 会导致差值为负，而 {@code nanoTime} 单调递增，不受影响。
 *
 * <p>两个 {@code nanoTime} 的差值才是有效信息，所以本类只暴露
 * {@link #elapsedMillis()} 而不暴露起点值。
 *
 * <p>收在一个类里而不是在两个接口（表格查询、图表聚合）各写一遍：
 * 新增接口时不必再决定一次该用哪个时钟。
 *
 * <p>本类不是线程安全的：一次计时应在一个线程内配对使用。
 */
final class QueryTimer {

    /** 起点。{@code nanoTime} 的值只在同一 JVM 内有意义，不对外暴露 */
    private final long startNanos;

    private QueryTimer(long startNanos) {
        this.startNanos = startNanos;
    }

    /** 开始计时 */
    static QueryTimer start() {
        return new QueryTimer(System.nanoTime());
    }

    /**
     * 从开始到现在的毫秒数，向下取整。
     *
     * <p>不足 1ms 时返回 0 —— 这是真实的取整，不是「没测到」。
     * 之所以不向上取整到 1，是因为那样会让 0.1ms 的查询看起来和 1.9ms 的一样慢，
     * 对排查性能反而有害。</p>
     */
    long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 计时执行一段逻辑并返回耗时（毫秒），供测试与内部复用。
     *
     * @param action 被测逻辑
     * @return 耗时毫秒数，向下取整
     */
    static long measureMillis(Supplier<?> action) {
        QueryTimer timer = start();
        action.get();
        return timer.elapsedMillis();
    }
}
