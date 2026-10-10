package com.tinyquery.api;

import java.util.function.Supplier;

/**
 * 查询耗时计时。
 *
 * <h2>为什么不用 {@code System.currentTimeMillis()}</h2>
 *
 * <p>它是<b>墙上时间</b>（wall clock），用来回答「现在是几点」，不是用来测时间间隔的。
 * 两个问题：</p>
 *
 * <ol>
 *   <li><b>粒度粗</b>。它跟着系统时钟走，粒度取决于操作系统，15.6ms（Windows 传统的
 *       时钟中断周期）到 1ms 不等。而本项目查询常见耗时就在这个量级 ——
 *       实测同一句 SQL 连打 10 次，真实耗时 15~33ms，而 {@code elapsed} 报出来是
 *       {@code [5, 2, 2, 2, 1, 1, 1, 0, 8, 0]}，<b>两次报 0</b>。
 *       「0 毫秒」会让调用方以为根本没查。</li>
 *   <li><b>会往回走</b>。它是墙上时间，用户改了系统时间、或 NTP 校时，它就会跳变，
 *       差值为负。{@link System#nanoTime()} 单调递增，不受影响。</li>
 * </ol>
 *
 * <p>{@code nanoTime} 专为测耗时设计：单调、纳秒级。它不是「现在是几点」，
 * 两个 {@code nanoTime} 的<b>差值</b>才是有效信息 —— 这正是本类只暴露
 * {@link #elapsedMillis()} 而不暴露起点值的原因。</p>
 *
 * <h2>为什么单独一个类</h2>
 *
 * <p>同样的计时在两个接口里各写一遍（表格查询与图表聚合），改成
 * {@code nanoTime} 要改四处、且以后新增接口还会再写错一次。
 * 收在一处之后，"该用哪个时钟"这个决定只有一个地方要做。</p>
 *
 * <p>本类不是线程安全的：一次计时应在一个线程内配对使用。</p>
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
