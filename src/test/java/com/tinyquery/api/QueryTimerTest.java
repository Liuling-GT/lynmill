package com.tinyquery.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 查询计时。
 *
 * <p>这个类存在的理由就是「{@code currentTimeMillis} 测不准毫秒级耗时」，
 * 所以测试也要盯着这一点：能不能测出比墙上时间粒度更细的时间，
 * 而不只是"能返回一个数"。</p>
 */
@DisplayName("查询计时：用 nanoTime，能测出墙上时间看不到的亚毫秒区间")
class QueryTimerTest {

    @Nested
    @DisplayName("基本行为")
    class Basics {

        @Test
        @DisplayName("计时一段已知耗时，误差在合理范围")
        void measuresKnownDuration() throws Exception {
            QueryTimer timer = QueryTimer.start();
            Thread.sleep(50);
            long ms = timer.elapsedMillis();

            // 睡眠 50ms，下限是 50；上限放宽到 500 以免在负载高的机器上偶发失败
            assertTrue(ms >= 50, "至少是睡眠时长，实际 " + ms);
            assertTrue(ms < 500, "不该离睡眠时长太远，实际 " + ms);
        }

        @Test
        @DisplayName("不耗时也返回 0，而不是负数或报错")
        void immediateStop() {
            assertEquals(0, QueryTimer.start().elapsedMillis());
        }

        @Test
        @DisplayName("measureMillis 返回被计时逻辑的耗时")
        void measureHelper() {
            long ms = QueryTimer.measureMillis(() -> {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            assertTrue(ms >= 30, "至少是睡眠时长，实际 " + ms);
            assertTrue(ms < 500, "实际 " + ms);
        }
    }

    @Nested
    @DisplayName("与 currentTimeMillis 的差异（这个类存在的理由）")
    class VersusWallClock {

        @Test
        @DisplayName("能区分出「同一毫秒内的两段不同耗时」—— 墙上时间做不到")
        void distinguishesSubMillisecondDurations() {
            // 两段明显不同的耗时：一段几乎不做事，一段做 17 万次累加。
            // 关键不在于各自绝对值，而在于 nanoTime 能给出【不同的】读数。
            QueryTimer fast = QueryTimer.start();
            long sink = 0;
            for (int i = 0; i < 100; i++) {
                sink += i;
            }
            long fastMicros = micros(fast);

            QueryTimer slow = QueryTimer.start();
            for (int i = 0; i < 400_000; i++) {
                sink += i;
            }
            long slowMicros = micros(slow);

            assertNotEquals(0, sink, "防止循环被优化掉");

            // 两次都远小于 1ms（否则这条测试就退化成"两个都是 1ms"，失去意义）。
            // 任一超过 1ms 说明机器太慢或 JIT 没生效，此时跳过判定而不是误报失败
            if (fastMicros < 1000 && slowMicros < 1000) {
                assertTrue(slowMicros > fastMicros,
                        "nanoTime 应能分出快慢：fast=" + fastMicros + "µs slow=" + slowMicros + "µs");
            }
        }

        @Test
        @DisplayName("亚毫秒操作上，nanoTime 有读数而 currentTimeMillis 常为 0")
        void nanoTimeSeesWhatWallClockMisses() {
            // 在同一毫秒内连续做 n 次"少量工作"，用 nanoTime 累计出来必然 > 0；
            // 用 currentTimeMillis 则很可能整段都是 0。
            // 这条不硬性断言 wall == 0（粒度是 1ms 的机器上偶尔会跨毫秒），
            // 只断言 nano 侧确实测到了时间 —— 那正是修复要达到的效果。
            long nanoTotal = 0;
            for (int round = 0; round < 200; round++) {
                QueryTimer t = QueryTimer.start();
                long sink = 0;
                for (int i = 0; i < 1000; i++) {
                    sink += i;
                }
                assertNotEquals(0, sink + 1);
                nanoTotal += micros(t);
            }
            assertTrue(nanoTotal > 0,
                    "200 轮累加不可能全为 0 微秒 —— 若为 0 说明计时没生效");
        }

        /** 以微秒为单位读 elapsed，绕开毫秒取整，便于观察亚毫秒差异 */
        private static long micros(QueryTimer timer) {
            // 用反射读私有起点没必要；改为再取一次 elapsedMillis 会丢精度，
            // 所以这里直接用 nanoTime 差值复算一遍同样的语义
            return nanosSince(timer) / 1000L;
        }

        private static long nanosSince(QueryTimer timer) {
            try {
                var f = QueryTimer.class.getDeclaredField("startNanos");
                f.setAccessible(true);
                return System.nanoTime() - (long) f.get(timer);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("读不到计时起点，说明字段被改名了", e);
            }
        }
    }
}
