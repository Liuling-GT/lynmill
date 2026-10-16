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
    @DisplayName("限制 1.2：比较运算符 7 个；in/between 已支持（L2），is null/like 仍不支持")
    void onlySevenComparisonOperators() {
        // 【L2 修改】in 与 between 从「不支持」改为「解析期自动展开」：
        //   c in ('a','b')      → c = 'a' or c = 'b'
        //   n between 1 and 10  → n >= 1 and n <= 10
        // 这是用户明确要求做的一项，不是重构顺带改的。
        // 展开成已有的 Condition 组合，执行层无需改动。
        var inStmt = parser.parse("select * from t where c in ('a','b')");
        assertEquals("OrCondition[left=SimpleCondition[field=c, op==, value=a], "
                        + "right=SimpleCondition[field=c, op==, value=b]]",
                String.valueOf(inStmt.condition()),
                "in 应展开成 = 的 or 组合");

        var betweenStmt = parser.parse("select * from t where n between 1 and 10");
        assertEquals("AndCondition[left=SimpleCondition[field=n, op=>=, value=1], "
                        + "right=SimpleCondition[field=n, op=<=, value=10]]",
                String.valueOf(betweenStmt.condition()),
                "between 应展开成 >= 与 <= 的 and 组合");

        // is null / like 仍未支持 —— 这两条要的是空值语义与模式匹配，
        // 不是「写法糖」，展开不出来，所以留在不支持列表里
        assertAll(
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
    @DisplayName("已实现（D1）：列别名 as —— 表达式与别名分开保存")
    void columnAliasWorks() {
        // 【D1 实现了】此前 as 整段被当成一个列名，报 Unknown column: city as c。
        //
        // 现在把别名拆出来单独保存：
        //   selectColumns 保持纯净（既有逻辑：聚合识别、常量识别、取列，一行不用改）
        //   aliases 只影响结果表头
        //
        // 这里断言的是【拆分结果】而不只是「不报错」—— 只断言不抛异常太弱，
        // 无法区分「拆对了」和「碰巧没报错」。
        var stmt = parser.parse("select city as c from sales");
        assertEquals(List.of("city"), List.of(stmt.selectColumns()),
                "as 应被拆出去，列表达式只剩 city");
        assertEquals(List.of("c"), List.of(stmt.aliases()),
                "别名应是 c");
        assertEquals("c", stmt.headerAt(0), "结果表头应取别名");

        // 聚合 + 别名：列表达式要保持 count(*) 原样，
        // 否则 isAggregateExpr 的 count( 前缀匹配会失效
        var agg = parser.parse("select count(*) as 行数 from sales");
        assertEquals(List.of("count(*)"), List.of(agg.selectColumns()),
                "聚合表达式的原文不能被动到，否则聚合识别会失效");
        assertEquals("行数", agg.headerAt(0));

        // 省略 as：第一段是表达式（含括号/星号）时才拆
        assertEquals(List.of("amount * 2"),
                List.of(parser.parse("select amount * 2 双倍 from sales").selectColumns()),
                "第一段是表达式时可以省 as");

        // 省略 as + 裸标识符：不拆 —— 与「含空格的列名」同形，无法可靠区分
        var plain = parser.parse("select city c from sales");
        assertEquals(List.of("city c"), List.of(plain.selectColumns()),
                "两个裸标识符无法区分「别名」与「含空格的列名」，保守按列名处理");
    }

    @Test
    @DisplayName("已修复：裸写含定界符的列名明确报错，不静默匹配")
    void bareDelimiterInColumnNameIsRejected() {
        // 列名里真的可能有反引号（表里就有一列叫 a`b）。但如果用户**不写定界符**
        // 直接裸写 a`b，那就必须报错 —— 因为那个反引号一旦被词法器看见，
        // 就必须有配对的收尾，否则整条 SQL 的切分位置都是猜的。
        //
        // 早先的行为是「静默通过」：把 a`b 当字面列名直接去匹配，
        // 于是语法错误被掩盖成一次正常查询。这是 N3。
        //
        // 期望值依据真实 SQLite：select a`b 在 SQLite 里也是报错
        // （unrecognized token），与本产品一致。
        var parser = new SQLParser();

        // 裸写 a`b：那个反引号一旦被看见就必须配对，所以报「未闭合定界符」。
        // 这是 N3 要的结果 —— 从「静默匹配」变成「明确报错」。
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select a`b from t"),
                "裸写的反引号应当报「未闭合定界符」，不能静默当成列名");

        // 对照：正确地写定界符就应当正常
        assertAll(
                () -> assertEquals(List.of("a`b"),
                        List.of(parser.parse("select `a``b` from t").selectColumns()),
                        "`` 转义后引用列名 a`b"),
                () -> assertEquals(List.of("a'b"),
                        List.of(parser.parse("select `a'b` from t").selectColumns()),
                        "反引号内可含单引号"));
    }

    @Test
    @DisplayName("记录：a`b` 会被读成裸 a + 定界 b，拼成 ab")
    void bareAThenDelimitedBReadsAsTwoTokens() {
        // 【为什么单独记一条】这条不是为了断言「正确」，而是把**实际行为钉住**，
        // 免得下次有人看到 `a`b`` 报「未知列 ab」时以为出了新 bug。
        //
        // 事实：第二个反引号会**开启**一个定界标识符，于是被切成两个 token
        // —— 裸 `a` 与定界 `b` —— 拼起来是 `ab`。解析**成功**（不报语法错），
        // 到了执行层查不到 ab 这一列，才报「未知列」。
        //
        // 与 SQLite 的差异：SQLite 直接报 unrecognized token（语法错误）。
        // 但两者**都是报错**，不会静默给出错误结果 —— 可接受。
        // 要统一成语法错误，得让词法器知道「反引号紧跟普通字符」本身非法，
        // 那会牵动非定界标识符的扫描规则，收益不抵风险。
        //
        // 注意 `a`b` 与 `a`b`+收尾反引号的区别：
        //   select a`b   → 未闭合 → 解析期就报错（见上一条测试）
        //   select a`b`  → 成对   → 解析成 ab，执行期报未知列
        var parser = new SQLParser();
        assertEquals(List.of("ab"),
                List.of(parser.parse("select a`b` from t").selectColumns()),
                "a + `b` 拼成 ab；这条记录的是实际行为，不是理想行为");
    }

    @Test
    @DisplayName("已修复：定界标识符的名字不被当成字符串常量剥引号")
    void delimitedIdentifierKeepsItsOwnQuotes() {
        // 【为什么要有这一条】列名本身可以是「一串单引号」——
        // 对拍表里就有叫 ''' 、'' 、' 的列。它们用反引号/双引号/方括号引起来时，
        // 词法层剥掉的只是**外层定界符**，名字里的单引号必须原样留着。
        //
        // 执行层有一处 unquote 会把「首尾都是单引号」的文本当字符串字面量剥掉，
        // 那条规则对 select '总计' 是对的，对定界标识符是错的：
        // 它把列名 ''' 剥成了 '，于是查询「成功」但取到了**另一列**。
        // 这种错很难发现 —— 不报错，只是数据不对。
        //
        // 期望值不是我定的，是对真实 SQLite 实测确认的：
        //   select `'''`  → 列 '''       select `''`   → 列 ''
        //   select `'`    → 列 '         select `''''` → 列 ''''
        var parser = new SQLParser();

        // 词法层：Token 的 text 是最终列名，且带 delimited 标志
        for (String[] pair : new String[][]{
                {"`'''`", "'''"}, {"`''`", "''"}, {"`'`", "'"}, {"`''''`", "''''"},
                {"\"'''\"", "'''"}, {"[''']", "'''"}}) {
            var stmt = parser.parse("select " + pair[0] + " from t");
            assertEquals(List.of(pair[1]), List.of(stmt.selectColumns()),
                    pair[0] + " 解析出的列名应当是 " + pair[1]);
            assertEquals(pair[1], stmt.delimitedColumnAt(0),
                    pair[0] + " 是定界标识符，delimitedColumnAt 必须给出原始列名 —— "
                            + "执行层靠它区分「字符串常量」与「定界标识符」");
        }

        // 对照组：真正的字符串常量仍然要被剥引号，否则表头会带着引号显示
        var literal = parser.parse("select '总计' from t");
        assertEquals(List.of("'总计'"), List.of(literal.selectColumns()),
                "字符串常量的原文（含引号）");
        assertEquals(null, literal.delimitedColumnAt(0),
                "字符串常量**不是**定界标识符，执行层仍走 unquote 剥引号");
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
