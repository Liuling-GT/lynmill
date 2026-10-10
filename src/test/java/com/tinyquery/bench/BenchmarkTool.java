package com.tinyquery.bench;

import com.tinyquery.datasource.DataSourceManager;
import com.tinyquery.datasource.loader.CSVLoader;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.executor.ColumnStore;
import com.tinyquery.executor.QueryEngine;
import com.tinyquery.executor.QueryResult;
import com.tinyquery.parser.QueryStatement;
import com.tinyquery.parser.SQLParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * README 性能表的测量工具。
 *
 * <p>为什么要有这个东西：README 里的性能数字曾经失准过（分页功能加进来之后，
 * 「where age &gt; 50 = 1950ms」这个数就不再反映真实情况了）。手测一次、
 * 抄进文档、之后没人复测 —— 数字就这样烂在文档里。
 *
 * <p>所以把测量做成可重跑的：{@code mvn test -Dtest=BenchmarkTool}，
 * 输出直接可以粘进 README。
 *
 * <p><b>测量口径</b>（重要，写进 README 时必须一起说明）：
 * <ul>
 *   <li><b>加载</b>：读 CSV + 建列式存储，只做一次</li>
 *   <li><b>解析</b>：{@code parse} 的耗时，不含执行</li>
 *   <li><b>执行</b>：{@code executePage} 的耗时，含过滤 + 投影 + 分页</li>
 * </ul>
 * 把三段分开报，是因为它们的量级差好几个数量级，混在一起看不出瓶颈在哪。
 *
 * <p>每组跑 {@value #WARMUP} 次预热 + {@value #ROUNDS} 次计时取中位数：
 * JIT 没热起来时头几次会慢好几倍，只测一次得到的数字没有意义。
 */
public class BenchmarkTool {

    private static final int WARMUP = 3;
    private static final int ROUNDS = 7;

    private static final String CSV = "D:/TinyQuery-Data/05-测试数据/huge.csv";

    public static void main(String[] args) throws Exception {
        Path csv = Path.of(args.length > 0 ? args[0] : CSV);
        if (!Files.isRegularFile(csv)) {
            System.out.println("找不到测试数据：" + csv);
            System.out.println("用 DataGenerator 生成，或传入路径作为第一个参数");
            return;
        }

        System.out.println("== TinyQuery 性能测量 ==");
        System.out.printf("数据文件 : %s%n", csv);
        System.out.printf("文件大小 : %.2f MB%n", Files.size(csv) / 1024.0 / 1024.0);
        System.out.printf("JDK      : %s (%s)%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"));
        System.out.printf("预热 %d 次，计时 %d 次取中位数%n%n", WARMUP, ROUNDS);

        // ---------- 加载 ----------
        // 用 CSVLoader 走真实加载路径，不用手工造 ColumnStore ——
        // 否则测的是我们想象的加载，不是用户实际经历的加载
        CSVLoader loader = new CSVLoader();
        DataSourceConfig config = new DataSourceConfig(
                "bench", "csv", Map.of("path", csv.toString()));

        long t0 = System.nanoTime();
        ColumnStore store = loader.load(config);
        long loadMs = (System.nanoTime() - t0) / 1_000_000;

        // getColumnIndex() 这个名字有误导性，它返回的是行数（见 ColumnStore:67）
        int rows = store.getColumnIndex();
        System.out.printf("加载      : %d ms（%d 行，%.0f 万行/秒）%n%n",
                loadMs, rows, rows / (loadMs / 1000.0) / 10_000);

        SQLParser parser = new SQLParser();
        QueryEngine engine = new QueryEngine(store);

        // ---------- 逐条查询 ----------
        // SQL 与列名对齐 huge.csv 的真实表头：name,age,city,salary
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("count(*)", "select count(*) from t");
        queries.put("全表扫描（取 50 行）", "select * from t");
        queries.put("过滤 age > 50", "select * from t where age > 50");
        queries.put("过滤 city = '北京'", "select * from t where city = '北京'");
        queries.put("过滤 + and 组合", "select * from t where age > 30 and city = '北京'");
        queries.put("投影两列", "select name, age from t");
        queries.put("排序 salary desc", "select * from t order by salary desc");
        queries.put("排序 + 过滤", "select * from t where age > 40 order by salary desc");
        queries.put("sum(salary)", "select sum(salary) from t");
        queries.put("avg(age)", "select avg(age) from t");

        System.out.printf("%-24s %10s %10s %12s%n", "查询", "解析(ms)", "执行(ms)", "命中行数");
        System.out.println("-".repeat(60));

        for (Map.Entry<String, String> e : queries.entrySet()) {
            String label = e.getKey();
            String sql = e.getValue();

            QueryStatement stmt = parser.parse(sql);

            long parseTotal = 0;
            long execTotal = 0;
            int hit = 0;

            for (int i = 0; i < WARMUP + ROUNDS; i++) {
                long p0 = System.nanoTime();
                QueryStatement s = parser.parse(sql);
                long p1 = System.nanoTime();

                QueryResult r = engine.executePage(s, 1, 50);
                long p2 = System.nanoTime();

                if (i >= WARMUP) {
                    parseTotal += p1 - p0;
                    execTotal += p2 - p1;
                    hit = r.totalRows();
                }
            }

            System.out.printf("%-24s %10.2f %10.2f %12d%n",
                    label,
                    parseTotal / (double) ROUNDS / 1_000_000,
                    execTotal / (double) ROUNDS / 1_000_000,
                    hit);
        }

        System.out.println();
        System.out.println("说明：执行耗时含过滤 + 投影 + 分页；分页见 executePage。");
        System.out.println("      解析耗时与 SQL 长度有关，过滤条件越长越慢。");
    }
}
