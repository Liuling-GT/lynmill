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
 * <p><b>维护约定</b>：如果某条限制被实现了，不要删掉这里的测试，
 * 而是把它从"会抛异常"改成"能正常解析"，并同步删掉 NOTES.md 的条目。
 * 反过来，如果新增了限制，先在这里加断言，再去写文档。
 */
class DocumentedLimitationsTest {

    private final SQLParser parser = new SQLParser();

    // ==================== 一、已知限制（有意为之） ====================

    @Test
    @DisplayName("限制 1.1：group by 不支持")
    void groupByNotSupported() {
        // 解析环节就得拦下来。若哪天放行到执行期才报错，
        // NOTES 里"当前是一套整体聚合的执行模型"这句话就不再准确了
        assertThrows(RuntimeException.class,
                () -> parser.parse("select 城市, count(*) from t group by 城市"));
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
    @DisplayName("限制 1.3：标识符不能加引号 —— 解析器会把它连引号一起当成表名")
    void quotedIdentifiersNotSupported() {
        // 注意这里断言的是「解析出来的表名带着引号」而不是「抛异常」。
        // 解析器其实**不报错**，它把 `t` 连引号一起当成表名，
        // 于是"数据源不存在：`t`" 这个误导性错误推迟到执行层才出现。
        // 这个区分很重要：以为解析器会拦，就会去找解析器的错，方向全错
        assertAll(
                () -> assertEquals("`t`", parser.parse("select * from `t`").tableName(),
                        "反引号应被当表名的一部分"),
                () -> assertEquals("\"t\"", parser.parse("select * from \"t\"").tableName(),
                        "双引号应被当表名的一部分"),
                () -> assertEquals("[t]", parser.parse("select * from [t]").tableName(),
                        "方括号应被当表名的一部分"));
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
    @DisplayName("缺陷 2.2：名字里含 from 会被当成表名的一部分")
    void nameContainingFromBreaksTableName() {
        // 这是缺陷不是限制 —— 名字里出现 from 完全合法。
        // 实测的真相：解析器**不报错**，它按第一个 " from " 切分，
        // 于是表名变成了一堆垃圾，报错推迟到查数据源时。
        //
        // 下面三条都是【实测出来的】，不是推想的：
        //   ① 字符串字面量里的 from  → 表名变成 "' as x from t"
        //   ② 列名里的 from          → 从 from 处切，列名被腰斩，表名剩 "_x from t_from_x"
        //   ③ 表名自己含 from        → 反而正常（切出来就是 t_from_x）
        // ③ 值得留着：它说明这个缺陷不是"含 from 就坏"，而是
        // "按第一个 ' from ' 切"，所以只有出现在 from 之前的部分会受害。
        //
        // 修好之后这里要改成断言正确的表名，并删掉 NOTES.md 的 2.2
        assertAll(
                () -> assertEquals("' as x from t",
                        parser.parse("select 'from' as x from t").tableName(),
                        "字符串字面量里的 from 会截断出错误的表名"),
                () -> assertEquals("_x from t_from_x",
                        parser.parse("select city_as from_x from t_from_x").tableName(),
                        "列名里的 from 会截断出错误的表名"),
                () -> assertEquals("t_from_x",
                        parser.parse("select * from t_from_x").tableName(),
                        "表名自己含 from 反而是正常的 —— 缺陷只影响 from 之前的部分"));
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
                () -> assertThrows(RuntimeException.class,
                        () -> parser.parse("select 城市, count(*) from t group by 城市"),
                        "group by 已能在解析期被拦下（这是后来补的）"));
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
