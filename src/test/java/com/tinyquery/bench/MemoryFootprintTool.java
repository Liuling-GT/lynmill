package com.tinyquery.bench;

import com.tinyquery.datasource.loader.CSVLoader;
import com.tinyquery.datasource.loader.XlsxLoader;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.executor.ColumnStore;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实测「加载一份数据占多少常驻内存」。
 *
 * <p>{@code DataSourceManager} 把整张表放进内存（{@code ColumnStore} 用
 * {@code Map<String, List<Object>>}），没有上限、没有淘汰。这是一条真实的产品
 * 约束：用户加载一个 500 MB 的 CSV 就会吃掉几百 MB 堆。但「会吃多少」从来没人
 * 量过 —— 凭感觉说「大概几倍文件大小」没有意义。</p>
 *
 * <p>测量方法用 retained heap 而不是文件大小反推，因为文件大小推不出内存占用。
 * 差在几处：CSV 是文本，进内存后数字可能变成 {@code Integer}/{@code Long}/
 * {@code Double} 对象；每列一个 {@code ArrayList}，元素是装箱对象
 * （{@code Object}），一个 {@code Integer} 至少 16 字节，而不是 4 字节；
 * 还有 {@code LinkedHashMap} / 列表扩容的冗余容量。</p>
 *
 * <p>所以直接测：加载前后各触发一次 GC，读 {@code MemoryMXBean} 的堆使用量，
 * 差值就是真实留在堆里的量。多次测量取最小值 —— GC 之后仍被引用的部分
 * 才是稳定值，偶尔的浮动噪声只会让数字偏大。</p>
 *
 * <p>口径：测的是加载完成的瞬间的常驻量，不含查询过程产生的临时对象。
 * 查询的临时开销与查询复杂度有关，是另一件事（见 README 性能表）。</p>
 */
public class MemoryFootprintTool {

    /** 重复次数。取最小值：GC 后的稳定值，浮动噪声只会让结果偏大 */
    private static final int ROUNDS = 5;

    private static final DecimalFormat MB = new DecimalFormat("0.0");
    private static final DecimalFormat RATIO = new DecimalFormat("0.00");

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(78));
        System.out.println("内存占用实测：加载一份数据后，堆里到底留下多少");
        System.out.println("=".repeat(78));
        System.out.println();
        System.out.println("测量口径：加载前/后各触发一次 GC，比较堆使用量。");
        System.out.println("取 " + ROUNDS + " 次的最小值（GC 后仍被引用的才是稳定值）。");
        System.out.println();
        System.out.printf("%-22s %10s %12s %12s %8s %10s%n",
                "数据", "文件", "行数", "常驻堆", "倍数", "每行");

        measureCsv("D:/TinyQuery-Data/05-测试数据/huge.csv");
        measureCsv("D:/TinyQuery-Data/05-测试数据/orders.csv");
        measureCsv("D:/TinyQuery-Data/05-测试数据/social_media.csv");

        System.out.println();
        reportHeapLimit();
        reportDataDir();
    }

    private static void measureCsv(String path) throws Exception {
        Path p = Path.of(path);
        if (!Files.exists(p)) {
            System.out.printf("%-22s %10s  （文件不存在，跳过）%n", p.getFileName());
            return;
        }

        long fileBytes = Files.size(p);
        long best = Long.MAX_VALUE;
        int rowCount = 0;

        for (int i = 0; i < ROUNDS; i++) {
            CSVLoader loader = new CSVLoader();
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("path", p.toString());
            DataSourceConfig config = new DataSourceConfig("mem", "csv", props);

            long before = usedHeapAfterGc(3);
            ColumnStore store = loader.load(config);
            long after = usedHeapAfterGc(3);

            // store 必须活着到测量之后，否则会被当成垃圾收掉，测出来是 0
            rowCount = store.getColumnIndex();
            best = Math.min(best, after - before);
        }

        double residentMb = best / 1024.0 / 1024.0;
        double fileMb = fileBytes / 1024.0 / 1024.0;
        System.out.printf("%-22s %9sMB %12d %11sMB %7sx %8sB%n",
                p.getFileName(), MB.format(fileMb), rowCount, MB.format(residentMb),
                RATIO.format(residentMb / fileMb),
                rowCount > 0 ? String.valueOf(best / rowCount) : "?");
    }

    /**
     * 触发 GC 后读堆使用量。
     *
     * <p>调用两次 {@code System.gc()} 并留一点间隔：一次往往回收不干净，
     * 尤其是刚分配完大对象的时候。</p>
     */
    private static long usedHeapAfterGc(int attempts) throws InterruptedException {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        long used = Long.MAX_VALUE;
        for (int i = 0; i < attempts; i++) {
            System.gc();
            Thread.sleep(60);
            MemoryUsage heap = bean.getHeapMemoryUsage();
            used = Math.min(used, heap.getUsed());
        }
        return used;
    }

    private static void reportHeapLimit() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        long max = bean.getHeapMemoryUsage().getMax();
        long committed = bean.getHeapMemoryUsage().getCommitted();

        System.out.println("=".repeat(78));
        System.out.println("本进程的堆上限");
        System.out.println("=".repeat(78));
        System.out.println("  max       = " + MB.format(max / 1024.0 / 1024.0) + " MB"
                + (max < 0 ? "（未设上限，由 JVM 按机器内存自动决定）" : ""));
        System.out.println("  committed = " + MB.format(committed / 1024.0 / 1024.0) + " MB");
        System.out.println();
        System.out.println("  注意：这是【测试进程】的堆。产品运行的堆上限取决于启动参数，");
        System.out.println("  目前 TinyQueryApplication 没有设置 -Xmx，用的是 JVM 默认值");
        System.out.println("  （通常为物理内存的 1/4）。");
    }

    private static void reportDataDir() {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("结论");
        System.out.println("=".repeat(78));
        System.out.println("  · 内存占用约为文件大小的【数倍】—— 文本转成对象后体积膨胀，");
        System.out.println("    且每列是装箱对象（Integer 至少 16 字节，不是 4 字节）。");
        System.out.println("  · 打开的数据源会【一直留在内存里】，没有上限也没有淘汰。");
        System.out.println("  · 因此同时打开多个大文件，或加载接近堆上限的单文件时会 OOM。");
        System.out.println();
        System.out.println("  这一条属于「已知限制」，已记入 NOTES.md。");
    }
}
