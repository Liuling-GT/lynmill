package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.i18n.Messages;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 加载前的内存预检：估算这份数据要占多少堆，超了就提前拒绝。
 *
 * <p>{@code ColumnStore} 把整张表放进内存，没有上限、没有淘汰（见 NOTES.md）。
 * 实测规律是常驻堆约为文件大小的 5 倍（文本进内存后每格都是装箱对象）。</p>
 *
 * <p>没有预检时，加载一个过大的文件会得到 {@code OutOfMemoryError}：程序直接崩掉，
 * 界面断开，日志里只有一句堆栈，用户既不知道发生了什么也不知道该怎么办。
 * 有了预检，用户得到一句能读懂的话：「该文件约需 1.4 GB 内存，超过当前可用上限
 * （堆上限 8.4 GB 的一半）」，据此能决定换个小文件或改用 SQLite 数据源。</p>
 *
 * <p>必须提前拒绝而不是崩了再补救：OOM 之后堆里全是垃圾，进程状态已不可信，
 * 「捕获 OOM 再优雅退出」在实践中做不到可靠，唯一的办法是在分配之前就别开始。</p>
 *
 * <p>估算必然是保守的：</p>
 *
 * <ul>
 *   <li>系数取实测的上界而非平均值 —— 漏放要崩，误拒只是让用户换一种数据源</li>
 *   <li>只对事先知道大小的数据源（文件类）做预检。JDBC 的结果集多大要连上才知道，
 *       只能跳过 —— 这是已知缺口，不是遗漏，已记入 NOTES.md</li>
 *   <li>阈值留安全余量：不能等占满 100% 堆才拒绝，加载与查询本身还要临时对象</li>
 * </ul>
 */
public final class MemoryGuard {

    /**
     * 常驻堆 / 文件大小 的估算系数。
     *
     * <p>实测值（{@code MemoryFootprintTool}）：huge.csv 4.92×、orders.csv 5.22×、
     * social_media.csv 5.32×。取 6 作为上界 —— 比实测最高值再高一点，
     * 给列数更多的文件留余地（每多一列就多一个 ArrayList 的冗余容量）。</p>
     */
    static final double RESIDENT_RATIO = 6.0;

    /**
     * 允许占用的堆比例上限。
     *
     * <p>不能等占满才拒绝：加载过程中还有解码缓冲与切分出的中间字符串，
     * 加载完之后用户马上要查询，查询也要临时对象。
     * 留一半给这些，也给「再打开一个小数据源」留余地。</p>
     */
    static final double MAX_HEAP_USAGE = 0.5;

    private MemoryGuard() {
        // 工具类，不实例化
    }

    /**
     * 检查一份文件能否安全加载，不能则抛异常。
     *
     * @param file 数据文件
     * @throws DataSourceException 估算需求超过可用堆时（errorKey = error.memoryInsufficient）
     */
    public static void checkFile(Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (Exception e) {
            // 拿不到大小就不拦：这不是预检该管的事。
            // 真正的读取失败会在后面给出准确的错误，不必在这里臆测
            return;
        }
        checkBytes(size, file.getFileName().toString());
    }

    /**
     * 检查给定字节数的数据能否安全加载。
     *
     * <p>包可见，便于测试直接喂数值，不必造出真实的大文件。</p>
     */
    static void checkBytes(long fileBytes, String label) {
        if (fileBytes <= 0) {
            return;
        }

        long maxHeap = maxHeapBytes();
        if (maxHeap <= 0) {
            // 未设上限（-Xmx 没给）时 getMax() 可能返回 -1。这种情形下没有可靠的判据，
            // 放过 —— 宁可不管，也不要因为探测不到就拦住正常使用
            return;
        }

        long estimated = estimateResidentBytes(fileBytes);
        long budget = (long) (maxHeap * MAX_HEAP_USAGE);

        if (estimated > budget) {
            throw new DataSourceException("error.memoryInsufficient",
                    new Object[]{label, mb(fileBytes), mb(estimated), mb(maxHeap), mb(budget)},
                    Messages.get("error.memoryInsufficient",
                            label, mb(fileBytes), mb(estimated), mb(maxHeap), mb(budget)));
        }
    }

    /**
     * 由文件大小估算常驻堆用量。
     *
     * <p>包可见，测试直接断言这个换算 —— 它是整个预检的依据。</p>
     */
    static long estimateResidentBytes(long fileBytes) {
        return (long) (fileBytes * RESIDENT_RATIO);
    }

    /**
     * 当前 JVM 的最大堆。
     *
     * <p>{@code -1} 表示未设上限，调用方据此放过。</p>
     */
    static long maxHeapBytes() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        return bean.getHeapMemoryUsage().getMax();
    }

    /** 字节转成人类可读的 MB / GB。放进错误文案里，用户才看得懂 */
    static String mb(long bytes) {
        double m = bytes / 1024.0 / 1024.0;
        if (m >= 1024) {
            return String.format("%.1f GB", m / 1024.0);
        }
        return String.format("%.0f MB", m);
    }
}
