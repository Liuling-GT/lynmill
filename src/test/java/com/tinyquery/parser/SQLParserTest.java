package com.tinyquery.parser;

import com.tinyquery.parser.where.AndCondition;
import com.tinyquery.parser.where.NotCondition;
import com.tinyquery.parser.where.OrCondition;
import com.tinyquery.parser.where.SimpleCondition;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SQLParser 解析")
class SQLParserTest {

    private final SQLParser parser = new SQLParser();

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

        assertTrue(e.getMessage().contains("Unsupported operator"));
    }

    // ==================== LIMIT ====================

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
    @DisplayName("LIMIT offset 形式明确拒绝，不静默忽略 offset")
    void parseLimitOffsetRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t limit 10, 20"));

        assertTrue(e.getMessage().contains("offset"));
    }

    // ==================== ORDER BY ====================

    @Test
    @DisplayName("ORDER BY 缺省为升序")
    void parseOrderByDefaultAsc() {
        QueryStatement stmt = parser.parse("select * from t order by salary");

        assertTrue(stmt.hasOrderBy());
        assertEquals("salary", stmt.sort().column());
        assertFalse(stmt.sort().desc());
        assertEquals("t", stmt.tableName());
    }

    @Test
    @DisplayName("ORDER BY DESC")
    void parseOrderByDesc() {
        QueryStatement stmt = parser.parse("select * from t order by salary desc");

        assertTrue(stmt.sort().desc());
        assertEquals("salary", stmt.sort().column());
    }

    @Test
    @DisplayName("ORDER BY 显式 ASC 与缺省一致")
    void parseOrderByAsc() {
        QueryStatement stmt = parser.parse("select * from t order by salary asc");

        assertFalse(stmt.sort().desc());
    }

    // ==================== 三级剥离顺序（核心不变式） ====================

    @Test
    @DisplayName("WHERE + ORDER BY + LIMIT 同时出现：三者都正确剥离，表名不被污染")
    void parseAllClausesTogether() {
        QueryStatement stmt = parser.parse(
                "select name, salary from huge where city = '北京' order by salary desc limit 20");

        assertEquals("huge", stmt.tableName());
        assertArrayEquals(new String[]{"name", "salary"}, stmt.selectColumns());
        assertEquals("salary", stmt.sort().column());
        assertTrue(stmt.sort().desc());
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
        assertEquals("age", stmt.sort().column());
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
    @DisplayName("多列排序明确报错，不静默忽略第二列")
    void multiColumnOrderByRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t order by city, salary desc"));

        assertTrue(e.getMessage().contains("ORDER BY"));
    }

    @Test
    @DisplayName("ORDER BY 后跟多余内容报错，不污染表名")
    void malformedOrderByRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from t order by salary desc extra"));
    }

    // ==================== 缺失关键字 ====================

    @Test
    @DisplayName("缺少 FROM 报错")
    void missingFromThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("select * from"));

        assertTrue(e.getMessage().contains("Missing table name"));
    }

    @Test
    @DisplayName("缺少 SELECT 报错")
    void missingSelectThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("delete from t"));
    }

    // ==================== 已知缺陷 ====================

    @Disabled("已知缺陷：值内部含 order by 会被误判为排序子句。"
            + "ORDER_BY_PATTERN 锚定在语句末尾，无法区分引号内外的 order by。"
            + "修复方向：匹配前统计该位置之前的单引号个数，为奇数则视为引号内，不予剥离")
    @Test
    @DisplayName("值内部含 order by 不应被识别为排序子句（当前会误判）")
    void orderByInsideStringLiteral() {
        QueryStatement stmt = parser.parse("select * from t where note = 'a order by b'");

        assertFalse(stmt.hasOrderBy(), "引号内的 order by 不应被当作排序子句");
        assertEquals("t", stmt.tableName());
    }
}