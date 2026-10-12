package com.tinyquery.audit;

import com.tinyquery.api.GlobalExceptionHandler;
import com.tinyquery.api.QueryController;
import com.tinyquery.config.SecurityToken;
import com.tinyquery.config.TokenFilter;
import com.tinyquery.datasource.DataSourceManager;
import com.tinyquery.datasource.loader.CSVLoader;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.credential.NoOpCredentialStore;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逐条核对 README「SQL 支持范围与限制」与「已知问题」两张表里的每一句承诺。
 *
 * <p>核对走真实 HTTP 入口（MockMvc 打到 {@code QueryController}），不直接调
 * {@code QueryEngine}。表名校验发生在控制器里的
 * {@code manager.getByTableName(stmt.tableName())}，而 {@code QueryEngine}
 * 拿到 ColumnStore 后不看表名 —— 绕开控制器直接调引擎，表名就不参与校验，
 * 「select * from t join other on ...」会静默返回全表，join、子查询、union、
 * 表名加引号全都会被误判成支持。所以这里测的是用户实际走到的那条路径。</p>
 *
 * <p>本类有两个调用方：{@link #main} 供人工排查时跑并把「声称」与「实际」
 * 并排打出来，{@link ReadmeAuditTest} 在 {@code mvn test} 里自动跑并断言没有 FAIL。
 * 两者共用同一份断言清单：{@link #run()} 返回结构化结果，各自消费。
 * 分开写两份清单会出现手工那份改了、自动那份没改的漂移。</p>
 *
 * <p>自动跑：{@code mvn test}（由 {@link ReadmeAuditTest} 驱动）。
 * 人工看详细输出：</p>
 * <pre>
 * mvn test-compile
 * java -cp "target/classes;target/test-classes;&lt;依赖&gt;" com.tinyquery.audit.ReadmeAudit
 * </pre>
 *
 * <p>（依赖用 {@code mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt} 拿到。）</p>
 *
 * <p>给 README 那张表加条目前，先在这里加一行，不要靠读代码判断支持不支持。
 * 这条工具三次出错都是工具错、README 对：第一次用错了层次（见上），
 * 第二次判断成功的依据错了，第三次（2026-10-11）是 {@code group by} 那条的夹具
 * 写成了 {@code select city, count(*) ... group by city} —— 这条会先撞上
 * 「普通列混聚合」规则并拿到那条规则的专门文案，于是显示 ✅，
 * 把「group by 其实没有专门文案」这个真相盖住了。夹具只能测它声称要测的那一件事：
 * 查 group by 就用不带普通列的纯 group by。这一点由
 * {@link #sectionDedicatedMessages()} 钉住。</p>
 */
public final class ReadmeAudit {

    /** 一条核对的结果。 */
    public enum Verdict {
        /** 断言通过 */
        PASS,
        /** 断言失败 —— 文档与实际不符，需要人工决定改哪边 */
        FAIL,
        /** 只打印不断言（这类条目本身不是硬承诺） */
        INFO
    }

    /**
     * @param section 所属小节标题，用于分组打印
     * @param label   条目名（对着 README 里的说法写）
     * @param sql     实际发出去的 SQL
     * @param verdict 判定
     * @param detail  给人看的说明：成功几行 / 报了什么错
     */
    public record Check(String section, String label, String sql,
                        Verdict verdict, String detail) {
    }

    // 小节标题。写成常量是因为 main 与测试都要用它们分组
    private static final String S_SUPPORT = "【支持】表中每条都应当成功";
    private static final String S_UNSUPPORTED = "【不支持（会报错）】表中每条都应当失败";
    private static final String S_MISLEADING = "【报错信息可能误导】一节：README 举的例子与对照";
    private static final String S_DEDICATED = "【专门文案】README 声称这几类有专门报错、不再掉进「数据源不存在」";
    private static final String S_KNOWN = "【已知问题】表逐条";

    private final MockMvc mvc;
    private final String token;
    private final List<Check> checks = new ArrayList<>();

    public ReadmeAudit(Path dir) throws Exception {
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

        this.mvc = MockMvcBuilders
                .standaloneSetup(new QueryController(manager))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TokenFilter())
                .build();
        this.token = SecurityToken.value();
    }

    /**
     * 跑完全部核对。
     *
     * <p>每次都从头跑，结果不跨次累积 —— 否则同一个实例跑两遍，
     * 第二遍的 FAIL 里会混进第一遍的，排查时看不出是哪一次的问题。</p>
     */
    public List<Check> run() {
        checks.clear();
        sectionSupport();
        sectionUnsupported();
        sectionMisleadingError();
        sectionDedicatedMessages();
        sectionKnownIssuesTable();
        return List.copyOf(checks);
    }

    // ==================== 「支持」表 ====================

    private void sectionSupport() {
        expectOk(S_SUPPORT, "select a, b", "select city, amount from sales");
        expectOk(S_SUPPORT, "select *", "select * from sales");
        expectOk(S_SUPPORT, "where", "select * from sales where amount > 100");
        expectOk(S_SUPPORT, "and / or / not", "select * from sales where amount > 100 and not city = '北京'");
        expectOk(S_SUPPORT, "= != <> > >= < <=", "select * from sales where amount >= 100");
        expectOk(S_SUPPORT, "条件外层括号", "select * from sales where (amount > 100 or amount < 50) and city = '北京'");
        expectOk(S_SUPPORT, "order by 单列", "select * from sales order by amount desc");
        expectOk(S_SUPPORT, "order by 多列（README 举例）", "select * from sales order by city asc, amount desc");
        expectOk(S_SUPPORT, "limit n", "select * from sales limit 2");
        expectOk(S_SUPPORT, "limit n offset m", "select * from sales limit 2 offset 1");
        expectOk(S_SUPPORT, "offset m", "select * from sales offset 1");
        expectOk(S_SUPPORT, "count(*)", "select count(*) from sales");
        expectOk(S_SUPPORT, "count(列)", "select count(city) from sales");
        expectOk(S_SUPPORT, "sum", "select sum(amount) from sales");
        expectOk(S_SUPPORT, "avg", "select avg(amount) from sales");
        expectOk(S_SUPPORT, "min", "select min(amount) from sales");
        expectOk(S_SUPPORT, "max", "select max(amount) from sales");
    }

    // ==================== 「不支持」表 ====================

    private void sectionUnsupported() {
        // 2026-10 复核发现这条原来被错误地放在「支持」表里，现在放到这里并断言
        // 它确实失败 —— 将来若实现了别名，此处会红
        expectFail(S_UNSUPPORTED, "select a as 别名", "select city as 城市 from sales");
        // 夹具原来是 select city, count(*) ... group by city，那条会先撞上
        // 「普通列混聚合」规则 —— 测的是那条规则，不是 group by。
        // 纯 group by（下面这条）才是真正要测的东西。
        expectFail(S_UNSUPPORTED, "group by", "select count(*) from sales group by city");
        expectFail(S_UNSUPPORTED, "in (...)", "select * from sales where city in ('北京', '上海')");
        expectFail(S_UNSUPPORTED, "between", "select * from sales where amount between 1 and 2");
        expectFail(S_UNSUPPORTED, "is null", "select * from sales where city is null");
        expectFail(S_UNSUPPORTED, "like", "select * from sales where city like '北%'");
        expectFail(S_UNSUPPORTED, "limit m, n", "select * from sales limit 1, 2");
        expectFail(S_UNSUPPORTED, "select distinct", "select distinct city from sales");
        expectFail(S_UNSUPPORTED, "join", "select * from sales join other on sales.id = other.id");
        expectFail(S_UNSUPPORTED, "子查询", "select * from (select * from sales) x");
        expectFail(S_UNSUPPORTED, "union", "select * from sales union select * from sales");
        expectFail(S_UNSUPPORTED, "表名反引号 `t`", "select * from `sales`");
        expectFail(S_UNSUPPORTED, "表名双引号 \"t\"", "select * from \"sales\"");
        expectFail(S_UNSUPPORTED, "表名方括号 [t]", "select * from [sales]");
    }

    // ==================== 「报错信息可能误导」一节 ====================

    private void sectionMisleadingError() {
        show(S_MISLEADING, "README 举的例子（join）", "select * from sample join other on sample.id = other.id");
        show(S_MISLEADING, "group by", "select count(*) from sales group by city");
        show(S_MISLEADING, "like", "select * from sales where city like '北%'");
        show(S_MISLEADING, "表名加引号（见【不支持】表）", "select * from `sales`");
    }

    /**
     * 断言 README 第 239 行那句承诺：{@code limit m, n} 与「普通列混聚合」
     * 有专门报错文案、不再掉进「数据源不存在」，其余仍受影响。
     * 这条以前没有测试覆盖，声称是假的也没人发现 —— 实际上 {@code group by}
     * 就一直是假的（2026-10-11 实测更正：它没有专门文案）。
     *
     * <p>判据刻意不依赖语言：不用「文案里有没有『数据源不存在』」这种断言，
     * 而是看报错里有没有出现那个垃圾表名。掉进 from 边界问题时，报错必然包含
     * 被整段当成表名的原文；有专门文案时则不会。中英文界面下这个判据都成立，
     * 也不会被测试间的语言状态污染影响。</p>
     */
    private void sectionDedicatedMessages() {
        // 声称「有专门文案」的两类：报错里不该出现垃圾表名
        expectDedicated(S_DEDICATED, "limit m, n 有专门文案", "select * from sales limit 1, 2");
        expectDedicated(S_DEDICATED, "普通列混聚合有专门文案", "select city, count(*) from sales");

        // group by 现在如实归入「仍受影响」：报错里应当出现垃圾表名
        expectFallsThrough(S_DEDICATED, "group by 仍掉进「数据源不存在」", "select count(*) from sales group by city");
    }

    // ==================== 「已知问题」表 ====================

    private void sectionKnownIssuesTable() {
        show(S_KNOWN, "不支持的语法报「数据源不存在」", "select * from sales join other on sales.id = other.id");
        show(S_KNOWN, "SQL 支持范围见上方（多列排序）", "select * from sales order by city asc, amount desc");
        show(S_KNOWN, "limit/offset 是否生效", "select * from sales limit 3 offset 2");
    }

    // ==================== 执行与判定 ====================

    private void expectOk(String section, String label, String sql) {
        R r = runSql(sql);
        if (r.ok()) {
            add(section, label, sql, Verdict.PASS, "成功（" + r.rows() + " 行）");
        } else {
            add(section, label, sql, Verdict.FAIL, "竟然失败：" + r.msg());
        }
    }

    private void expectFail(String section, String label, String sql) {
        R r = runSql(sql);
        if (!r.ok()) {
            add(section, label, sql, Verdict.PASS, "失败：" + r.msg());
        } else {
            add(section, label, sql, Verdict.FAIL,
                    "竟然成功（" + r.rows() + " 行），README 说不支持");
        }
    }

    /** 断言这条 SQL 被专门拦下：报错里不该出现「被整段当成表名」的痕迹。 */
    private void expectDedicated(String section, String label, String sql) {
        R r = runSql(sql);
        if (r.ok()) {
            add(section, label, sql, Verdict.FAIL, "竟然成功（" + r.rows() + " 行），应当报错");
            return;
        }
        String garbage = garbageTableName(sql);
        if (r.msg().contains(garbage)) {
            add(section, label, sql, Verdict.FAIL,
                    "README 声称有专门文案，但报错里出现了垃圾表名「" + garbage + "」：" + r.msg());
        } else {
            add(section, label, sql, Verdict.PASS, "有专门文案：" + r.msg());
        }
    }

    /** 断言这条 SQL 仍掉进 from 边界问题：报错里应当出现那个垃圾表名。 */
    private void expectFallsThrough(String section, String label, String sql) {
        R r = runSql(sql);
        if (r.ok()) {
            add(section, label, sql, Verdict.FAIL, "竟然成功（" + r.rows() + " 行），应当报错");
            return;
        }
        String garbage = garbageTableName(sql);
        if (r.msg().contains(garbage)) {
            add(section, label, sql, Verdict.PASS, "掉进「数据源不存在」，表名被读成：" + garbage);
        } else {
            add(section, label, sql, Verdict.FAIL,
                    "README 说它仍受影响，但报错里没有垃圾表名，像是已经有专门文案了 —— "
                            + "若确实补上了专门文案，请同步改 README 第 239 行与 NOTES 1.1 / 2.4：" + r.msg());
        }
    }

    private void show(String section, String label, String sql) {
        R r = runSql(sql);
        add(section, label, sql, Verdict.INFO,
                r.ok() ? ("成功，" + r.rows() + " 行") : r.msg());
    }

    private void add(String section, String label, String sql, Verdict v, String detail) {
        checks.add(new Check(section, label, sql, v, detail));
    }

    /**
     * 从 SQL 里推出发动机「会读成什么表名」—— 即 {@code from} 之后、被下一个
     * 真正被解析器识别的子句关键字切开之前的那一段。
     *
     * <p>只按关键字切分，不试图复用产品里的解析器。复用解析器就等于拿被测对象
     * 当裁判 —— 解析器读错表名时，这个判据会跟着一起错，于是恰好测不出要找的东西。</p>
     *
     * <p>关键字清单不能凭印象列（2026-10-11 踩到）：这里最初把 {@code group by}
     * 也列成了边界，于是推出表名是 {@code sales}，而真相是解析器根本不认
     * {@code group by}，表名是 {@code sales group by city}。判据的模型错了就会得出
     * 相反的结论（把「掉进数据源不存在」判成「有专门文案」），而它错的理由
     * 恰恰就是它要测的那个缺陷。所以清单只列解析器真的会切开的关键字：
     * {@code where} / {@code order by} / {@code limit} / {@code offset}。
     * {@code group by} 与 {@code join} 都不在其中 —— 它们会被整段吞进表名，
     * 这正是 2.2 / 2.4 描述的现象。这份清单由
     * {@link ReadmeAuditTest#garbageTableNameMatchesRealParser()}
     * 逐条对着真解析器核对，不靠记忆维护。</p>
     */
    static String garbageTableName(String sql) {
        int from = sql.toLowerCase().indexOf(" from ");
        if (from < 0) {
            return "";
        }
        String rest = sql.substring(from + 6).trim();
        // 只列解析器真的会当作子句边界的关键字。顺序无关，取最靠前的那一个
        int cut = rest.length();
        for (String kw : new String[]{" where ", " order by ", " limit ", " offset "}) {
            int i = rest.toLowerCase().indexOf(kw);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        return rest.substring(0, cut).trim();
    }

    /** 一次查询的结果。走真实 HTTP，所以能拿到控制器产出的报错文案 */
    private record R(boolean ok, int rows, String msg) {
    }

    private R runSql(String sql) {
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

            // 用 code==0 判断成功，不要用 HTTP 200 ——
            // 这个 API 的错误响应也是 HTTP 200，失败体现在 body 的 code 上，
            // 用 status 判断会把所有成功都读成失败。
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
     * <p>响应里 {@code data} 是二维数组（每行是元素数组），不是对象数组：
     * {@code "data":[["北京","10"],["上海","20"]]}。所以数的是 depth==2 处的
     * {@code [} 个数，不是 {@code &#123;} 个数 —— 数错会让所有行数都显示 0。</p>
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

    /**
     * 构造指向指定文件的 {@link com.tinyquery.datasource.DataSourceStore}。
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

    // ==================== 人工排查入口 ====================

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("readme-audit");
        List<Check> checks = new ReadmeAudit(dir).run();

        System.out.println("=".repeat(80));
        System.out.println("README 行为核对 —— 走真实 HTTP 入口");
        System.out.println("样本表名：sales（city / amount 两列，30 行）");
        System.out.println("=".repeat(80));

        String current = null;
        for (Check c : checks) {
            if (!c.section().equals(current)) {
                current = c.section();
                System.out.println();
                System.out.println("--- " + current + " ---");
            }
            System.out.printf("  %s %-36s → %s%n", icon(c.verdict()), c.label(), c.detail());
        }

        long pass = checks.stream().filter(c -> c.verdict() == Verdict.PASS).count();
        long fail = checks.stream().filter(c -> c.verdict() == Verdict.FAIL).count();

        System.out.println();
        System.out.println("=".repeat(80));
        System.out.printf("核对结束：一致 %d 处，不一致 %d 处%n", pass, fail);
        if (fail > 0) {
            System.out.println();
            System.out.println("对不上的条目（需要人工决定：改 README 还是改代码）：");
            checks.stream()
                    .filter(c -> c.verdict() == Verdict.FAIL)
                    .forEach(c -> System.out.println("  · " + c.label() + "：" + c.detail()));
        }
        System.out.println("=".repeat(80));
    }

    private static String icon(Verdict v) {
        return switch (v) {
            case PASS -> "✅";
            case FAIL -> "❌";
            case INFO -> "·";
        };
    }
}
