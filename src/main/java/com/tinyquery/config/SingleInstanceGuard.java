package com.tinyquery.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单实例守卫：保证同一时间只有一个 TinyQuery 在跑。
 *
 * <p>用户双击第二次时不该起第二个进程 —— 那会变成两个服务、两个浏览器标签、
 * 两份互相看不见的内存数据。正确行为是打开已在运行那个实例的页面，然后自己退出。</p>
 *
 * <p>URL 没有写在被锁的那个文件里，而是单独一个 {@code tinyquery.url}。原因是
 * Windows 的文件锁是强制锁（{@code LockFileEx}），而 {@link FileChannel#tryLock()}
 * 不带参数时锁的是整个文件。若 URL 与被锁区域同处一个文件，第二个实例根本读不出来：
 * 它读的每个字节都在对方锁住的范围内。拆成「一个空文件用来加锁 + 一个明文文件用来
 * 传递地址」后，三个平台行为一致，也不需要任何字节范围技巧。</p>
 *
 * <pre>
 * main()
 *  ├─ tryAcquire() 成功 → 启动 Spring → 服务器真正监听后再 publishUrl()
 *  └─ tryAcquire() 失败 → readRunningUrl() → 打开浏览器 → 退出
 * </pre>
 *
 * <p>地址之所以等服务器起来后才写，是因为端口最终由 Web 服务器决定
 * （{@code server.port=0} 时是随机端口），只有那时拿到的才是权威值。</p>
 */
public final class SingleInstanceGuard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    // 运行地址文件名，与锁文件同目录
    private static final String URL_FILE_NAME = "tinyquery.url";

    // 读运行地址的重试参数。
    // 第一个实例从「拿到锁」到「写出地址」之间隔着整个 Spring 启动过程（约 1~2 秒），
    // 用户若在这期间又双击一次，第二个实例会先读到空文件：短暂重试而不是立刻放弃。
    // 30 × 100ms = 最多等 3 秒
    private static final int READ_ATTEMPTS = 30;
    private static final long READ_INTERVAL_MILLIS = 100;

    private final Path lockFile;
    private final Path urlFile;
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstanceGuard(Path lockFile, Path urlFile, FileChannel channel, FileLock lock) {
        this.lockFile = lockFile;
        this.urlFile = urlFile;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 按 {@link PathResolver#getLockFile()} 的位置尝试取得单实例锁。
     */
    public static SingleInstanceGuard tryAcquire() {
        return tryAcquire(PathResolver.getLockFile());
    }

    /**
     * 在指定位置尝试取得单实例锁。显式传入路径是为了让测试能用临时目录，不碰真实数据目录。
     *
     * @param lockFile 锁文件路径，不存在则创建
     * @return 守卫实例；{@link #isAcquired()} 为 false 表示已有实例在运行
     */
    public static SingleInstanceGuard tryAcquire(Path lockFile) {
        Path urlFile = lockFile.resolveSibling(URL_FILE_NAME);

        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // FileLock 是「进程级」而非线程级的：同一个 JVM 内对同一文件重复加锁
                // 抛的是这个异常，而不是让 tryLock 返回 null。视同「已被占用」，
                // 与跨进程的行为对齐 —— 测试也因此能在同一个 JVM 里验证这条分支
                lock = null;
            }
        } catch (IOException e) {
            // 打不开锁文件不该让程序起不来：降级为「本次不启用单实例保护」，
            // 最坏结果是可能起两个实例，总好过完全无法启动
            log.warn("无法打开单实例锁文件 {}，本次启动不启用单实例保护", lockFile, e);
            closeQuietly(channel);
            channel = null;
        }

        return new SingleInstanceGuard(lockFile, urlFile, channel, lock);
    }

    /**
     * 是否成功取得锁。false 表示已有实例在运行。
     */
    public boolean isAcquired() {
        return lock != null;
    }

    /**
     * 把本实例的访问地址写入文件，供后续启动的实例读取。
     *
     * <p>未持有锁时是空操作 —— 只有真正的第一个实例才有资格写。</p>
     */
    public void publishUrl(String url) {
        if (!isAcquired() || url == null || url.isBlank()) {
            return;
        }
        try {
            Files.writeString(urlFile, url, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 写不进去只影响「第二次双击能否自动打开页面」，不影响本实例运行
            log.warn("写入运行地址失败：{}", urlFile, e);
        }
    }

    /**
     * 读取已在运行实例的访问地址。
     *
     * <p>仅在 {@link #isAcquired()} 为 false 时有意义。读不到时按
     * {@link #READ_ATTEMPTS} × {@link #READ_INTERVAL_MILLIS} 重试，
     * 以覆盖「第一个实例还没写完地址」的窗口。</p>     *
     * @return 访问地址；始终读不到返回 null
     */
    public String readRunningUrl() {
        return readRunningUrl(READ_ATTEMPTS, READ_INTERVAL_MILLIS);
    }

    /**
     * 包可见，供测试缩短等待时间。
     */
    String readRunningUrl(int attempts, long intervalMillis) {
        for (int i = 0; i < attempts; i++) {
            String url = readUrlFile();
            if (url != null) {
                return url;
            }
            sleepQuietly(intervalMillis);
        }
        return null;
    }

    /**
     * 释放锁。
     *
     * <p>正常退出时其实不必显式调用：进程结束后操作系统会自动释放文件锁。
     * 保留此方法是给测试用的 —— 同一个 JVM 里要连续验证多个用例，必须能主动放手。</p>
     */
    @Override
    public void close() {
        closeQuietly(lock);
        closeQuietly(channel);
    }

    private String readUrlFile() {
        try {
            String content = Files.readString(urlFile, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? null : content;
        } catch (NoSuchFileException e) {
            // 第一个实例还没写，属于正常的时序窗口
            return null;
        } catch (IOException e) {
            log.debug("读取运行地址失败：{}", urlFile, e);
            return null;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 恢复中断标记，交由上层决定是否退出
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("关闭资源失败", e);
        }
    }

    @Override
    public String toString() {
        return "SingleInstanceGuard{lockFile=" + lockFile + ", acquired=" + isAcquired() + "}";
    }
}
