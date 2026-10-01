package com.tinyquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SingleInstanceGuard 单实例守卫")
class SingleInstanceGuardTest {

    private static final String URL = "http://localhost:8080";

    // 显式传入临时目录，绝不碰真实的用户数据目录
    private static Path lockFile(Path dir) {
        return dir.resolve("tinyquery.lock");
    }

    private static Path urlFile(Path dir) {
        return dir.resolve("tinyquery.url");
    }

    // ==================== 加锁 ====================

    @Test
    @DisplayName("首次启动能取得锁")
    void firstAcquireSucceeds(@TempDir Path dir) {
        try (SingleInstanceGuard guard = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
            assertTrue(guard.isAcquired());
        }
    }

    @Test
    @DisplayName("已被持锁时第二次获取失败 —— 同一 JVM 抛 OverlappingFileLockException 也算失败")
    void secondAcquireFails(@TempDir Path dir) {
        try (SingleInstanceGuard first = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
            assertTrue(first.isAcquired());

            try (SingleInstanceGuard second = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
                // FileLock 是进程级的：同一个 JVM 内重复加锁抛的是
                // OverlappingFileLockException 而不是返回 null，
                // 守卫必须把它当作「已被占用」，否则测试和生产会走上两条不同的路
                assertFalse(second.isAcquired());
            }
        }
    }

    @Test
    @DisplayName("第一个实例释放后，新实例能取得锁")
    void acquireSucceedsAfterRelease(@TempDir Path dir) {
        SingleInstanceGuard first = SingleInstanceGuard.tryAcquire(lockFile(dir));
        assertTrue(first.isAcquired());
        first.close();

        try (SingleInstanceGuard second = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
            assertTrue(second.isAcquired());
        }
    }

    // ==================== 地址传递 ====================

    @Test
    @DisplayName("持有锁时写入的地址，第二个实例能读出来")
    void publishThenRead(@TempDir Path dir) {
        try (SingleInstanceGuard holder = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
            holder.publishUrl(URL);

            try (SingleInstanceGuard other = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
                assertFalse(other.isAcquired());
                assertEquals(URL, other.readRunningUrl(2, 10));
            }
        }
    }

    @Test
    @DisplayName("锁文件里的陈旧地址会被新实例覆盖，不会被误读")
    void staleUrlIsOverwritten(@TempDir Path dir) throws Exception {
        // 模拟「上一个实例被强杀，地址文件残留」的场景
        Files.writeString(urlFile(dir), "http://localhost:9999", StandardCharsets.UTF_8);

        try (SingleInstanceGuard guard = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
            guard.publishUrl(URL);

            assertEquals(URL, Files.readString(urlFile(dir), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("未持有锁时写入地址是空操作 —— 只有第一个实例有资格写")
    void publishIsNoOpWhenNotAcquired(@TempDir Path dir) {
        try (SingleInstanceGuard first = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
            assertTrue(first.isAcquired());

            try (SingleInstanceGuard second = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
                second.publishUrl(URL);

                assertFalse(Files.exists(urlFile(dir)), "第二个实例不该写出地址文件");
            }
        }
    }

    @Test
    @DisplayName("空白地址不写入")
    void blankUrlNotPublished(@TempDir Path dir) {
        try (SingleInstanceGuard guard = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
            guard.publishUrl("   ");

            assertFalse(Files.exists(urlFile(dir)));
        }
    }

    @Test
    @DisplayName("地址文件不存在时重试后返回 null，不抛异常")
    void readReturnsNullWhenUrlFileMissing(@TempDir Path dir) {
        try (SingleInstanceGuard guard = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
            // 参数是 (重试次数, 间隔毫秒)，测试里缩短以免白等
            assertNull(guard.readRunningUrl(2, 10));
        }
    }

    @Test
    @DisplayName("地址文件只有空白内容时按「没有地址」处理")
    void readTreatsBlankContentAsAbsent(@TempDir Path dir) throws Exception {
        Files.writeString(urlFile(dir), "   \n", StandardCharsets.UTF_8);

        try (SingleInstanceGuard guard = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
            assertNull(guard.readRunningUrl(2, 10));
        }
    }

    // ==================== 核心契约 ====================

    @Test
    @DisplayName("地址文件与锁文件同目录，且是两个独立文件 —— 否则 Windows 上读不出来")
    void urlFileIsSiblingOfLockFile(@TempDir Path dir) {
        try (SingleInstanceGuard guard = SingleInstanceGuard.tryAcquire(lockFile(dir))) {
            guard.publishUrl(URL);

            // Windows 的文件锁是强制锁，tryLock() 锁的是整个锁文件。
            // 若地址与被锁区域同处一个文件，第二个实例根本读不出来
            assertTrue(Files.exists(dir.resolve("tinyquery.lock")));
            assertTrue(Files.exists(dir.resolve("tinyquery.url")));
            assertNotEquals(lockFile(dir), urlFile(dir));
        }
    }
}
