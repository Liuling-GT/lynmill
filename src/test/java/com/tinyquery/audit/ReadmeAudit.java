package com.tinyquery.audit;

import com.tinyquery.api.GlobalExceptionHandler;
import com.tinyquery.api.QueryController;
import com.tinyquery.config.SecurityToken;
import com.tinyquery.config.TokenFilter;
import com.tinyquery.datasource.DataSourceManager;
import com.tinyquery.datasource.loader.CSVLoader;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.credential.NoOpCredentialStore;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逐条核对 README「SQL 支持范围与限制」与「已知问题」两张表里的每一句承诺。
 *
 * <h2>为什么必须走 HTTP API，而不能直接调 QueryEngine</h2>
 *
 * <p>第一版这个工具直接 {@code new QueryEngine(store)} 跑，结果报了 6 个
 * <b>假警报</b>：join、子查询、union、表名加引号全都"成功"了。</p>
 *
 * <p>原因：表名校验发生在 {@code QueryController} 里的
 * {@code manager.getByTableName(stmt.tableName())}，而 {@code QueryEngine}
 * 拿到 ColumnStore 后<b>根本不看表名</b>。绕开控制器，表名就成了摆设 ——
 * 于是「select * from t join other on ...」静默返回全表。</p>
 *
 * <p><b>这正是本条待办要防的事</b>：审计工具自己在错误的层次上测量，
 * 得出「README 全错」的结论，而其实 README 是对的、工具是错的。
 * 所以改成走真实入口（HTTP），用户看到什么就测什么。</p>
 *
 * <h2>怎么跑</h2>
 *
 * <pre>
 * mvn test-compile
 * java -cp "target/classes;target/test-classes;&lt;依赖&gt;" com.tinyquery.audit.ReadmeAudit
 * </pre>
 *
 * <p>（依赖用 {@code mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt} 拿到。）</p>
 *
 * <h2>沿用它的经验</h2>
 *
 * <p>以后给 README 那张表加条目前，先在这里加一行 —— 它会把「声称」与
 * 「实际」并排打出来。<b>不要靠读代码判断支持不支持</b>：这个工具第一次跑时
 * 用错了层次，第二次跑时判断成功的依据也错了（见下），两次都是工具错、README 对。</p>
 */
public final class ReadmeAudit {

    private static MockMvc mvc;
    private static String token;
    private static int pass;
    private static int mismatch;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("readme-audit");
        buildMvc(dir);

        System.out.println("=".repeat(80));
        System.out.println("README 行为核对 —— 走真实 HTTP 入口");
        System.out.println("样本表名：sales（city / amount 两列，30 行）");
        System.out.println("=".repeat(80));

        sectionSupport();
        sectionUnsupported();
        sectionMisleadingError();
        sectionKnownIssuesTable();

        System.out.println();
        System.out.println("=".repeat(80));
        System.out.printf("核对结束：一致 %d 处，不一致 %d 处%n", pass, mismatch);
        if (mismatch > 0) {
            System.out.println();
            System.out.println("对不上的条目（需要人工决定：改 README 还是改代码）：");
            for (String m : findings) {
                System.out.println("  · " + m);
            }
        }
        System.out.println("=".repeat(80));
    }

    /** 记录对不上的条目，最后汇总 —— 散在各处会看漏 */
    private static final java.util.List<String> findings = new java.util.ArrayList<>();

    // ==================== 「支持」表 ====================

    private static void sectionSupport() {
        p("【支持】表中每条都应当成功");
        expectOk("select a, b", "select city, amount from sales");
        expectOk("select *", "select * from sales");
        expectOk("where", "select * from sales where amount > 100");
        expectOk("and / or / not", "select * from sales where amount > 100 and not city = '北京'");
        expectOk("= != <> > >= < <=", "select * from sales where amount >= 100");
        expectOk("条件外层括号", "select * from sales where (amount > 100 or amount < 50) and city = '北京'");
        expectOk("order by 单列", "select * from sales order by amount desc");
        expectOk("order by 多列（README 举例）", "select * from sales order by city asc, amount desc");
        expectOk("limit n", "select * from sales limit 2");
        expectOk("limit n offset m", "select * from sales limit 2 offset 1");
        expectOk("offset m", "select * from sales offset 1");
        expectOk("count(*)", "select count(*) from sales");
        expectOk("count(列)", "select count(city) from sales");
        expectOk("sum", "select sum(amount) from sales");
        expectOk("avg", "select avg(amount) from sales");
        expectOk("min", "select min(amount) from sales");
        expectOk("max", "select max(amount) from sales");
    }

    // ==================== 「不支持」表 ====================

    private static void sectionUnsupported() {
        p("【不支持（会报错）】表中每条都应当失败");
        // 2026-10 复核发现：这条原来被错误地放在【支持】表里。
        // 现在放到这里，并断言它确实失败 —— 这样将来若实现了别名，此处会红
        expectFail("select a as 别名", "select city as 城市 from sales");
        expectFail("group by", "select city, count(*) from sales group by city");
        expectFail("in (...)", "select * from sales where city in ('北京', '上海')");
        expectFail("between", "select * from sales where amount between 1 and 2");
        expectFail("is null", "select * from sales where city is null");
        expectFail("like", "select * from sales where city like '北%'");
        expectFail("limit m, n", "select * from sales limit 1, 2");
        expectFail("select distinct", "select distinct city from sales");
        expectFail("join", "select * from sales join other on sales.id = other.id");
        expectFail("子查询", "select * from (select * from sales) x");
        expectFail("union", "select * from sales union select * from sales");
        expectFail("表名反引号 `t`", "select * from `sales`");
        expectFail("表名双引号 \"t\"", "select * from \"sales\"");
        expectFail("表名方括号 [t]", "select * from [sales]");
    }

    // ==================== 「报错信息可能误导」一节 ====================

    private static void sectionMisleadingError() {
        p("【报错信息可能误导】一节：README 说这几类「有专门文案」，其余仍报「数据源不存在」");
        show("README 举的例子（join）", "select * from sample join other on sample.id = other.id");
        show("group by（声称有专门文案）", "select city, count(*) from sales group by city");
        show("limit m, n（声称有专门文案）", "select * from sales limit 1, 2");
        show("普通列混聚合（声称有专门文案）", "select city, count(*) from sales");
        show("like（声称仍受影响）", "select * from sales where city like '北%'");
        show("表名加引号（见【不支持】表）", "select * from `sales`");
    }

    // ==================== 「已知问题」表 ====================

    private static void sectionKnownIssuesTable() {
        p("【已知问题】表逐条");
        show("不支持的语法报「数据源不存在」", "select * from sales join other on sales.id = other.id");
        show("SQL 支持范围见上方（多列排序）", "select * from sales order by city asc, amount desc");
        show("limit/offset 是否生效", "select * from sales limit 3 offset 2");
    }

    // ==================== 执行与判定 ====================

    private static void expectOk(String claim, String sql) {
        R r = run(sql);
        if (r.ok) {
            pass++;
            System.out.printf("  ✅ %-36s → 成功（%d 行）%n", claim, r.rows);
        } else {
            mismatch++;
            System.out.printf("  ❌ %-36s → 竟然失败：%s%n", claim, r.msg);
            findings.add("README【支持】表列出「" + claim + "」，但实际报错：" + r.msg);
        }
    }

    private static void expectFail(String claim, String sql) {
        R r = run(sql);
        if (!r.ok) {
            pass++;
            System.out.printf("  ✅ %-36s → 失败：%s%n", claim, r.msg);
        } else {
            mismatch++;
            System.out.printf("  ⚠️  %-36s → 竟然成功（%d 行），README 说不支持%n", claim, r.rows);
            findings.add("README【不支持】表列出「" + claim + "」，但实际成功返回 "
                    + r.rows + " 行");
        }
    }

    private static void show(String label, String sql) {
        R r = run(sql);
        System.out.printf("  · %-32s → %s%n", label,
                r.ok ? ("成功，" + r.rows + " 行") : r.msg);
    }

    /** 一次查询的结果。走真实 HTTP，所以能拿到控制器产出的报错文案 */
    private record R(boolean ok, int rows, String msg) {
    }

    private static R run(String sql) {
        try {
            String body = "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            MvcResult res = mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post("/api/query")
                                    .header(SecurityToken.HEADER_NAME, token)
                                    .header("Host", "localhost:8080")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andReturn();

            String text = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
            int status = res.getResponse().getStatus();

            // 【踩过的坑】用 code==0 判断成功，不要用 HTTP 200 ——
            // 这个 API 的【错误】响应也是 HTTP 200，失败体现在 body 的 code 上。
            // 用 status 判断会把所有成功都读成失败（第一版就踩了这个）。
            // 字段名也注意：成功时数据在 "data"，不是 "rows"。
            if (status == 200 && text.contains("\"code\":0")) {
                return new R(true, countRows(text), null);
            }
            return new R(false, 0, extractMessage(text));
        } catch (Exception e) {
            return new R(false, 0, "调用异常：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 数 data 数组里的行数。
     *
     * <p>响应里 {@code data} 是<b>二维数组</b>（每行是元素数组），不是对象数组：
     * {@code "data":[["北京","10"],["上海","20"]]}。所以数的是 depth==2 处的
     * {@code [} 个数，不是 {@code &#123;} 个数 —— 第一版数错了，所有行数都显示 0。</p>
     */
    private static int countRows(String json) {
        int i = json.indexOf("\"data\"");
        if (i < 0) {
            return -1;
        }
        int start = json.indexOf('[', i);
        if (start < 0) {
            return -1;
        }
        int probe = start + 1;
        while (probe < json.length() && Character.isWhitespace(json.charAt(probe))) {
            probe++;
        }
        if (probe < json.length() && json.charAt(probe) == ']') {
            return 0;
        }
        int depth = 0;
        int count = 0;
        boolean inStr = false;
        for (int k = start; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '"' && (k == 0 || json.charAt(k - 1) != '\\')) {
                inStr = !inStr;
            }
            if (inStr) {
                continue;
            }
            if (c == '[') {
                depth++;
                if (depth == 2) {
                    count++;
                }
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        return count;
    }

    private static String extractMessage(String json) {
        int i = json.indexOf("\"message\"");
        if (i < 0) {
            return json.length() > 110 ? json.substring(0, 110) + "…" : json;
        }
        int q1 = json.indexOf('"', json.indexOf(':', i));
        if (q1 < 0) {
            return json;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = q1 + 1; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '\\' && k + 1 < json.length()) {
                char n = json.charAt(++k);
                sb.append(n == 'n' ? ' ' : n);
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        String m = sb.toString().replace("\n", " ");
        return m.length() > 104 ? m.substring(0, 104) + "…" : m;
    }

    private static void p(String title) {
        System.out.println();
        System.out.println("--- " + title + " ---");
    }

    private static void buildMvc(Path dir) throws Exception {
        DataSourceManager manager = new DataSourceManager(
                List.of(new CSVLoader()),
                storeAt(dir.resolve("datasources.json")),
                new NoOpCredentialStore("审计中不存密码"));

        Path csv = dir.resolve("sales.csv");
        StringBuilder sb = new StringBuilder("city,amount\n");
        String[] cities = {"北京", "上海", "广州"};
        for (int i = 0; i < 30; i++) {
            sb.append(cities[i % 3]).append(',').append((i + 1) * 10).append('\n');
        }
        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", csv.toString());
        manager.register(new DataSourceConfig("sales", "csv", props));

        mvc = MockMvcBuilders
                .standaloneSetup(new QueryController(manager))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TokenFilter())
                .build();
        token = SecurityToken.value();
    }

    /**
     * 构造指向指定文件的 {@link DataSourceStore}。
     *
     * <p>它是包级可见的，审计类不在同一个包里，所以只能反射 ——
     * 与 {@code QueryApiTest.storeAt} 同样的做法。</p>
     */
    private static com.tinyquery.datasource.DataSourceStore storeAt(Path file) {
        try {
            var c = com.tinyquery.datasource.DataSourceStore.class
                    .getDeclaredConstructor(Path.class);
            c.setAccessible(true);
            return c.newInstance(file);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法构造指向临时目录的 DataSourceStore", e);
        }
    }
}
