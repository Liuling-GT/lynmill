package com.tinyquery.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把 NOTES.md 里「已知限制 / 已知缺陷」的断言钉住。
 *
 * <p>为什么要单独立一个类：文档会漂移，而且漂移了没人知道 —— 没有测试会因此变红。
 * 这个仓库就吃过一次亏：README 长期把 {@code in} / {@code between} / {@code is null}
 * 写成"支持"（其实从未实现），又把已经实现的「条件外层括号」写成"不支持"。
 * 两个方向的错都有，且存在了不止一个版本。
 *
 * <p>所以这里把文档里每一句"我们认为会怎样"变成可执行的断言。改动导致行为变化时，
 * 这里会红，于是修改的人必须同时更新 NOTES.md —— 要么改结论，要么改文档。
 *
 * <p>维护约定：如果某条限制被实现了，不要删掉这里的测试，
 * 而是把它从"会抛异常"改成"能正常解析"，并同步删掉 NOTES.md 的条目。
 * 反过来，如果新增了限制，先在这里加断言，再去写文档。
 */
class DocumentedLimitationsTest {

    private final SQLParser parser = new SQLParser();

    // ==================== 一、已知限制（有意为之） ====================

    @Test
    @DisplayName("限制 1.1：group by 不支持，且它并不是被专门拦下的")
    void groupByNotSupported() {
        // 【夹具修正 2026-10-11】这条原来用的是
        //     assertThrows(RuntimeException.class,
        //             () -> parser.parse("select 城市, count(*) from t group by 城市"));
        // 注释写着「解析环节就得拦下来」。但那条 SQL 之所以抛，是因为
        // 「普通列与聚合混写」规则 —— 跟 group by 无关：把 group by 去掉，
        // 报错一字不差。所以这个夹具测的是混写规则，group by 一次都没被测到。
        //
        // 换成纯 group by 之后才看清真相：它在解析期根本不被拦，
        // 而是被整段当成表名，直到执行层才报「数据源不存在」。
        // （README 与 NOTES 2.4 当时都错说成"有专门文案"，已同步更正。）
        var stmt = parser.parse("select count(*) from t group by 城市");

        assertEquals("t group by 城市", stmt.tableName(),
                "group by 被整段当成了表名 —— 它没有专门处理，也没有专门报错");

        // 对照：混写才是真正被解析期专门拦下的那一条。
        // 保留它，是为了让上面那句"与 group by 无关"可被验证 ——
        // 同一句话去掉 group by 后照样抛，说明拦的是混写。
        assertThrows(RuntimeException.class,
                () -> parser.parse("select 城市, count(*) from t"),
                "普通列与聚合混写应当在解析期被拦下");
    }

    @Test
    @DisplayName("限制 1.2：只支持 7 个比较运算符，in/between/is null/like 都没有")
    void onlySevenComparisonOperators() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> parser.parse("select * from t where c in ('a','b')"),
                        "in 应为不支持"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> parser.parse("select * from t where n between 1 and 10"),
                        "between 应为不支持"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> parser.parse("select * from t where c is null"),
                        "is null 应为不支持"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> parser.parse("select * from t where c like 'a%'"),
                        "like 应为不支持"));
    }

    @Test
    @DisplayName("限制 1.2：文档给出的替代写法确实可用")
    void documentedAlternativesWork() {
        // NOTES 里让用户把 in 改成 or、把 between 改成 >= and <=。
        // 替代写法自己要能用，否则那条建议就是空头支票
        assertAll(
                () -> assertDoesNotThrow(
                        () -> parser.parse("select * from t where c = 'a' or c = 'b'"),
                        "c = 'a' or c = 'b' 应可用"),
                () -> assertDoesNotThrow(
                        () -> parser.parse("select * from t where n >= 1 and n <= 10"),
                        "n >= 1 and n <= 10 应可用"));
    }

    @Test
    @DisplayName("已修复（1-2）：标识符可以加引号 —— 定界符会被剥掉")
    void quotedIdentifiersAreStripped() {
        // 【1-2 修好了】此前这三条断言的是「表名连引号一起当名字」：
        // 解析器不报错，把 `t` 整个当表名，于是"数据源不存在：`t`"
        // 这个误导性错误推迟到执行层才出现。
        //
        // 现在引号在词法层就被剥掉了 —— 引号是 SQL 的【定界符】，不是名字的一部分。
        // 用户写 from `销售` 就是想引用「销售」这个数据源，
        // Excel 里一列叫「销售」，不会叫「`销售`」。
        //
        // 三种定界符都支持：反引号（MySQL）、双引号（标准 SQL）、方括号（SQL Server）。
        assertAll(
                () -> assertEquals("t", parser.parse("select * from `t`").tableName(),
                        "反引号应被剥掉"),
                () -> assertEquals("t", parser.parse("select * from \"t\"").tableName(),
                        "双引号应被剥掉"),
                () -> assertEquals("t", parser.parse("select * from [t]").tableName(),
                        "方括号应被剥掉"),
                () -> assertEquals(List.of("a"),
                        List.of(parser.parse("select `a` from t").selectColumns()),
                        "列名上的定界符同样应被剥掉"),
                () -> assertEquals("销售",
                        parser.parse("select * from `销售`").tableName(),
                        "中文表名加反引号 —— 这是最贴近真实用法的场景"));
    }

    // ==================== 二、已知缺陷（应工作但不工作） ====================

    @Test
    @DisplayName("缺陷 2.1：列别名（as）完全不支持 —— 整段被当成一个列名")
    void columnAliasNotSupported() {
        // 【为什么要有这一条】这个缺陷在 2026-10 的 README 复核中被发现：
        // README 的【支持】表一直写着"select a as 别名"支持，而实现里
        // SQLParser 从来没有处理过 as（初始提交起就没有，不是回归）。
        // 文档与实现长期不一致，且没有任何测试因此变红 —— 正是本类开头说的那种漂移。
        //
        // 断言的不是"抛异常"（太弱，任何异常都能让那种断言通过），
        // 而是【解析出的列名就是那一整段】—— 这才是根因，
        // 将来实现别名时，这里会红，改的人必须同时更新 NOTES.md 的 2.1。
        assertAll(
                () -> assertEquals(List.of("city as c"),
                        List.of(parser.parse("select city as c from sales").selectColumns()),
                        "as 整段被当成一个列名（这就是别名不可用的根因）"),
                () -> assertEquals(List.of("city c"),
                        List.of(parser.parse("select city c from sales").selectColumns()),
                        "省略 as 的写法同样不支持"),
                () -> assertEquals(List.of("城市 as 城市名"),
                        List.of(parser.parse("select 城市 as 城市名 from t").selectColumns()),
                        "中文别名同样不支持"));
    }

    @Test
    @DisplayName("已修复（1-2）：名字里含 from 不再影响切分")
    void nameContainingFromWorks() {
        // 【1-2 修好了】此前解析器按第一个 " from " 字符位置切分，不看词边界，
        // 于是名字里出现 from 就会把语句切错（D2）。
        //
        // 现在 from 只在它是【关键字 token】时才当子句边界。
        // 词法层按位置判定词性：列名段里的 from 不是关键字，字符串里的更不是。
        //
        // 三条实测用例都保留着，但断言换成了正确的值：
        //   ① 字符串字面量里的 from  → 表名 t（引号内是一个 STRING token）
        //   ② 列名里的 from          → 表名 t_from_x，列名完整
        //   ③ 表名自己含 from        → 表名 t_from_x（这条本来就对，必须保持）
        assertAll(
                () -> assertEquals("t",
                        parser.parse("select 'from' as x from t").tableName(),
                        "字符串字面量里的 from 不该被当子句边界"),
                () -> assertEquals("t_from_x",
                        parser.parse("select city_as from_x from t_from_x").tableName(),
                        "列名里的 from 不该截断表名"),
                () -> assertEquals(List.of("city_as from_x"),
                        List.of(parser.parse("select city_as from_x from t_from_x").selectColumns()),
                        "列名应完整保留，不再被腰斩"),
                () -> assertEquals("t_from_x",
                        parser.parse("select * from t_from_x").tableName(),
                        "表名自己含 from —— 这一条从来就是对的，修 D2 不能把它弄坏"),
                () -> assertEquals("from_table",
                        parser.parse("select * from from_table").tableName(),
                        "表名以 from 开头也应正常"),
                () -> assertEquals("t",
                        parser.parse("select city_from from t").tableName(),
                        "列名整体就叫 city_from"));
    }

    @Test
    @DisplayName("缺陷 2.2 的边界：from 出现在 where 之后就没事 —— 表名仍然正确")
    void fromInsideWhereIsFine() {
        // NOTES 里写了「where 之后不再找 from」。这条边界值得钉住：
        // 它划出了缺陷的影响范围，也解释了为什么用户觉得"有时行有时不行"
        QueryStatement stmt = parser.parse("select * from t where 城市 = 'from'");

        assertEquals("t", stmt.tableName(),
                "where 里的 'from' 不应影响表名");
        assertTrue(stmt.hasWhere(), "where 条件应当保留");
    }

    @Test
    @DisplayName("缺陷 2.4：不支持的子句会整段变成表名，导致误报「数据源不存在」")
    void unsupportedSyntaxBecomesGarbageTableName() {
        // 根因钉在这里比钉报错文案更有价值：
        // 报错文案以后可能会改好，但只要"垃圾表名"这个现象在，
        // 误导就还会以别的形式冒出来
        assertAll(
                () -> assertEquals("t join t2 on t.姓名 = t2.姓名",
                        parser.parse("select * from t join t2 on t.姓名 = t2.姓名").tableName(),
                        "join 整段被当成了表名"),
                // 【2026-10-11 更正】这条以前写的是
                //     assertThrows(RuntimeException.class,
                //             () -> parser.parse("select 城市, count(*) from t group by 城市"))
                // 并注释「group by 已能在解析期被拦下（这是后来补的）」。两处都错：
                //
                //   1. 那条 SQL 之所以抛，是因为「普通列与聚合混写」规则 ——
                //      与 group by 毫无关系。把 group by 去掉后它照样抛，
                //      两条报错一字不差（实测确认）。夹具测的是那条规则。
                //   2. group by 从来没有被专门处理过，主代码里搜不到任何相关逻辑。
                //
                // 夹具选错，就把「group by 会被整段吞进表名」这个真相盖住了 ——
                // 而 README 与 NOTES 当时都声称它有专门文案。
                // 现在换成不带普通列的纯 group by，它才真正测到 group by 本身。
                () -> assertEquals("t group by 城市",
                        parser.parse("select count(*) from t group by 城市").tableName(),
                        "group by 仍被整段当成表名（它没有专门处理，也没有专门报错）"));
    }

    // ==================== 已实现、文档不得倒退 ====================

    @Test
    @DisplayName("多列 order by 与 limit offset 已实现，文档不得再写成不支持")
    void implementedFeaturesStayImplemented() {
        // README 曾经把这两项写在"不支持"表格里，而实现早已完成。
        // 反向漂移同样有害：用户会因为文档而放弃可用的功能
        assertAll(
                () -> assertDoesNotThrow(
                        () -> parser.parse("select * from t order by 城市 asc, 薪资 desc"),
                        "多列 order by 应可用"),
                () -> assertDoesNotThrow(
                        () -> parser.parse("select * from t limit 3 offset 2"),
                        "limit n offset m 应可用"),
                () -> assertDoesNotThrow(
                        () -> parser.parse("select * from t offset 2"),
                        "offset 单独使用应可用"));
    }

    @Test
    @DisplayName("limit m, n 仍然被拒绝（有意保留的不支持）")
    void commaLimitStillRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t limit 2, 3"));

        assertTrue(e.getMessage().contains("offset"), e.getMessage());
    }
}
