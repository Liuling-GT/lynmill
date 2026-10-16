package com.tinyquery.parser;

import com.tinyquery.i18n.Messages;
import com.tinyquery.parser.where.AndCondition;
import com.tinyquery.parser.where.NotCondition;
import com.tinyquery.parser.where.OrCondition;
import com.tinyquery.parser.where.SimpleCondition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SQLParser 解析")
class SQLParserTest {

    private final SQLParser parser = new SQLParser();

    /**
     * 把语言钉死在中文。
     *
     * <p>本类的断言大量检查报错原文（「未闭合」「缺少右括号」…），
     * 而这些文案是跟着 {@link Messages#current()} 走的。不钉住的话，
     * 只要同一线程上跑过别的用例把语言切成了英文，这里就会莫名其妙地红 ——
     * 而失败原因与被测的解析逻辑毫无关系。</p>
     *
     * <p>英文文案本身由 {@code MessagesTest} 负责验证，两边各管各的。</p>
     */
    @BeforeEach
    void pinChinese() {
        Messages.setCurrent(Messages.ZH);
    }

    @AfterEach
    void clearLocale() {
        Messages.clear();
    }

    // ==================== 基础 ====================

    @Test
    @DisplayName("最简查询：表名与全列")
    void parseMinimal() {
        QueryStatement stmt = parser.parse("select * from huge");

        assertEquals("huge", stmt.tableName());
        assertArrayEquals(new String[]{"*"}, stmt.selectColumns());
        assertFalse(stmt.hasWhere());
        assertFalse(stmt.hasOrderBy());
        assertFalse(stmt.hasLimit());
    }

    @Test
    @DisplayName("显式列清单：按逗号拆分并去空白")
    void parseColumnList() {
        QueryStatement stmt = parser.parse("select name , salary from huge");

        assertArrayEquals(new String[]{"name", "salary"}, stmt.selectColumns());
        assertEquals("huge", stmt.tableName());
    }

    @Test
    @DisplayName("表名保留 .csv 后缀，映射交给 DataSourceManager")
    void tableNameKeepsSuffix() {
        assertEquals("huge.csv", parser.parse("select * from huge.csv").tableName());
    }

    // ==================== WHERE ====================

    @Test
    @DisplayName("简单条件：字段、运算符、值")
    void parseSimpleCondition() {
        QueryStatement stmt = parser.parse("select * from t where age > 25");

        assertTrue(stmt.hasWhere());
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class, stmt.condition());
        assertEquals("age", cond.field());
        assertEquals(">", cond.op());
        assertEquals("25", cond.value());
    }

    @Test
    @DisplayName("AND 优先级高于 OR：age > 30 and city = '北京' or salary > 1 拆成 Or(And(...), ...)")
    void parseAndOrPrecedence() {
        QueryStatement stmt = parser.parse(
                "select * from t where age > 30 and city = '北京' or salary > 1");

        OrCondition or = assertInstanceOf(OrCondition.class, stmt.condition());
        assertInstanceOf(AndCondition.class, or.left());
        assertInstanceOf(SimpleCondition.class, or.right());
    }

    @Test
    @DisplayName("NOT 取反")
    void parseNot() {
        QueryStatement stmt = parser.parse("select * from t where not city = '北京'");

        NotCondition not = assertInstanceOf(NotCondition.class, stmt.condition());
        assertInstanceOf(SimpleCondition.class, not.inner());
    }

    @Test
    @DisplayName("引号包裹的值会被剥掉引号")
    void parseQuotedValue() {
        QueryStatement stmt = parser.parse("select * from t where city = '北京'");

        SimpleCondition cond = (SimpleCondition) stmt.condition();
        assertEquals("北京", cond.value());
    }

    @Test
    @DisplayName("不支持的运算符报错")
    void unsupportedOperatorThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t where city like '北%'"));

        assertTrue(e.getMessage().contains("不支持的运算符"), e.getMessage());
    }

    // ==================== LIMIT / OFFSET ====================

    @Test
    @DisplayName("LIMIT 解析为上限，且不污染表名")
    void parseLimit() {
        QueryStatement stmt = parser.parse("select * from huge limit 10");

        assertTrue(stmt.hasLimit());
        assertEquals(10, stmt.limit());
        assertEquals("huge", stmt.tableName());
    }

    @Test
    @DisplayName("LIMIT 0 合法，表示不返回任何行")
    void parseLimitZero() {
        assertEquals(0, parser.parse("select * from t limit 0").limit());
    }

    @Test
    @DisplayName("逗号形式明确拒绝，并指向正确写法")
    void parseLimitCommaRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t limit 10, 20"));

        assertTrue(e.getMessage().contains("offset"), "报错应指出改用 offset 写法：" + e.getMessage());
    }

    @Test
    @DisplayName("逗号形式后跟 offset 也要拒绝，不能只剥掉后半截")
    void parseLimitCommaWithOffsetRejected() {
        // 逗号模式锚定语句末尾，匹配不上这一句；若没有兜底闸，
        // offset 5 会被剥掉、limit 10, 20 留在语句里污染表名
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t limit 10, 20 offset 5"));

        assertTrue(e.getMessage().contains("LIMIT/OFFSET"), e.getMessage());
    }

    @Test
    @DisplayName("limit n offset m 解析出两个值，且不污染表名")
    void parseLimitWithOffset() {
        QueryStatement stmt = parser.parse("select * from huge limit 10 offset 5");

        assertTrue(stmt.hasLimit());
        assertEquals(10, stmt.limit());
        assertTrue(stmt.hasOffset());
        assertEquals(5, stmt.offset());
        assertEquals("huge", stmt.tableName());
    }

    @Test
    @DisplayName("单独的 offset 合法，此时没有行数上限")
    void parseOffsetOnly() {
        QueryStatement stmt = parser.parse("select * from huge offset 5");

        assertFalse(stmt.hasLimit());
        assertNull(stmt.limit());
        assertEquals(5, stmt.offset());
        assertEquals("huge", stmt.tableName());
    }

    @Test
    @DisplayName("offset 0 与不写 offset 等价，hasOffset 为假")
    void parseOffsetZeroEqualsAbsent() {
        QueryStatement stmt = parser.parse("select * from t limit 3 offset 0");

        assertEquals(3, stmt.limit());
        assertEquals(0, stmt.offset());
        assertFalse(stmt.hasOffset(), "offset 0 不该被当成「要跳过」");
    }

    @Test
    @DisplayName("limit + offset 与 where / order by 组合，子句都能正确剥离")
    void parseLimitOffsetWithWhereAndOrderBy() {
        QueryStatement stmt = parser.parse(
                "select name from t where age > 30 order by salary desc limit 10 offset 5");

        assertEquals("t", stmt.tableName());
        assertEquals(10, stmt.limit());
        assertEquals(5, stmt.offset());
        assertTrue(stmt.hasWhere());
        assertTrue(stmt.hasOrderBy());
        assertEquals("salary", stmt.sort().first().column());
        assertTrue(stmt.sort().first().desc());
    }

    @Test
    @DisplayName("limit 后缺数字时给出明确报错，而不是把子句当成表名")
    void parseLimitWithoutNumberRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t limit abc"));

        assertTrue(e.getMessage().contains("LIMIT/OFFSET"), e.getMessage());
    }

    @Test
    @DisplayName("offset 后缺数字时给出明确报错")
    void parseOffsetWithoutNumberRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t limit 3 offset"));

        assertTrue(e.getMessage().contains("LIMIT/OFFSET"), e.getMessage());
    }

    @Test
    @DisplayName("负数 offset 报错，不静默当成 0")
    void parseNegativeOffsetRejected() {
        // 负号不是数字，三种锚定模式都匹配不上，落到兜底闸
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t limit 3 offset -1"));

        assertTrue(e.getMessage().contains("LIMIT/OFFSET"), e.getMessage());
    }

    @Test
    @DisplayName("子句顺序写反（limit 在 order by 之前）要报错，不能静默接受")
    void parseLimitBeforeOrderByRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t limit 3 order by age"));

        assertTrue(e.getMessage().contains("LIMIT/OFFSET"), e.getMessage());
    }

    @Test
    @DisplayName("引号内的 limit / offset 是数据，不算子句")
    void literalLimitNotTreatedAsClause() {
        QueryStatement stmt = parser.parse("select * from t where note = 'limit 10 offset 5'");

        assertFalse(stmt.hasLimit());
        assertFalse(stmt.hasOffset());
        assertEquals("t", stmt.tableName());
    }

    @Test
    @DisplayName("列名里含 limit / offset 不算子句")
    void columnNameContainingKeywordNotTreatedAsClause() {
        QueryStatement stmt = parser.parse("select offset_value from t where limit_flag = 1");

        assertFalse(stmt.hasLimit());
        assertFalse(stmt.hasOffset());
        assertEquals("t", stmt.tableName());
    }

    // ==================== ORDER BY ====================

    @Test
    @DisplayName("ORDER BY 缺省为升序")
    void parseOrderByDefaultAsc() {
        QueryStatement stmt = parser.parse("select * from t order by salary");

        assertTrue(stmt.hasOrderBy());
        assertEquals("salary", stmt.sort().first().column());
        assertFalse(stmt.sort().first().desc());
        assertEquals("t", stmt.tableName());
    }

    @Test
    @DisplayName("ORDER BY DESC")
    void parseOrderByDesc() {
        QueryStatement stmt = parser.parse("select * from t order by salary desc");

        assertTrue(stmt.sort().first().desc());
        assertEquals("salary", stmt.sort().first().column());
    }

    @Test
    @DisplayName("ORDER BY 显式 ASC 与缺省一致")
    void parseOrderByAsc() {
        QueryStatement stmt = parser.parse("select * from t order by salary asc");

        assertFalse(stmt.sort().first().desc());
    }

    // ==================== 三级剥离顺序（核心不变式） ====================

    @Test
    @DisplayName("WHERE + ORDER BY + LIMIT 同时出现：三者都正确剥离，表名不被污染")
    void parseAllClausesTogether() {
        QueryStatement stmt = parser.parse(
                "select name, salary from huge where city = '北京' order by salary desc limit 20");

        assertEquals("huge", stmt.tableName());
        assertArrayEquals(new String[]{"name", "salary"}, stmt.selectColumns());
        assertEquals("salary", stmt.sort().first().column());
        assertTrue(stmt.sort().first().desc());
        assertEquals(20, stmt.limit());

        SimpleCondition cond = assertInstanceOf(SimpleCondition.class, stmt.condition());
        assertEquals("city", cond.field());
        assertEquals("北京", cond.value());
    }

    @Test
    @DisplayName("只有 ORDER BY 没有 LIMIT")
    void parseOrderByWithoutLimit() {
        QueryStatement stmt = parser.parse("select * from huge order by age");

        assertEquals("huge", stmt.tableName());
        assertEquals("age", stmt.sort().first().column());
        assertFalse(stmt.hasLimit());
    }

    @Test
    @DisplayName("只有 LIMIT 没有 ORDER BY")
    void parseLimitWithoutOrderBy() {
        QueryStatement stmt = parser.parse("select * from huge limit 5");

        assertEquals("huge", stmt.tableName());
        assertEquals(5, stmt.limit());
        assertFalse(stmt.hasOrderBy());
    }

    // ==================== 多列排序与非法写法 ====================

    @Test
    @DisplayName("多列排序：按逗号切分，各列方向独立")
    void multiColumnOrderBy() {
        QueryStatement stmt = parser.parse("select * from t order by city asc, salary desc");

        assertEquals("t", stmt.tableName());
        assertTrue(stmt.hasOrderBy());
        assertEquals(2, stmt.sort().size());
        assertEquals("city", stmt.sort().specs().get(0).column());
        assertFalse(stmt.sort().specs().get(0).desc());
        assertEquals("salary", stmt.sort().specs().get(1).column());
        assertTrue(stmt.sort().specs().get(1).desc());
    }

    @Test
    @DisplayName("多列排序：方向缺省为升序，可只给部分列写方向")
    void multiColumnOrderByDefaultAsc() {
        QueryStatement stmt = parser.parse("select * from t order by dept, salary desc, name");

        assertEquals(3, stmt.sort().size());
        assertFalse(stmt.sort().specs().get(0).desc());
        assertTrue(stmt.sort().specs().get(1).desc());
        assertFalse(stmt.sort().specs().get(2).desc());
        assertEquals("name", stmt.sort().specs().get(2).column());
    }

    @Test
    @DisplayName("多列排序：逗号周围的空格随意")
    void multiColumnOrderBySpacing() {
        QueryStatement stmt = parser.parse("select * from t order by dept,salary   desc");

        assertEquals(2, stmt.sort().size());
        assertEquals("dept", stmt.sort().specs().get(0).column());
        assertEquals("salary", stmt.sort().specs().get(1).column());
        assertTrue(stmt.sort().specs().get(1).desc());
    }

    @Test
    @DisplayName("多列排序与 WHERE / LIMIT / OFFSET 组合")
    void multiColumnOrderByWithOtherClauses() {
        QueryStatement stmt = parser.parse(
                "select * from t where age > 30 order by dept asc, salary desc limit 10 offset 5");

        assertEquals("t", stmt.tableName());
        assertTrue(stmt.hasWhere());
        assertEquals(2, stmt.sort().size());
        assertEquals(10, stmt.limit());
        assertEquals(5, stmt.offset());
    }

    @Test
    @DisplayName("单列排序仍解析为只有一个元素的列表")
    void singleColumnOrderByIsOneElementList() {
        QueryStatement stmt = parser.parse("select * from t order by salary desc");

        assertEquals(1, stmt.sort().size());
        assertEquals("salary", stmt.sort().first().column());
        assertTrue(stmt.sort().first().desc());
    }

    @Test
    @DisplayName("排序列为空时报错，不让逗号跑进列名")
    void emptyOrderByItemRejected() {
        // 早先 order by dept, 会把列名解析成 "dept,"，报错说「列 dept, 不存在」——
        // 用户看着表里明明有 dept 列，完全摸不着头脑
        for (String sql : new String[]{
                "select * from t order by dept,",
                "select * from t order by ,dept",
                "select * from t order by dept,,salary"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> parser.parse(sql), "应报错：" + sql);
            assertTrue(e.getMessage().contains("空的排序列"), e.getMessage());
        }
    }

    @Test
    @DisplayName("ORDER BY 后跟多余内容报错，不污染表名")
    void malformedOrderByRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t order by salary desc extra"));
    }

    @Test
    @DisplayName("多列里某列后跟多余内容也报错")
    void malformedMultiColumnOrderByRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t order by dept asc extra, salary"));
    }

    // ==================== 聚合 / 明细混写 ====================
    // 聚合结果固定一行，明细列没有「当前行」可引用。此前会静默把列名当值输出
    // （select name, count(*) 得到 [name, 3]），现在在解析阶段就报错。

    @Test
    @DisplayName("普通列 + 聚合列混写报错，不再把列名当值输出")
    void mixedSelectRejected() {
        for (String sql : new String[]{
                "select name, count(*) from t",
                "select count(*), name from t",
                "select name, salary, count(*) from t"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> parser.parse(sql), "应报错：" + sql);
            assertTrue(e.getMessage().contains("不能混用普通列"), e.getMessage());
        }
    }

    @Test
    @DisplayName("报错信息里带上出问题的那一列")
    void mixedSelectNamesOffendingColumn() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select name, count(*) from t"));

        assertTrue(e.getMessage().contains("name"), "报错应指出是哪一列：" + e.getMessage());
    }

    @Test
    @DisplayName("各种聚合函数与普通列混写都报错")
    void mixedSelectAllAggregateFunctions() {
        for (String func : new String[]{"count(*)", "sum(salary)", "avg(salary)",
                "max(salary)", "min(salary)"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> parser.parse("select name, " + func + " from t"),
                    "应报错：" + func);
        }
    }

    @Test
    @DisplayName("纯聚合查询合法：多个聚合函数可以并存")
    void pureAggregateAllowed() {
        QueryStatement stmt = parser.parse("select count(*), avg(salary), min(salary) from t");

        assertTrue(stmt.isAggregate());
        assertEquals(3, stmt.selectColumns().length);
    }

    @Test
    @DisplayName("常量 + 聚合放行 —— 常量在那一行里就是它自己")
    void constantWithAggregateAllowed() {
        QueryStatement stmt = parser.parse("select 1, count(*) from t");

        assertTrue(stmt.isAggregate());
        assertEquals(2, stmt.selectColumns().length);
    }

    @Test
    @DisplayName("带引号的字符串常量 + 聚合也放行")
    void stringConstantWithAggregateAllowed() {
        QueryStatement stmt = parser.parse("select 'abc', count(*) from t");

        assertTrue(stmt.isAggregate());
    }

    @Test
    @DisplayName("小数常量 + 聚合放行")
    void decimalConstantWithAggregateAllowed() {
        assertTrue(parser.parse("select 3.14, count(*) from t").isAggregate());
    }

    @Test
    @DisplayName("聚合查询里混写常量与普通列时，仍报普通列那一项")
    void constantAndColumnWithAggregateStillRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select 1, name, count(*) from t"));

        assertTrue(e.getMessage().contains("name"), e.getMessage());
    }

    @Test
    @DisplayName("没有聚合函数时随便混写都不报错（回归锁）")
    void nonAggregateSelectNotValidated() {
        // 这些都是明细查询，多列并存本来就正常
        parser.parse("select name, salary from t");
        parser.parse("select 1, name from t");
        parser.parse("select 1 from t");
    }

    // ==================== 聚合参数的列名大小写 ====================
    // getAggregations 早先对整串 toLowerCase，把参数里的列名也改了 ——
    // 于是用户写对了大小写（sum(Salary)）反而报「Unknown column:salary」

    @Test
    @DisplayName("聚合参数列名保持用户原文，不被转成小写")
    void aggregateFieldKeepsOriginalCase() {
        QueryStatement stmt = parser.parse("select sum(Salary) from t");

        AggregationInfo agg = stmt.getAggregations().get(0);
        assertEquals("sum", agg.function());
        assertEquals("Salary", agg.field(), "列名必须保持原文，否则大小写敏感的数据源永远查不到");
    }

    @Test
    @DisplayName("函数名仍归一化为小写，COUNT / Count / count 都认")
    void aggregateFunctionNameStillNormalized() {
        for (String func : new String[]{"count", "COUNT", "Count"}) {
            QueryStatement stmt = parser.parse("select " + func + "(*) from t");
            assertEquals("count", stmt.getAggregations().get(0).function(), func + " 应被识别");
        }
    }

    @Test
    @DisplayName("表头沿用用户原文，不作为归一化后的样子")
    void aggregateOriginalKeepsUserSpelling() {
        QueryStatement stmt = parser.parse("select AVG(Salary) from t");

        assertEquals("AVG(Salary)", stmt.getAggregations().get(0).original());
    }

    // ==================== 聚合函数括号 ====================

    @Test
    @DisplayName("缺右括号时明确报错，而不是抛 StringIndexOutOfBoundsException")
    void missingClosingParenRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select count(* from t").getAggregations());

        assertTrue(e.getMessage().contains("缺少右括号"), e.getMessage());
    }

    @Test
    @DisplayName("缺右括号的报错里带上出问题的那个表达式")
    void missingClosingParenNamesExpression() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select sum(salary from t").getAggregations());

        assertTrue(e.getMessage().contains("sum(salary"), e.getMessage());
    }

    @Test
    @DisplayName("嵌套括号取最外层，不在第一个右括号处截断")
    void nestedParensTakeOutermost() {
        // 当前不支持嵌套聚合，但切分不该切在第一个 ')' 上而留下半个表达式
        QueryStatement stmt = parser.parse("select sum(f(x)) from t");

        String field = stmt.getAggregations().get(0).field();
        assertEquals("f(x)", field, "应取到最外层括号之间的内容");
    }

    // ==================== 名称提示 ====================

    @Test
    @DisplayName("大小写唯一匹配时给出提示")
    void suggestUniqueCaseVariant() {
        assertEquals("Salary", NameSuggester.suggest("salary", List.of("name", "Salary")));
        assertEquals("UserName", NameSuggester.suggest("username", List.of("UserName")));
    }

    @Test
    @DisplayName("多个候选时不猜 —— 报错但不出提示")
    void suggestAmbiguousReturnsNull() {
        assertNull(NameSuggester.suggest("salary", List.of("Salary", "SALARY")));
    }

    @Test
    @DisplayName("完全一致或毫无关系时都不提示")
    void suggestNoMatchReturnsNull() {
        assertNull(NameSuggester.suggest("salary", List.of("salary")), "完全相同不算候选");
        assertNull(NameSuggester.suggest("bonus", List.of("name", "Salary")), "毫无关系不该提示");
        assertNull(NameSuggester.suggest("salary", List.of()));
        assertNull(NameSuggester.suggest("salary", null));
    }

    // ==================== 缺失关键字 ====================

    @Test
    @DisplayName("缺少 FROM 报错")
    void missingFromThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from"));

        assertTrue(e.getMessage().contains("缺少表名"));
    }

    @Test
    @DisplayName("缺少 SELECT 报错")
    void missingSelectThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("delete from t"));
    }

    // ==================== 引号感知 ====================

    // 【1-2 修好了，本测试从 @Disabled 转为启用】
    // 此前它被 @Disabled 标着「已知缺陷」，注释里写的修复方向是
    // 「匹配前统计该位置之前的单引号个数，为奇数则视为引号内」——
    // 那是给旧的正则实现打的补丁思路。
    //
    // 1-2 没有走补丁路线：词法层把 'a order by b' 整体扫成一个 STRING token，
    // 排序子句只在 order / by 是 KEYWORD token 时才成立。
    // 引号内的内容是 STRING，压根不参与判断 —— 缺陷消失，而不是被绕开。
    @Test
    @DisplayName("值内部含 order by 不应被识别为排序子句")
    void orderByInsideStringLiteral() {
        QueryStatement stmt = parser.parse("select * from t where note = 'a order by b'");

        assertFalse(stmt.hasOrderBy(), "引号内的 order by 不应被当作排序子句");
        assertEquals("t", stmt.tableName());
    }

    @Test
    @DisplayName("值内部含 order by，且语句真的有排序子句时，两者互不干扰")
    void orderByInsideLiteralPlusRealOrderBy() {
        QueryStatement stmt =
                parser.parse("select * from t where note = 'a order by b' order by age desc");

        assertTrue(stmt.hasOrderBy(), "真的 order by 应被识别");
        assertEquals(1, stmt.sort().size());
        assertEquals("age", stmt.sort().first().column());
        assertEquals("a order by b", assertInstanceOf(SimpleCondition.class, stmt.condition()).value(),
                "值里的 order by 应完整保留");
    }

    @Test
    @DisplayName("值内部含 from 不会截断表名")
    void fromInsideStringLiteral() {
        QueryStatement stmt = parser.parse("select * from t where note = 'from here'");

        assertEquals("t", stmt.tableName(), "引号内的 from 不该影响表名");
        assertEquals("from here", assertInstanceOf(SimpleCondition.class, stmt.condition()).value());
    }

    // ==================== 括号 ====================

    @Test
    @DisplayName("最外层括号被剥离：where (age > 30) 等价于 where age > 30")
    void parseOuterParens() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where (age > 30)").condition());

        assertEquals("age", cond.field(), "左括号不应留在字段名里");
        assertEquals("30", cond.value(), "右括号不应留在值里");
    }

    @Test
    @DisplayName("多层括号同样被剥离")
    void parseNestedOuterParens() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where ((age > 30))").condition());

        assertEquals("age", cond.field());
        assertEquals("30", cond.value());
    }

    @Test
    @DisplayName("括号内的 OR 不能被吞掉：and (a or b) 必须保留完整条件树")
    void parseParenWrappedOrIsKept() {
        QueryStatement stmt = parser.parse(
                "select * from t where age > 30 and (city = '北京' or city = '上海')");

        AndCondition and = assertInstanceOf(AndCondition.class, stmt.condition());
        assertEquals("age", ((SimpleCondition) and.left()).field());

        OrCondition or = assertInstanceOf(OrCondition.class, and.right());
        assertEquals("北京", ((SimpleCondition) or.left()).value());
        assertEquals("上海", ((SimpleCondition) or.right()).value());
    }

    @Test
    @DisplayName("括号在左侧时同样保留完整条件树")
    void parseParenWrappedLeadingGroup() {
        QueryStatement stmt = parser.parse(
                "select * from t where (city = '北京' or city = '上海') and age > 30");

        AndCondition and = assertInstanceOf(AndCondition.class, stmt.condition());
        assertInstanceOf(OrCondition.class, and.left());
        assertEquals("age", ((SimpleCondition) and.right()).field());
    }

    @Test
    @DisplayName("NOT 后跟括号：not (age > 30)")
    void parseNotWithParens() {
        QueryStatement stmt = parser.parse("select * from t where not (age > 30)");

        NotCondition not = assertInstanceOf(NotCondition.class, stmt.condition());
        SimpleCondition inner = assertInstanceOf(SimpleCondition.class, not.inner());
        assertEquals("age", inner.field());
    }

    @Test
    @DisplayName("值两侧的括号也剥掉：where a > (1)")
    void parseParenAroundValue() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where a > (1)").condition());

        assertEquals("1", cond.value());
    }

    @Test
    @DisplayName("并列的两个括号组各自独立，不会被整体剥掉")
    void parseTwoSiblingGroups() {
        QueryStatement stmt = parser.parse("select * from t where (a > 1) and (b < 2)");

        AndCondition and = assertInstanceOf(AndCondition.class, stmt.condition());
        assertEquals("a", ((SimpleCondition) and.left()).field());
        assertEquals("b", ((SimpleCondition) and.right()).field());
    }

    // ==================== 值里的空格 ====================

    @Test
    @DisplayName("值里的空格不再截断：'John Smith'")
    void parseValueWithSpace() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where name = 'John Smith'").condition());

        assertEquals("name", cond.field());
        assertEquals("John Smith", cond.value(), "值必须保留到末尾，不能只取第一段");
    }

    @Test
    @DisplayName("中文值里的空格同样保留")
    void parseChineseValueWithSpace() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where name = '李 雷'").condition());

        assertEquals("李 雷", cond.value());
    }

    @Test
    @DisplayName("未加引号却含空格的值明确报错，不静默做字符串比较")
    void parseUnquotedValueWithSpaceRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t where age > 30 extra"));

        assertTrue(e.getMessage().contains("值含空格"));
    }

    // ==================== 引号内的字面量不得被改写 ====================

    @Test
    @DisplayName("值以 and 开头：'And' 不再崩溃")
    void parseLiteralStartingWithAnd() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where name = 'And'").condition());

        assertEquals("And", cond.value(), "引号内的 And 不得被改写成 ' and '");
    }

    @Test
    @DisplayName("值以 or 开头：'or' 不再崩溃")
    void parseLiteralStartingWithOr() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where name = 'or'").condition());

        assertEquals("or", cond.value());
    }

    @Test
    @DisplayName("值以 not 开头且含空格：'Not Only'")
    void parseLiteralStartingWithNot() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where name = 'Not Only'").condition());

        assertEquals("Not Only", cond.value());
    }

    @Test
    @DisplayName("值里的运算符不被补空格：'a>b'")
    void parseLiteralWithOperatorChar() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where note = 'a>b'").condition());

        assertEquals("a>b", cond.value(), "引号内的 > 不得被改写成 ' > '");
    }

    @Test
    @DisplayName("值里的连续空格不被折叠：'hello   world'")
    void parseLiteralWithMultipleSpaces() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where note = 'hello   world'").condition());

        assertEquals("hello   world", cond.value(), "三个空格必须原样保留");
    }

    @Test
    @DisplayName("值里的 and 不被当作连接词：'x and y'")
    void parseLiteralContainingAnd() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where name = 'x and y'").condition());

        assertEquals("x and y", cond.value(), "引号内的 and 前后虽有空格，但不是连接词");
    }

    @Test
    @DisplayName("值里的 or 不被当作连接词：'x or y'")
    void parseLiteralContainingOr() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where name = 'x or y'").condition());

        assertEquals("x or y", cond.value());
    }

    @Test
    @DisplayName("值里的括号不被当作条件括号：= ')'")
    void parseLiteralContainingParen() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where name = ')'").condition());

        assertEquals("name", cond.field());
        assertEquals(")", cond.value());
    }

    @Test
    @DisplayName("括号里套着含括号的值：where (name = ')')")
    void parseParenWithParenInsideLiteral() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where (name = ')')").condition());

        assertEquals("name", cond.field(), "配对时必须跳过引号内的右括号");
        assertEquals(")", cond.value());
    }

    @Test
    @DisplayName("转义单引号被还原：'it''s' → it's")
    void parseEscapedQuote() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where name = 'it''s'").condition());

        assertEquals("it's", cond.value());
    }

    @Test
    @DisplayName("空字符串字面量：= '' 得到空值，不崩溃")
    void parseEmptyLiteral() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where name = ''").condition());

        assertEquals("", cond.value());
    }

    @Test
    @DisplayName("值里的换行原样保留")
    void parseLiteralWithNewline() {
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class,
                parser.parse("select * from t where a = 'x\ny'").condition());

        assertEquals("x\ny", cond.value());
    }

    // ==================== 补丁 A：引号未闭合 ====================

    @Test
    @DisplayName("引号未闭合明确报错，不再静默返回空结果")
    void unterminatedLiteralRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t where name = 'abc"));

        assertTrue(e.getMessage().contains("未闭合"), e.getMessage());
    }

    @Test
    @DisplayName("引号未闭合（含 and，避免被拆成两个条件）同样报错")
    void unterminatedLiteralWithAndRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t where name = 'a and b = 1"));

        assertTrue(e.getMessage().contains("未闭合"), e.getMessage());
    }

    @Test
    @DisplayName("成对的转义引号不算未闭合：'it''s'")
    void escapedQuoteNotTreatedAsUnterminated() {
        assertDoesNotThrow(() -> parser.parse("select * from t where name = 'it''s'"));
    }

    @Test
    @DisplayName("正常的成对引号不受影响")
    void normalQuotingNotAffected() {
        assertDoesNotThrow(() -> parser.parse("select * from t where city = '北京'"));
    }

    // ==================== 补丁 B：WHERE 后没有内容 ====================

    @Test
    @DisplayName("WHERE 后面没写条件时报错，不再静默返回全表")
    void emptyWhereRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t where"));

        assertTrue(e.getMessage().contains("WHERE 后面缺少"), e.getMessage());
    }

    @Test
    @DisplayName("WHERE 后面只有空格同样报错")
    void blankWhereRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("select * from t where   "));
    }

    // ==================== 补丁 C：WHERE 定位跳过引号内 ====================

    @Test
    @DisplayName("SELECT 列表里的字符串含 where，不再抛 StringIndexOutOfBoundsException")
    void whereInsideSelectLiteralDoesNotCrash() {
        QueryStatement stmt = assertDoesNotThrow(() -> parser.parse("select 'where' as x from t"));

        assertFalse(stmt.hasWhere(), "字面量里的 where 不是 WHERE 子句");
        assertEquals("t", stmt.tableName());
    }

    @Test
    @DisplayName("表名含 where 子串（anywhere）时仍能找到真正的 WHERE")
    void whereKeywordNeedsWordBoundary() {
        QueryStatement stmt = parser.parse("select * from anywhere where id = 1");

        assertEquals("anywhere", stmt.tableName());
        SimpleCondition cond = assertInstanceOf(SimpleCondition.class, stmt.condition());
        assertEquals("id", cond.field());
        assertEquals("1", cond.value());
    }

    @Test
    @DisplayName("普通 WHERE 的定位不受影响")
    void normalWhereStillFound() {
        QueryStatement stmt = parser.parse("select * from t where age > 25");

        assertTrue(stmt.hasWhere());
        assertEquals("age", ((SimpleCondition) stmt.condition()).field());
    }

    // ==================== 错误提示 ====================

    @Test
    @DisplayName("缺值时给出明确提示")
    void missingValueReported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t where age >"));

        assertTrue(e.getMessage().contains("缺少值"), e.getMessage());
    }

    @Test
    @DisplayName("缺字段名时给出明确提示")
    void missingFieldReported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t where = 30"));

        assertTrue(e.getMessage().contains("缺少列名"), e.getMessage());
    }
}