package com.tinyquery.audit;

import com.tinyquery.parser.SQLParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把 {@link ReadmeAudit} 接进 {@code mvn test}。
 *
 * <p>ReadmeAudit 是 {@code main} 程序，类名不匹配 surefire 的默认匹配式，
 * 此前从未在构建里跑过，只能靠人手工执行。断言仍只写在 ReadmeAudit 一处，
 * 本类只消费结果 —— 各写一份会新造一个漂移面。
 *
 * <p>类名以 {@code Test} 结尾即可被默认匹配式发现，不需要改 pom。
 */
@DisplayName("README 的两张表与实际行为一致（由 ReadmeAudit 驱动）")
class ReadmeAuditTest {

    @Test
    @DisplayName("逐条核对：没有任何一条「文档声称」与「实际行为」对不上")
    void readmeTablesMatchRealBehavior(@TempDir Path dir) throws Exception {
        List<ReadmeAudit.Check> checks = new ReadmeAudit(dir).run();

        List<ReadmeAudit.Check> failed = checks.stream()
                .filter(c -> c.verdict() == ReadmeAudit.Verdict.FAIL)
                .toList();

        // 一次列全，不要断言第一条就停：只报第一条的话，改文档的人要
        // 「修一条 → 跑一次」反复来回，也看不出总共差多少
        String report = failed.stream()
                .map(c -> "  · " + c.label() + "\n      SQL   : " + c.sql()
                        + "\n      实际  : " + c.detail())
                .collect(Collectors.joining("\n"));

        assertTrue(failed.isEmpty(),
                "README 与实际行为有 " + failed.size() + " 处不符"
                        + "（要么改 README，要么改代码 —— 见 NOTES 第四节的维护约定）：\n" + report);
    }

    /**
     * 防止这个测试失效。
     *
     * <p>若 ReadmeAudit 被改坏、或某段小节不再产生断言，上面那条测试会因为
     * 「没有 FAIL」而照常通过。所以这里钉住断言数量：条数掉下去就说明核对没跑全。
     */
    @Test
    @DisplayName("核对确实跑满：断言条数不得缩水（防「一条没跑也算通过」）")
    void auditActuallyRan(@TempDir Path dir) throws Exception {
        List<ReadmeAudit.Check> checks = new ReadmeAudit(dir).run();

        long asserted = checks.stream()
                .filter(c -> c.verdict() != ReadmeAudit.Verdict.INFO)
                .count();
        long infos = checks.stream()
                .filter(c -> c.verdict() == ReadmeAudit.Verdict.INFO)
                .count();

        // 当前实际：支持 17 + 不支持 14 + 专门文案 3 = 34 条断言，另有 7 条只打印
        assertTrue(asserted >= 34,
                "断言条数从 34 掉到了 " + asserted + " —— 核对没有跑全，"
                        + "上面的「一致」结论不可信");
        assertTrue(infos >= 7,
                "只打印的条目从 7 掉到了 " + infos + " —— 有人删了 show()，请确认是否有意为之");
        assertEquals(0, checks.stream().filter(c -> c.sql() == null || c.sql().isBlank()).count(),
                "每条核对都必须带 SQL，否则报告里看不出测的是什么");
    }

    /**
     * {@link ReadmeAudit#garbageTableName} 是这条链上的量具，它自己错了整套判据就跟着错，
     * 而且错得隐蔽：量具读错表名时，「报错里有没有垃圾表名」会得出相反的结论。
     *
     * <p>量具最初把 {@code group by} 列成了子句边界，于是推出表名是 {@code sales}，
     * 而真解析器给出的是 {@code sales group by city} —— 量具出错的理由恰恰就是
     * 它要测的那个缺陷。所以这里不写死期望值，而是逐条对着真解析器核对：
     * 期望值由 {@code SQLParser} 现场产出。
     */
    @Test
    @DisplayName("量具自检：垃圾表名的推导与真解析器逐条一致")
    void garbageTableNameMatchesRealParser() {
        SQLParser parser = new SQLParser();

        String[] sqls = {
                // 正常查询：子句关键字会被正确切开
                "select * from sales",
                "select * from sales where amount > 100",
                "select * from sales order by amount desc",
                "select * from sales order by city asc, amount desc",
                "select * from sales limit 2",
                "select * from sales limit 2 offset 1",
                "select * from sales offset 1",
                // 下面这些是「被整段吞进表名」的典型，量具最容易在这里想当然
                "select count(*) from sales group by city",
                "select * from sales join other on sales.id = other.id",
                "select * from (select * from sales) x",
                "select * from sales union select * from sales",
                "select * from `sales`",
                "select * from \"sales\"",
                "select * from [sales]",
                "select distinct city from sales",
        };

        int compared = 0;
        for (String sql : sqls) {
            String actual;
            try {
                actual = parser.parse(sql).tableName();
            } catch (RuntimeException e) {
                // 解析期就抛的（如 limit m, n）不在这个量具的适用范围内
                continue;
            }
            compared++;
            assertEquals(actual, ReadmeAudit.garbageTableName(sql),
                    "量具与真解析器对不上 —— 判据会得出相反结论，SQL：" + sql);
        }

        // 防止这条自检退化成空跑：若解析器改成全部在解析期拦下，
        // 循环里一条都不会被对照，测试却照样绿
        assertTrue(compared >= 10,
                "只对照了 " + compared + " 条 —— 大多数 SQL 都在解析期抛了，"
                        + "这条自检已经失去意义，请检查解析器是否变了行为");
    }

    @Test
    @DisplayName("量具自检：没有 from 时不硬编答案")
    void garbageTableNameWithoutFrom() {
        assertEquals("", ReadmeAudit.garbageTableName("select 1"));
    }
}
