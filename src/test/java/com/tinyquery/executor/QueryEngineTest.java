package com.tinyquery.executor;

import com.tinyquery.datasource.ValueNormalizer;
import com.tinyquery.i18n.Messages;
import com.tinyquery.parser.QueryHintException;
import com.tinyquery.parser.QueryStatement;
import com.tinyquery.parser.SQLParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QueryEngine 过滤 / 聚合 / 排序 / 分页")
class QueryEngineTest {

    private final SQLParser parser = new SQLParser();

    /**
     * 把语言钉死在中文 —— 本类有若干断言检查本地化后的报错原文
     * （「不是合法数字」「单引号未闭合」…）。理由同 {@code SQLParserTest}：
     * 被别的用例改了线程上的语言，就会得到与被测逻辑无关的红色失败。
     */
    @BeforeEach
    void pinChinese() {
        Messages.setCurrent(Messages.ZH);
    }

    @AfterEach
    void clearLocale() {
        Messages.clear();
    }

    // ==================== 测试数据与辅助 ====================

    //构造一行：row("name", "u1", "salary", 1000)
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    //按列清单批量建表
    private static ColumnStore store(String[] columns, Object[][] rows) {
        ColumnStore store = new ColumnStore();
        for (Object[] r : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < columns.length; i++) {
                map.put(columns[i], r[i]);
            }
            store.addRow(map);
        }
        return store;
    }

    //生成 n 行测试数据。salary 用固定公式打乱，避免输入有序时掩盖排序缺陷
    private static ColumnStore salaryStore(int n) {
        Object[][] rows = new Object[n][2];
        for (int i = 0; i < n; i++) {
            rows[i] = new Object[]{"u" + i, (i * 7919) % 10007};
        }
        return store(new String[]{"name", "salary"}, rows);
    }

    //执行分页查询
    private QueryResult page(ColumnStore store, String sql, int page, int pageSize) {
        QueryStatement stmt = parser.parse(sql);
        return new QueryEngine(store).executePage(stmt, page, pageSize);
    }

    //执行并只取结果行
    private List<String[]> rows(ColumnStore store, String sql) {
        return page(store, sql, 1, 1000).rows();
    }

    // ==================== 过滤 ====================

    @Test
    @DisplayName("无 WHERE 时命中的是全表行数")
    void filterWithoutWhere() {
        QueryResult result = page(salaryStore(30), "select * from t", 1, 10);

        assertEquals(30, result.totalRows());
        assertEquals(10, result.rows().size());
    }

    @Test
    @DisplayName("数值条件过滤")
    void filterNumeric() {
        ColumnStore store = store(
                new String[]{"name", "age"},
                new Object[][]{{"a", 20}, {"b", 30}, {"c", 40}});

        QueryResult result = page(store, "select name from t where age > 25", 1, 10);

        assertEquals(2, result.totalRows());
        assertEquals("b", result.rows().get(0)[0]);
        assertEquals("c", result.rows().get(1)[0]);
    }

    @Test
    @DisplayName("字符串条件过滤，大小写敏感")
    void filterStringIsCaseSensitive() {
        ColumnStore store = store(
                new String[]{"city"},
                new Object[][]{{"北京"}, {"上海"}, {"beijing"}});

        assertEquals(1, page(store, "select * from t where city = '北京'", 1, 10).totalRows());
        assertEquals(1, page(store, "select * from t where city = 'beijing'", 1, 10).totalRows());
        assertEquals(0, page(store, "select * from t where city = 'BEIJING'", 1, 10).totalRows());
    }

    @Test
    @DisplayName("小数按数值比较，不按字典序 —— '9' > '27.8' 的字典序陷阱")
    void filterDecimalUsesNumericOrder() {
        // 必须经过 ValueNormalizer，否则单元格是 String，
        // 会走字典序比较 —— 那正是这条测试要防的事
        ColumnStore store = store(
                new String[]{"rate"},
                new Object[][]{
                        {ValueNormalizer.normalize("9")},
                        {ValueNormalizer.normalize("27.8")},
                        {ValueNormalizer.normalize("10")},
                        {ValueNormalizer.normalize("100")},
                });

        // 数值比较：27.8 和 100 满足 > 9
        QueryResult result = page(store, "select * from t where rate > 9", 1, 10);

        assertEquals(3, result.totalRows());
    }

    @Test
    @DisplayName("NULL 参与任何比较都不命中，与 SQL 语义一致")
    void nullNeverMatches() {
        ColumnStore store = new ColumnStore();
        store.addRow(row("a", 1));
        store.addRow(row("a", null));

        assertEquals(1, page(store, "select * from t where a = 1", 1, 10).totalRows());
        assertEquals(0, page(store, "select * from t where a != 1", 1, 10).totalRows());
    }

    @Test
    @DisplayName("数值列与非法数字比较时报错，不静默返回空结果")
    void invalidNumberValueThrows() {
        ColumnStore store = salaryStore(10);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> rows(store, "select * from t where salary > abc"));

        assertTrue(e.getMessage().contains("不是合法数字"));
    }

    @Test
    @DisplayName("字符串列与任意文本比较不报错 —— 合法性检查只在遇到数值单元格时触发")
    void stringColumnAcceptsNonNumericValue() {
        ColumnStore store = store(new String[]{"name"}, new Object[][]{{"abc"}});

        assertEquals(1, page(store, "select * from t where name = 'abc'", 1, 10).totalRows());
        assertEquals(0, page(store, "select * from t where name = 'xyz'", 1, 10).totalRows());
    }

    @Test
    @DisplayName("引用不存在的列时报错")
    void unknownColumnThrows() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> rows(salaryStore(10), "select * from t where 没有这列 = 1"))
                .getMessage().contains("Unknown column"));
    }

    // ==================== 聚合 ====================

    @Test
    @DisplayName("count(*) 统计命中行数")
    void countStar() {
        List<String[]> result = rows(salaryStore(25), "select count(*) from t");

        assertEquals(1, result.size());
        assertEquals("25", result.get(0)[0]);
    }

    @Test
    @DisplayName("count(列) 只数非空值，与 count(*) 不同")
    void countColumnExcludesNull() {
        ColumnStore store = new ColumnStore();
        store.addRow(row("a", 1, "b", 10));
        store.addRow(row("a", 2, "b", 20));
        store.addRow(row("a", null, "b", 30));
        store.addRow(row("a", 4));          // b 缺失，被补成 null

        List<String[]> result = rows(store, "select count(*), count(a), count(b) from t");

        assertArrayEquals(new String[]{"4", "3", "3"}, result.get(0));
    }

    @Test
    @DisplayName("sum / avg / max / min 数值聚合")
    void numericAggregates() {
        ColumnStore store = store(
                new String[]{"v"},
                new Object[][]{{10}, {20}, {30}, {40}});

        List<String[]> result = rows(store, "select sum(v), avg(v), max(v), min(v) from t");

        assertArrayEquals(new String[]{"100", "25", "40", "10"}, result.get(0));
    }

    @Test
    @DisplayName("大数累加不溢出 int")
    void sumDoesNotOverflow() {
        ColumnStore store = store(
                new String[]{"v"},
                new Object[][]{{2_000_000_000}, {2_000_000_000}, {2_000_000_000}});

        List<String[]> result = rows(store, "select sum(v) from t");

        // int 累加会溢出成负数，BigDecimal 累加得到正确值
        assertEquals("6000000000", result.get(0)[0]);
    }

    @Test
    @DisplayName("空集聚合返回 0")
    void aggregateOnEmptySet() {
        List<String[]> result = rows(salaryStore(5),
                "select sum(salary), avg(salary), max(salary), min(salary) from t where salary > 999999");

        assertArrayEquals(new String[]{"0", "0", "0", "0"}, result.get(0));
    }

    @Test
    @DisplayName("文本列的 max / min 返回 0（已知行为，非字典序最值）")
    void aggregateOnTextColumnReturnsZero() {
        ColumnStore store = store(
                new String[]{"city"},
                new Object[][]{{"北京"}, {"上海"}});

        List<String[]> result = rows(store, "select max(city), min(city) from t");

        assertArrayEquals(new String[]{"0", "0"}, result.get(0));
    }

    @Test
    @DisplayName("聚合表头取表达式原文")
    void aggregateColumnNames() {
        QueryResult result = page(salaryStore(10), "select count(*), avg(salary) from t", 1, 50);

        assertEquals(List.of("count(*)", "avg(salary)"), result.columns());
    }

    @Test
    @DisplayName("聚合结果固定一行，不受分页与排序影响")
    void aggregateIgnoresPaging() {
        QueryResult result = page(salaryStore(100),
                "select count(*) from t order by salary", 5, 50);

        // totalRows 是「结果集的行数」，聚合永远只有 1 行，
        // 不是参与聚合的源数据行数
        assertEquals(1, result.rows().size());
        assertEquals(1, result.totalRows());
        assertEquals("100", result.rows().get(0)[0]);
        assertFalse(result.hasMore());
    }

    // ==================== 排序：核心不变式 ====================

    @Test
    @DisplayName("降序排序结果严格降序，首行是全表最大值")
    void orderByDescIsCorrect() {
        ColumnStore store = salaryStore(100);

        List<String[]> result = rows(store, "select * from t order by salary desc limit 100");

        assertEquals(100, result.size());
        for (int i = 1; i < result.size(); i++) {
            int prev = Integer.parseInt(result.get(i - 1)[1]);
            int cur = Integer.parseInt(result.get(i)[1]);
            assertTrue(prev >= cur, "第 " + i + " 行破坏了降序：" + prev + " < " + cur);
        }

        int max = 0;
        for (Object v : store.getColumns("salary")) {
            max = Math.max(max, (int) v);
        }
        assertEquals(String.valueOf(max), result.get(0)[1]);
    }

    /**
     * 这是整个测试套件里最重要的一条
     * sortAndLimit 有两条分支：命中行数不超过上限时走整体排序，超过时走有界堆
     * 有界堆的淘汰方向写反过一次（compare > 0 写成 < 0），不报错、不影响编译
     * 只是静默选出最差的一批行。这条测试用「整体排序」当基准去校验「有界堆」
     */
    @Test
    @DisplayName("有界堆路径必须与整体排序路径给出相同的前 N 行")
    void topNHeapMatchesFullSort() {
        ColumnStore store = salaryStore(100);

        // 命中 100 行 > limit 10，走有界堆
        List<String[]> heapRows = rows(store, "select * from t order by salary desc limit 10");

        // 命中 100 行 = limit 100，不超过上限，走整体排序（作为可信基准）
        List<String[]> baseRows = rows(store, "select * from t order by salary desc limit 100");

        assertEquals(10, heapRows.size());
        for (int i = 0; i < 10; i++) {
            assertArrayEquals(baseRows.get(i), heapRows.get(i),
                    "第 " + i + " 行与整体排序不一致，有界堆选错了行");
        }
    }

    @Test
    @DisplayName("升序的有界堆同样正确")
    void topNHeapAscending() {
        ColumnStore store = salaryStore(100);

        List<String[]> heapRows = rows(store, "select * from t order by salary asc limit 5");
        List<String[]> baseRows = rows(store, "select * from t order by salary asc limit 100");

        for (int i = 0; i < 5; i++) {
            assertArrayEquals(baseRows.get(i), heapRows.get(i));
        }
    }

    @Test
    @DisplayName("limit 0 返回空集且不抛异常（有界堆的容量边界）")
    void orderByWithLimitZero() {
        QueryResult result = page(salaryStore(50), "select * from t order by salary desc limit 0", 1, 50);

        assertEquals(0, result.totalRows());
        assertTrue(result.rows().isEmpty());
    }

    @Test
    @DisplayName("命中行数恰好等于上限时走稳定排序，不进入堆路径")
    void orderByExactlyAtLimit() {
        ColumnStore store = salaryStore(10);

        List<String[]> result = rows(store, "select * from t order by salary desc limit 10");
        List<String[]> base = rows(store, "select * from t order by salary desc limit 100");

        assertEquals(10, result.size());
        for (int i = 0; i < 10; i++) {
            assertArrayEquals(base.get(i), result.get(i));
        }
    }

    @Test
    @DisplayName("混合类型排序：数值在前，文本在后，null 最后")
    void orderByMixedTypes() {
        ColumnStore store = new ColumnStore();
        store.addRow(row("v", "text"));
        store.addRow(row("v", 5));
        store.addRow(row("v", null));
        store.addRow(row("v", 1));

        List<String[]> result = rows(store, "select * from t order by v");

        assertEquals("1", result.get(0)[0]);
        assertEquals("5", result.get(1)[0]);
        assertEquals("text", result.get(2)[0]);
        assertEquals("null", result.get(3)[0]);   // String.valueOf(null) 的形态
    }

    // ==================== 分页 ====================

    @Test
    @DisplayName("分页切片正确：相邻页无重复，拼接后整体有序")
    void pagesDoNotOverlap() {
        ColumnStore store = salaryStore(100);
        String sql = "select * from t order by salary desc";

        QueryResult p1 = page(store, sql, 1, 30);
        QueryResult p2 = page(store, sql, 2, 30);
        QueryResult p3 = page(store, sql, 3, 30);
        QueryResult p4 = page(store, sql, 4, 30);

        assertEquals(100, p1.totalRows());
        assertEquals(30, p1.rows().size());
        assertEquals(30, p2.rows().size());
        assertEquals(30, p3.rows().size());
        assertEquals(10, p4.rows().size());

        // 无重复
        Set<String> seen = new HashSet<>();
        for (QueryResult p : List.of(p1, p2, p3, p4)) {
            for (String[] r : p.rows()) {
                assertTrue(seen.add(r[0]), "行重复出现在多页：" + r[0]);
            }
        }
        assertEquals(100, seen.size());

        // 拼接后整体降序
        List<String[]> all = new ArrayList<>();
        for (QueryResult p : List.of(p1, p2, p3, p4)) {
            all.addAll(p.rows());
        }
        for (int i = 1; i < all.size(); i++) {
            int prev = Integer.parseInt(all.get(i - 1)[1]);
            int cur = Integer.parseInt(all.get(i)[1]);
            assertTrue(prev >= cur, "跨页拼接后破坏了降序");
        }
    }

    /**
     * 分页路径曾经完全忽略 SQL 里的 LIMIT：select ... limit 10 会返回整表，
     * 且没有任何报错。这条测试锁住这个行为。
     */
    @Test
    @DisplayName("分页路径下 LIMIT 仍然生效，且优先于 pageSize")
    void limitAppliesOnPaginatedPath() {
        ColumnStore store = salaryStore(100);

        // pageSize 远大于 limit
        QueryResult result = page(store, "select * from t limit 5", 1, 50);

        assertEquals(5, result.totalRows());
        assertEquals(5, result.rows().size());
        assertFalse(result.hasMore());
    }

    @Test
    @DisplayName("LIMIT 与分页叠加：总页数按 LIMIT 收窄后的行数计算")
    void limitNarrowsTotalPages() {
        ColumnStore store = salaryStore(100);

        QueryResult p1 = page(store, "select * from t limit 20", 1, 15);
        QueryResult p2 = page(store, "select * from t limit 20", 2, 15);

        assertEquals(20, p1.totalRows());
        assertEquals(15, p1.rows().size());
        assertTrue(p1.hasMore());
        assertEquals(5, p2.rows().size());
        assertFalse(p2.hasMore());
    }

    @Test
    @DisplayName("LIMIT 生效时排序范围同步收窄，返回的是全局前 N 个")
    void limitNarrowsSortRange() {
        ColumnStore store = salaryStore(100);

        // 分页取第 1 页 10 行，但 limit 50 —— 排序必须覆盖到第 50 名
        List<String[]> paged = page(store, "select * from t order by salary desc limit 50", 1, 10).rows();
        List<String[]> base = rows(store, "select * from t order by salary desc limit 100");

        for (int i = 0; i < 10; i++) {
            assertArrayEquals(base.get(i), paged.get(i));
        }
    }

    @Test
    @DisplayName("页码超出范围返回空页而不是报错")
    void pageOutOfRangeReturnsEmpty() {
        QueryResult result = page(salaryStore(10), "select * from t", 99, 50);

        assertEquals(10, result.totalRows());
        assertTrue(result.rows().isEmpty());
        assertFalse(result.hasMore());
    }

    @Test
    @DisplayName("hasMore 在最后一页为 false")
    void hasMoreFalseOnLastPage() {
        ColumnStore store = salaryStore(60);

        assertTrue(page(store, "select * from t", 1, 30).hasMore());
        assertFalse(page(store, "select * from t", 2, 30).hasMore());
    }

    // ==================== 表头与空结果 ====================

    @Test
    @DisplayName("select * 的表头来自 ColumnStore，空结果也照常返回")
    void starColumnsEvenWhenEmpty() {
        QueryResult result = page(salaryStore(10), "select * from t where salary > 999999", 1, 50);

        assertEquals(0, result.totalRows());
        assertTrue(result.rows().isEmpty());
        assertEquals(List.of("name", "salary"), result.columns());
    }

    @Test
    @DisplayName("显式列的表头取自 SELECT 列表")
    void explicitColumnNames() {
        QueryResult result = page(salaryStore(5), "select name from t", 1, 50);

        assertEquals(List.of("name"), result.columns());
    }

    // ==================== 导出路径 ====================

    @Test
    @DisplayName("listRows 返回全部命中行且保持排序，不受分页影响")
    void listRowsReturnsAllInOrder() {
        ColumnStore store = salaryStore(100);
        QueryStatement stmt = parser.parse("select * from t order by salary desc");

        List<Integer> indexes = new QueryEngine(store).listRows(stmt);

        assertEquals(100, indexes.size());

        List<Object> salary = store.getColumns("salary");
        for (int i = 1; i < indexes.size(); i++) {
            int prev = (int) salary.get(indexes.get(i - 1));
            int cur = (int) salary.get(indexes.get(i));
            assertTrue(prev >= cur, "listRows 未保持排序");
        }
    }

    @Test
    @DisplayName("listRows 与分页查询的行序一致 —— 先看后导两边结果不能对不上")
    void listRowsMatchesPagedQuery() {
        ColumnStore store = salaryStore(100);
        QueryStatement stmt = parser.parse("select * from t order by salary desc");

        List<Integer> exported = new QueryEngine(store).listRows(stmt);
        List<String[]> viewed = page(store, "select * from t order by salary desc limit 100", 1, 100).rows();

        List<Object> names = store.getColumns("name");

        assertEquals(viewed.size(), exported.size());
        for (int i = 0; i < viewed.size(); i++) {
            // 导出的第 i 个行号，其 name 列应等于界面第 i 行的第一列
            assertEquals(names.get(exported.get(i)), viewed.get(i)[0]);
        }
    }

    // ==================== 列类型推断：空串与 null 必须一视同仁 ====================
    // 这些用例锁住一个语义：inferType 判「有没有值」必须和 isPresent 一致。
    // 早先 inferType 只跳 null，列首一个空串就把整列钉成 string，
    // 前端因此不把该数值列列为可选度量，图表也按文本分组画。

    @Test
    @DisplayName("列首是空串时，数值列仍应推断为 number")
    void inferTypeSkipsEmptyStringAtHead() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", ""},
                {"r2", ""},
                {"r3", 100},
                {"r4", 200},
        });

        QueryResult result = page(store, "select * from t", 1, 100);

        assertEquals("number", result.columnTypes().get(1),
                "空串应和 null 一样被跳过，不能把整列钉成 string");
    }

    @Test
    @DisplayName("列首是 null 时数值列推断为 number（对照，行为不变）")
    void inferTypeSkipsNullAtHead() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", null},
                {"r2", null},
                {"r3", 100},
                {"r4", 200},
        });

        QueryResult result = page(store, "select * from t", 1, 100);

        assertEquals("number", result.columnTypes().get(1));
    }

    @Test
    @DisplayName("null 与空串混在列首时，数值列仍推断为 number")
    void inferTypeSkipsMixedEmptyAtHead() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", null},
                {"r2", ""},
                {"r3", 100},
                {"r4", 200},
        });

        QueryResult result = page(store, "select * from t", 1, 100);

        assertEquals("number", result.columnTypes().get(1),
                "null 在前也挡不住后面那个空串把整列判成 string");
    }

    @Test
    @DisplayName("纯空格串也算无值")
    void inferTypeSkipsBlankString() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", "   "},
                {"r2", 100},
                {"r3", 200},
        });

        QueryResult result = page(store, "select * from t", 1, 100);

        assertEquals("number", result.columnTypes().get(1));
    }

    @Test
    @DisplayName("整列都是空串时推断为 empty，而不是 string")
    void inferTypeAllEmptyStringsIsEmpty() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", ""},
                {"r2", null},
        });

        QueryResult result = page(store, "select * from t", 1, 100);

        assertEquals("empty", result.columnTypes().get(1),
                "全空列没有类型可言，不该谎报成 string");
    }

    @Test
    @DisplayName("整列都是 null 时推断为 empty（对照，行为不变）")
    void inferTypeAllNullsIsEmpty() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", null},
                {"r2", null},
        });

        QueryResult result = page(store, "select * from t", 1, 100);

        assertEquals("empty", result.columnTypes().get(1));
    }

    @Test
    @DisplayName("空串之后是字符串时仍推断为 string，不被误判成 number")
    void inferTypeEmptyThenStringIsString() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", ""},
                {"r2", "abc"},
        });

        QueryResult result = page(store, "select * from t", 1, 100);

        assertEquals("string", result.columnTypes().get(1));
    }

    @Test
    @DisplayName("列首空串的数值列在图表里走分桶，而不是按文本分组")
    void chartBucketsNumericColumnWithEmptyHead() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", ""},
                {"r2", ""},
                {"r3", 100},
                {"r4", 200},
        });
        QueryStatement stmt = parser.parse("select * from t");

        ChartResult chart = new QueryEngine(store)
                .aggregateForChart(stmt, "v", List.of(), "count", 10);

        assertTrue(chart.bucketed(),
                "数值维度应走分桶；走成文本分组说明列类型判错了");
    }

    @Test
    @DisplayName("文本列仍走分组，不被误判成数值列（对照）")
    void chartGroupsTextColumn() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", ""},
                {"r2", "abc"},
                {"r3", "def"},
        });
        QueryStatement stmt = parser.parse("select * from t");

        ChartResult chart = new QueryEngine(store)
                .aggregateForChart(stmt, "v", List.of(), "count", 10);

        assertFalse(chart.bucketed(), "文本列不该走分桶");
    }

    // ==================== 图表聚合：A 分组与「其他」 ====================
    // 图表聚合的分组键来自界面下拉框，不是 SQL 的 GROUP BY，
    // 整条链路约 340 行，此前只有两条 bucketed() 布尔断言

    //部门 -> 工资的表，用于分组类断言。
    //各组之和刻意取成互不相同，避免同值时的稳定顺序掩盖真正的排序缺陷
    private static ColumnStore deptSalaryStore() {
        return store(new String[]{"dept", "salary"}, new Object[][]{
                {"A", 100}, {"A", 200},
                {"B", 300},
                {"C", 400}, {"C", 500}, {"C", 600},
                {"D", 700},
        });
    }

    private ChartResult chart(ColumnStore store, String dimension,
                              List<String> measures, String aggregation, Integer topN) {
        QueryStatement stmt = parser.parse("select * from t");
        return new QueryEngine(store).aggregateForChart(stmt, dimension, measures, aggregation, topN);
    }

    @Test
    @DisplayName("A1 按文本维度分组，类别按第一个度量降序、各组聚合值正确")
    void chartGroupsByDimension() {
        ChartResult result = chart(deptSalaryStore(), "dept", List.of("salary"), "sum", 10);

        // 各组之和 C=1500 > D=700 > B=300 > A=300。
        // A 与 B 同值，稳定排序保持首次出现顺序，故 A 在 B 前
        assertEquals(List.of("C", "D", "A", "B"), result.categories());
        assertEquals(List.of(new java.math.BigDecimal("1500"), new java.math.BigDecimal("700"),
                new java.math.BigDecimal("300"), new java.math.BigDecimal("300")),
                result.series().get(0).values());
        assertFalse(result.truncated());
        assertEquals(4, result.totalGroups());
    }

    @Test
    @DisplayName("A2 分组数超 topN 时把尾部合并为「其他」，并如实上报 truncated/totalGroups")
    void chartMergesTailIntoOther() {
        ChartResult result = chart(deptSalaryStore(), "dept", List.of("salary"), "sum", 2);

        // 取前 2 组（C、D），其余（B、A）并入「其他」
        assertEquals(List.of("C", "D", ChartResult.OTHER), result.categories());
        assertTrue(result.truncated(), "发生合并时必须上报，前端要据此提示");
        assertEquals(4, result.totalGroups(), "totalGroups 是合并前的分组总数");
    }

    @Test
    @DisplayName("A3 「其他」的值等于被合并各组之和 —— 不丢数据")
    void chartOtherEqualsSumOfMergedGroups() {
        ChartResult result = chart(deptSalaryStore(), "dept", List.of("salary"), "sum", 2);

        List<java.math.BigDecimal> values = result.series().get(0).values();
        java.math.BigDecimal other = values.get(values.size() - 1);

        // 被合并的是 A(300) 与 B(300)
        assertEquals(0, new java.math.BigDecimal("600").compareTo(other),
                "「其他」必须是剩余组之和，否则总值对不上、用户会以为数据少了");

        // 总额守恒：所有柱子加起来等于全表总和
        java.math.BigDecimal total = values.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertEquals(0, new java.math.BigDecimal("2800").compareTo(total), "合并前后总值必须守恒");
    }

    @Test
    @DisplayName("A4 维度为空值时归到「(空)」标签，不丢行")
    void chartEmptyDimensionBecomesEmptyLabel() {
        ColumnStore store = store(new String[]{"dept", "salary"}, new Object[][]{
                {"A", 100},
                {"", 200},
                {null, 300},
        });

        ChartResult result = chart(store, "dept", List.of("salary"), "sum", 10);

        assertTrue(result.categories().contains("(空)"), "空串与 null 都应落到 (空)：" + result.categories());
        List<java.math.BigDecimal> values = result.series().get(0).values();
        int at = result.categories().indexOf("(空)");
        assertEquals(0, new java.math.BigDecimal("500").compareTo(values.get(at)),
                "空串与 null 两行都要计入，不能被丢掉");
    }

    @Test
    @DisplayName("A5 分组顺序稳定：同值时按首次出现顺序，不随机")
    void chartGroupOrderIsStable() {
        ColumnStore store = store(new String[]{"dept", "salary"}, new Object[][]{
                {"X", 100}, {"Y", 100}, {"Z", 100},
        });

        ChartResult first = chart(store, "dept", List.of("salary"), "sum", 10);
        ChartResult second = chart(store, "dept", List.of("salary"), "sum", 10);

        assertEquals(first.categories(), second.categories(), "两次聚合顺序必须一致");
        assertEquals(List.of("X", "Y", "Z"), first.categories(), "同值时应保持首次出现顺序");
    }

    // ==================== 图表聚合：B 度量聚合方式 ====================

    @Test
    @DisplayName("B1 sum / max / min / count 各自算对（单组便于核对）")
    void chartAggregationModes() {
        ColumnStore store = store(new String[]{"dept", "salary"}, new Object[][]{
                {"A", 100}, {"A", 200}, {"A", 300},
        });

        assertEquals(0, new java.math.BigDecimal("600").compareTo(
                chart(store, "dept", List.of("salary"), "sum", 10).series().get(0).values().get(0)));
        assertEquals(0, new java.math.BigDecimal("300").compareTo(
                chart(store, "dept", List.of("salary"), "max", 10).series().get(0).values().get(0)));
        assertEquals(0, new java.math.BigDecimal("100").compareTo(
                chart(store, "dept", List.of("salary"), "min", 10).series().get(0).values().get(0)));
        assertEquals(0, new java.math.BigDecimal("3").compareTo(
                chart(store, "dept", List.of(), "count", 10).series().get(0).values().get(0)));
    }

    @Test
    @DisplayName("B2 avg 取算术平均")
    void chartAvg() {
        ColumnStore store = store(new String[]{"dept", "salary"}, new Object[][]{
                {"A", 100}, {"A", 200}, {"A", 300},
        });

        java.math.BigDecimal avg = chart(store, "dept", List.of("salary"), "avg", 10)
                .series().get(0).values().get(0);

        assertEquals(0, new java.math.BigDecimal("200").compareTo(avg));
    }

    @Test
    @DisplayName("B3 count 模式不需要度量列，数的是行数")
    void chartCountIgnoresMeasures() {
        ChartResult result = chart(deptSalaryStore(), "dept", List.of(), "count", 10);

        // C 组 3 行、其余各 1 行；降序后 C 在最前
        assertEquals("C", result.categories().get(0));
        assertEquals(0, new java.math.BigDecimal("3").compareTo(result.series().get(0).values().get(0)));
        assertEquals("计数", result.series().get(0).name(), "count 模式的系列名固定为 计数");
    }

    @Test
    @DisplayName("B4 非数值单元格只计行数，不参与求和（与 SQL 聚合口径一致）")
    void chartNonNumericCellsExcludedFromSum() {
        ColumnStore store = store(new String[]{"dept", "salary"}, new Object[][]{
                {"A", 100},
                {"A", "abc"},
                {"A", ""},
                {"A", 200},
        });

        // sum 只累加两个数值
        assertEquals(0, new java.math.BigDecimal("300").compareTo(
                chart(store, "dept", List.of("salary"), "sum", 10).series().get(0).values().get(0)));
        // avg 的分母是「参与累加的单元格数」=2，不是行数 4
        assertEquals(0, new java.math.BigDecimal("150").compareTo(
                chart(store, "dept", List.of("salary"), "avg", 10).series().get(0).values().get(0)),
                "avg 分母必须与 sum 同源，否则平均值被拉低");
        // count 数的是行数，与非数值无关
        assertEquals(0, new java.math.BigDecimal("4").compareTo(
                chart(store, "dept", List.of(), "count", 10).series().get(0).values().get(0)));
    }

    @Test
    @DisplayName("B5 avg 合并「其他」时按行数加权，不是把平均值直接相加")
    void chartAvgMergeIsWeighted() {
        // 「其他」里必须有两组以上、且行数不同，才能真正区分加权与均值相加。
        // 只合并一组时权重恒等于 1，测不出区别。
        //
        // 四组，行数同为 1，因此排序由各组之和决定：D=400 > C=300 > B=200 > A=100。
        // topN=2 留下 C、D，把 A、B 并入「其他」。
        //   A 组：1 行，值 100        均值 100
        //   B 组：3 行，值 200/200/200 均值 200
        //   加权平均 = (100*1 + 200*3) / 4 = 175
        //   若错把均值直接相加 = 100 + 200 = 300
        ColumnStore store = store(new String[]{"dept", "salary"}, new Object[][]{
                {"A", 100},
                {"B", 200}, {"B", 200}, {"B", 200},
                {"C", 300},
                {"D", 400},
        });

        ChartResult result = chart(store, "dept", List.of("salary"), "avg", 2);

        assertEquals(List.of("D", "C", ChartResult.OTHER), result.categories());
        java.math.BigDecimal other = result.series().get(0).values().get(2);

        assertEquals(0, new java.math.BigDecimal("175").compareTo(other),
                "「其他」的 avg 必须按单元格数加权（应为 175）；"
                        + "若写成均值相加会得到 300，若只按组数平均会得到 150");
    }

    // ==================== 图表聚合：C 多系列 ====================

    @Test
    @DisplayName("C1 两个度量各成一系列，值顺序与类别一一对应")
    void chartTwoMeasuresAligned() {
        ColumnStore store = store(new String[]{"dept", "salary", "bonus"}, new Object[][]{
                {"A", 100, 1},
                {"A", 200, 2},
                {"B", 300, 3},
        });

        ChartResult result = chart(store, "dept", List.of("salary", "bonus"), "sum", 10);

        assertEquals(2, result.series().size(), "两个度量应产出两条系列");
        assertEquals("sum(salary)", result.series().get(0).name());
        assertEquals("sum(bonus)", result.series().get(1).name());

        // 类别顺序 B(300) > A(300)? A 与 B 的 salary 都是 300，按首次出现 A 在前
        int atB = result.categories().indexOf("B");
        int atA = result.categories().indexOf("A");
        assertEquals(0, new java.math.BigDecimal("300").compareTo(result.series().get(0).values().get(atB)));
        assertEquals(0, new java.math.BigDecimal("300").compareTo(result.series().get(0).values().get(atA)));
        // 每条系列的长度都必须与类别数一致，否则柱子对不上位
        for (ChartSeries s : result.series()) {
            assertEquals(result.categories().size(), s.values().size(), "系列长度必须等于类别数");
        }
    }

    @Test
    @DisplayName("C2 多系列只按第一个度量排序 —— 各排各的会让柱子错位")
    void chartMultiSeriesShareOneOrder() {
        // 按 salary 排是 A 在前，按 bonus 排是 B 在前。
        // 若两个系列各排各的，图就废了
        ColumnStore store = store(new String[]{"dept", "salary", "bonus"}, new Object[][]{
                {"A", 900, 1},
                {"B", 100, 900},
        });

        ChartResult result = chart(store, "dept", List.of("salary", "bonus"), "sum", 10);

        assertEquals(List.of("A", "B"), result.categories(), "排序口径是第一个度量 salary");
        // 两个系列必须都按 A,B 的顺序给值
        assertEquals(0, new java.math.BigDecimal("900").compareTo(result.series().get(0).values().get(0)));
        assertEquals(0, new java.math.BigDecimal("1").compareTo(result.series().get(1).values().get(0)));
        assertEquals(0, new java.math.BigDecimal("100").compareTo(result.series().get(0).values().get(1)));
        assertEquals(0, new java.math.BigDecimal("900").compareTo(result.series().get(1).values().get(1)),
                "第二系列必须跟着同一套顺序，不能自己重排");
    }

    @Test
    @DisplayName("C3 多系列时「其他」的每个系列都要合并到")
    void chartOtherMergesEverySeries() {
        ColumnStore store = store(new String[]{"dept", "salary", "bonus"}, new Object[][]{
                {"A", 100, 1},
                {"B", 200, 2},
                {"C", 300, 3},
        });

        ChartResult result = chart(store, "dept", List.of("salary", "bonus"), "sum", 2);

        assertEquals(List.of("C", "B", ChartResult.OTHER), result.categories());
        assertEquals(2, result.series().size());
        for (ChartSeries s : result.series()) {
            assertEquals(3, s.values().size(), "「其他」列在每条系列里都要有值");
        }
        // 被合并的是 A，两个系列都要并入 A 的值
        assertEquals(0, new java.math.BigDecimal("100").compareTo(
                result.series().get(0).values().get(2)), "salary 的「其他」= A 的 100");
        assertEquals(0, new java.math.BigDecimal("1").compareTo(
                result.series().get(1).values().get(2)), "bonus 的「其他」也必须并入，不能只并第一个");
    }

    // ==================== 图表聚合：D 数值分桶 ====================

    @Test
    @DisplayName("D1 数值维度分桶，标签是整数区间且格式无浮点误差")
    void chartBucketLabelsAreClean() {
        Object[][] rows = new Object[20][2];
        for (int i = 0; i < 20; i++) {
            rows[i] = new Object[]{"r" + i, i};
        }
        ColumnStore store = store(new String[]{"name", "v"}, rows);

        ChartResult result = chart(store, "v", List.of(), "count", 10);

        assertTrue(result.bucketed());
        for (String c : result.categories()) {
            assertTrue(c.matches("-?\\d+-\\d+"),
                    "桶标签必须是 整数-整数，实际：" + c + "（出现小数说明中途用了 double）");
        }
    }

    @Test
    @DisplayName("D2 最大值必须落进最后一桶 —— 不能被静默丢掉")
    void chartMaxValueLandsInLastBucket() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", 0}, {"r2", 50}, {"r3", 100},
        });

        ChartResult result = chart(store, "v", List.of(), "count", 10);

        // 三行都要落在某个桶里，总数不能少
        int total = result.series().get(0).values().stream()
                .mapToInt(java.math.BigDecimal::intValue).sum();
        assertEquals(3, total, "最大值 100 若落不进任何桶，总数会变成 2");
    }

    @Test
    @DisplayName("D3 空桶保留不删 —— 直方图要能看出「这段没有数据」")
    void chartKeepsEmptyBuckets() {
        // 0 与 100 之间跨 10 段，中间必然有空桶
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", 0}, {"r2", 100},
        });

        ChartResult result = chart(store, "v", List.of(), "count", 10);
        List<java.math.BigDecimal> values = result.series().get(0).values();

        assertTrue(values.size() >= 3, "应保留中间的空桶，实际只有 " + values.size() + " 段");
        assertTrue(values.stream().anyMatch(v -> v.signum() == 0), "中间应存在值为 0 的空桶");
    }

    @Test
    @DisplayName("D4 桶数不超过上限（防止极差过大时生成上千段）")
    void chartBucketCountIsCapped() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", 0}, {"r2", 100000000},
        });

        ChartResult result = chart(store, "v", List.of(), "count", 10);

        assertTrue(result.categories().size() <= 30,
                "桶数必须有上限，实际 " + result.categories().size() + " 段");
    }

    @Test
    @DisplayName("D5 维度全同值时退化为单桶，不崩也不空图")
    void chartSingleValueDimension() {
        ColumnStore store = store(new String[]{"name", "v"}, new Object[][]{
                {"r1", 7}, {"r2", 7}, {"r3", 7},
        });

        ChartResult result = chart(store, "v", List.of(), "count", 10);

        assertTrue(result.bucketed());
        assertEquals(1, result.categories().size(), "min == max 时应退化成单桶");
        assertEquals(0, new java.math.BigDecimal("3").compareTo(result.series().get(0).values().get(0)));
    }

    // ==================== 图表聚合：E 参数边界 ====================

    @Test
    @DisplayName("E1 topN 为 null / 0 / 负数时一律回落到默认 50，不截断")
    void chartTopNInvalidFallsBackToDefault() {
        // 造 60 个分组：默认 50 会触发「其他」，传 0 或负数也必须一样
        Object[][] rows = new Object[60][2];
        for (int i = 0; i < 60; i++) {
            rows[i] = new Object[]{"g" + i, i};
        }
        ColumnStore store = store(new String[]{"dept", "salary"}, rows);

        for (Integer topN : new Integer[]{null, 0, -5}) {
            ChartResult result = chart(store, "dept", List.of("salary"), "sum", topN);
            assertEquals(51, result.categories().size(),
                    "topN=" + topN + " 应回落默认 50，得到 50 组 + 其他");
            assertTrue(result.truncated());
        }

        // 显式传大值时才取到全部
        ChartResult all = chart(store, "dept", List.of("salary"), "sum", 100);
        assertEquals(60, all.categories().size());
        assertFalse(all.truncated());
    }

    @Test
    @DisplayName("E2 维度列不存在时报错（不是静默空图）")
    void chartUnknownDimensionFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> chart(deptSalaryStore(), "nope", List.of("salary"), "sum", 10));

        assertTrue(e.getMessage().contains("Unknown column"), e.getMessage());
    }

    // ==================== LIMIT / OFFSET ====================
    // 语义：先跳过 offset 行，再取至多 limit 行。分页在这个结果集内翻。

    // id 依次为 1..n 的表，便于直接断言「取了哪几行」
    private static ColumnStore idStore(int n) {
        Object[][] rows = new Object[n][1];
        for (int i = 0; i < n; i++) {
            rows[i] = new Object[]{i + 1};
        }
        return store(new String[]{"id"}, rows);
    }

    private static List<String> firstColumn(List<String[]> rows) {
        List<String> list = new ArrayList<>();
        for (String[] r : rows) {
            list.add(r[0]);
        }
        return list;
    }

    @Test
    @DisplayName("offset 跳过前 m 行，从第 m+1 行开始取")
    void offsetSkipsRows() {
        QueryResult result = page(idStore(10), "select * from t limit 3 offset 2", 1, 50);

        assertEquals(3, result.totalRows());
        assertEquals(List.of("3", "4", "5"), firstColumn(result.rows()));
    }

    @Test
    @DisplayName("单独的 offset：跳过 m 行，剩余全部返回")
    void offsetWithoutLimit() {
        QueryResult result = page(idStore(10), "select * from t offset 7", 1, 50);

        assertEquals(3, result.totalRows());
        assertEquals(List.of("8", "9", "10"), firstColumn(result.rows()));
    }

    @Test
    @DisplayName("offset 超出总行数时返回空结果，不是报错")
    void offsetBeyondEndIsEmpty() {
        QueryResult result = page(idStore(10), "select * from t limit 3 offset 99", 1, 50);

        assertEquals(0, result.totalRows());
        assertTrue(result.rows().isEmpty());
        assertFalse(result.hasMore());
    }

    @Test
    @DisplayName("offset 0 与不写 offset 结果完全一致")
    void offsetZeroEqualsAbsent() {
        List<String[]> withZero = page(idStore(10), "select * from t limit 4 offset 0", 1, 50).rows();
        List<String[]> without = page(idStore(10), "select * from t limit 4", 1, 50).rows();

        assertEquals(firstColumn(without), firstColumn(withZero));
    }

    @Test
    @DisplayName("offset 跳过的必须是排序后的行，不是原表顺序")
    void offsetAppliesAfterOrderBy() {
        // salary 用公式打乱，原表顺序与 salary 升序不同，能区分「先排后跳」和「先跳后排」
        ColumnStore s = salaryStore(20);
        QueryResult result = page(s, "select name from t order by salary asc limit 3 offset 2", 1, 50);
        List<String[]> all = page(s, "select name from t order by salary asc", 1, 1000).rows();

        assertEquals(firstColumn(all.subList(2, 5)), firstColumn(result.rows()));
    }

    @Test
    @DisplayName("totalRows 是跳过 offset 之后的行数，前端据此算总页数")
    void totalRowsExcludesOffset() {
        QueryResult result = page(idStore(10), "select * from t offset 6", 1, 2);

        assertEquals(4, result.totalRows(), "跳过 6 行后还剩 4 行");
        assertEquals(2, result.rows().size());
        assertTrue(result.hasMore());
    }

    @Test
    @DisplayName("offset 与界面分页叠加：第 2 页从 offset + pageSize 处开始")
    void offsetCombinesWithPagination() {
        // offset 4 之后是 [5,6,7,8,9,10]；每页 2 行，第 2 页应为 [7,8]
        QueryResult result = page(idStore(10), "select * from t offset 4", 2, 2);

        assertEquals(6, result.totalRows());
        assertEquals(List.of("7", "8"), firstColumn(result.rows()));
    }

    @Test
    @DisplayName("offset + order by + 分页三者叠加")
    void offsetWithOrderByAndPagination() {
        ColumnStore s = salaryStore(20);
        List<String[]> all = page(s, "select name from t order by salary desc", 1, 1000).rows();

        // 结果集 = 升序排列后的第 4..13 行（offset 3 + limit 10）；第 2 页每页 4 行 → 结果集内第 5..8 行
        QueryResult result = page(s, "select name from t order by salary desc limit 10 offset 3", 2, 4);

        assertEquals(10, result.totalRows(), "limit 10 是结果集上限，不受 offset 影响");
        assertEquals(firstColumn(all.subList(7, 11)), firstColumn(result.rows()));
    }

    @Test
    @DisplayName("类型推断只看结果集：被 offset 跳过的行不参与抽样")
    void typeInferenceIgnoresSkippedRows() {
        // 前 3 行是文本，后 3 行是数字。offset 3 之后结果集全是数字
        ColumnStore s = store(new String[]{"v"}, new Object[][]{
                {"abc"}, {"def"}, {"ghi"}, {100}, {200}, {300},
        });

        QueryResult result = page(s, "select * from t limit 3 offset 3", 1, 50);

        assertEquals("number", result.columnTypes().get(0),
                "被跳过的文本行不该把结果集判成 string");
    }

    @Test
    @DisplayName("导出遵循 LIMIT / OFFSET，与界面看到的是同一段")
    void listRowsRespectsLimitAndOffset() {
        ColumnStore s = idStore(10);
        QueryStatement stmt = parser.parse("select * from t limit 3 offset 2");

        List<Integer> exported = new QueryEngine(s).listRows(stmt);
        List<String[]> viewed = page(s, "select * from t limit 3 offset 2", 1, 50).rows();

        assertEquals(viewed.size(), exported.size(), "导出行数必须与界面一致");
        List<Object> idColumn = s.getColumns("id");
        for (int i = 0; i < viewed.size(); i++) {
            assertEquals(String.valueOf(idColumn.get(exported.get(i))), viewed.get(i)[0]);
        }
    }

    @Test
    @DisplayName("导出遵循 order by + offset，行序与界面一致")
    void listRowsRespectsOrderByWithOffset() {
        ColumnStore s = salaryStore(20);
        QueryStatement stmt = parser.parse("select * from t order by salary desc limit 5 offset 4");

        List<Integer> exported = new QueryEngine(s).listRows(stmt);
        List<String[]> viewed = page(s, "select * from t order by salary desc limit 5 offset 4", 1, 50).rows();

        assertEquals(5, exported.size());
        List<Object> names = s.getColumns("name");
        for (int i = 0; i < viewed.size(); i++) {
            assertEquals(names.get(exported.get(i)), viewed.get(i)[0]);
        }
    }

    @Test
    @DisplayName("没有 limit / offset 时导出仍是全部命中行（回归锁）")
    void listRowsWithoutLimitReturnsAll() {
        ColumnStore s = idStore(10);

        assertEquals(10, new QueryEngine(s).listRows(parser.parse("select * from t")).size());
        assertEquals(10, new QueryEngine(s).listRows(parser.parse("select * from t order by id")).size());
    }

    // ==================== 多列 ORDER BY ====================
    // 语义：先按第一列排，相等才看第二列，依次类推。

    // dept 有重复值，才能看出第二排序列是否真的生效
    private static ColumnStore deptStore() {
        return store(new String[]{"dept", "name", "salary"}, new Object[][]{
                {"A", "u1", 300},
                {"B", "u2", 100},
                {"A", "u3", 100},
                {"B", "u4", 300},
                {"A", "u5", 200},
                {"B", "u6", 200},
        });
    }

    // 取某一列的全部值
    private static List<String> columnOf(List<String[]> rows, int index) {
        List<String> list = new ArrayList<>();
        for (String[] r : rows) {
            list.add(r[index]);
        }
        return list;
    }

    @Test
    @DisplayName("两列排序：先按第一列分组，组内按第二列排")
    void multiColumnOrderBy() {
        QueryResult result = page(deptStore(),
                "select dept, name, salary from t order by dept asc, salary desc", 1, 50);

        assertEquals(List.of("A", "A", "A", "B", "B", "B"), columnOf(result.rows(), 0));
        assertEquals(List.of("300", "200", "100", "300", "200", "100"), columnOf(result.rows(), 2));
    }

    @Test
    @DisplayName("两列方向独立：一升一降与两升结果不同")
    void multiColumnOrderByMixedDirections() {
        List<String> ascAsc = columnOf(page(deptStore(),
                "select dept, salary from t order by dept asc, salary asc", 1, 50).rows(), 1);
        List<String> ascDesc = columnOf(page(deptStore(),
                "select dept, salary from t order by dept asc, salary desc", 1, 50).rows(), 1);

        assertEquals(ascAsc.size(), ascDesc.size());
        assertNotEquals(ascAsc, ascDesc, "第二列方向没生效，两列方向被当成同一个了");
        assertEquals(List.of("100", "200", "300"), ascAsc.subList(0, 3));
    }

    @Test
    @DisplayName("三列排序：前两列相等时才看第三列")
    void threeColumnOrderBy() {
        ColumnStore s = store(new String[]{"a", "b", "c"}, new Object[][]{
                {"x", "1", "p"},
                {"x", "1", "o"},
                {"x", "2", "n"},
                {"w", "9", "m"},
        });

        QueryResult result = page(s, "select a, b, c from t order by a asc, b asc, c asc", 1, 50);

        assertEquals(List.of("w", "x", "x", "x"), columnOf(result.rows(), 0));
        assertEquals(List.of("m", "o", "p", "n"), columnOf(result.rows(), 2));
    }

    @Test
    @DisplayName("第一列全相同时退化成单列排序")
    void multiColumnDegeneratesWhenFirstColumnConstant() {
        ColumnStore s = store(new String[]{"dept", "salary"}, new Object[][]{
                {"A", 300}, {"A", 100}, {"A", 200},
        });

        QueryResult result = page(s, "select salary from t order by dept asc, salary asc", 1, 50);

        assertEquals(List.of("100", "200", "300"), columnOf(result.rows(), 0));
    }

    @Test
    @DisplayName("多列排序与分页组合")
    void multiColumnOrderByWithPagination() {
        // 结果应是 [A300 A200 A100 B300 B200 B100]，第 2 页每页 2 行 → [A100 B300]
        QueryResult result = page(deptStore(),
                "select dept, salary from t order by dept asc, salary desc", 2, 2);

        assertEquals(6, result.totalRows());
        assertEquals(List.of("A", "B"), columnOf(result.rows(), 0));
        assertEquals(List.of("100", "300"), columnOf(result.rows(), 1));
    }

    @Test
    @DisplayName("多列排序与 LIMIT / OFFSET 组合")
    void multiColumnOrderByWithLimitOffset() {
        QueryResult result = page(deptStore(),
                "select dept, salary from t order by dept asc, salary desc limit 2 offset 1", 1, 50);

        assertEquals(2, result.totalRows());
        assertEquals(List.of("A", "A"), columnOf(result.rows(), 0));
        assertEquals(List.of("200", "100"), columnOf(result.rows(), 1));
    }

    @Test
    @DisplayName("有界堆路径与整体排序路径结果一致 —— 多列时最容易在这里出错")
    void multiColumnHeapMatchesFullSort() {
        // limit 卡在数据量中间：命中 40 行、limit 10 时走有界堆，limit 100 时走整体排序。
        // 两条分支的排序语义必须完全相同，否则数据量一变结果就变
        Object[][] data = new Object[40][2];
        for (int i = 0; i < 40; i++) {
            // dept 只有 4 个值，制造大量并列；salary 用公式打乱
            data[i] = new Object[]{"D" + (i % 4), (i * 7919) % 10007};
        }
        ColumnStore store40 = store(new String[]{"dept", "salary"}, data);

        String sql = "select dept, salary from t order by dept asc, salary desc";
        QueryResult viaHeap = page(store40, sql + " limit 10", 1, 50);
        QueryResult viaFullSort = page(store40, sql + " limit 100", 1, 50);

        assertEquals(10, viaHeap.totalRows(), "limit 10 应走有界堆分支");
        assertEquals(40, viaFullSort.totalRows(), "limit 100 超过行数，应走整体排序分支");

        // 整体排序的前 10 行必须与有界堆的 10 行完全一致
        assertEquals(columnOf(viaFullSort.rows(), 0).subList(0, 10), columnOf(viaHeap.rows(), 0));
        assertEquals(columnOf(viaFullSort.rows(), 1).subList(0, 10), columnOf(viaHeap.rows(), 1));
    }

    @Test
    @DisplayName("多列全降序时堆路径同样一致")
    void multiColumnHeapMatchesFullSortAllDesc() {
        Object[][] data = new Object[40][2];
        for (int i = 0; i < 40; i++) {
            data[i] = new Object[]{"D" + (i % 4), (i * 7919) % 10007};
        }
        ColumnStore store40 = store(new String[]{"dept", "salary"}, data);

        String sql = "select dept, salary from t order by dept desc, salary desc";
        QueryResult viaHeap = page(store40, sql + " limit 10", 1, 50);
        QueryResult viaFullSort = page(store40, sql + " limit 100", 1, 50);

        assertEquals(columnOf(viaFullSort.rows(), 0).subList(0, 10), columnOf(viaHeap.rows(), 0));
        assertEquals(columnOf(viaFullSort.rows(), 1).subList(0, 10), columnOf(viaHeap.rows(), 1));
    }

    @Test
    @DisplayName("单列排序的结果不受多列改造影响（回归锁）")
    void singleColumnOrderByUnchanged() {
        QueryResult result = page(deptStore(), "select salary from t order by salary desc", 1, 50);

        assertEquals(List.of("300", "300", "200", "200", "100", "100"), columnOf(result.rows(), 0));
    }

    @Test
    @DisplayName("多列排序下导出与界面一致")
    void multiColumnOrderByExportMatchesView() {
        ColumnStore s = deptStore();
        QueryStatement stmt = parser.parse(
                "select dept, salary from t order by dept asc, salary desc");

        List<Integer> exported = new QueryEngine(s).listRows(stmt);
        List<String[]> viewed = page(s, "select dept, salary from t order by dept asc, salary desc",
                1, 50).rows();

        assertEquals(viewed.size(), exported.size());
        List<Object> salaries = s.getColumns("salary");
        for (int i = 0; i < viewed.size(); i++) {
            assertEquals(String.valueOf(salaries.get(exported.get(i))), viewed.get(i)[1]);
        }
    }

    @Test
    @DisplayName("多列排序里含 null 时仍保持全序，不抛异常")
    void multiColumnOrderByWithNulls() {
        ColumnStore s = store(new String[]{"a", "b"}, new Object[][]{
                {"x", 100},
                {"x", null},
                {"y", 200},
                {null, 50},
        });

        QueryResult result = page(s, "select a, b from t order by a asc, b asc", 1, 50);

        assertEquals(4, result.rows().size());
    }

    // ==================== 常量列与聚合混写 ====================
    // 常量列（select 1 from t）是合法且有用的写法，本轮保证它一字不变；
    // 普通列与聚合混写则在解析阶段就报错，执行期不会再走到 const 分支

    private static ColumnStore threeRows() {
        return store(new String[]{"name", "salary"}, new Object[][]{
                {"u1", 100}, {"u2", 200}, {"u3", 300},
        });
    }

    @Test
    @DisplayName("常量列：每行输出同一个常量")
    void constantColumn() {
        QueryResult result = page(threeRows(), "select 1 from t", 1, 50);

        assertEquals(3, result.totalRows());
        assertEquals(List.of("1"), result.columns());
        assertEquals(List.of("1", "1", "1"), firstColumn(result.rows()));
    }

    @Test
    @DisplayName("字符串常量列：列名去掉引号，值也去掉引号")
    void stringConstantColumn() {
        QueryResult result = page(threeRows(), "select 'abc' from t", 1, 50);

        assertEquals(List.of("abc"), result.columns());
        assertEquals(List.of("abc", "abc", "abc"), firstColumn(result.rows()));
    }

    @Test
    @DisplayName("常量与普通列并存：常量按行重复，普通列照常取值")
    void constantWithNormalColumn() {
        QueryResult result = page(threeRows(), "select 1, name from t", 1, 50);

        assertEquals(List.of("1", "name"), result.columns());
        assertEquals(3, result.rows().size());
        assertEquals(List.of("1", "1", "1"), firstColumn(result.rows()));
        assertEquals(List.of("u1", "u2", "u3"), columnOf(result.rows(), 1));
    }

    @Test
    @DisplayName("常量列受 WHERE 与 LIMIT 影响，与普通列一致")
    void constantColumnRespectsWhereAndLimit() {
        QueryResult filtered = page(threeRows(), "select 1 from t where salary > 150", 1, 50);
        assertEquals(2, filtered.totalRows());

        QueryResult limited = page(threeRows(), "select 1 from t limit 2", 1, 50);
        assertEquals(2, limited.totalRows());
        assertEquals(2, limited.rows().size());
    }

    @Test
    @DisplayName("常量 + 聚合：一行结果，常量就是它自己")
    void constantWithAggregate() {
        QueryResult result = page(threeRows(), "select 1, count(*) from t", 1, 50);

        assertEquals(1, result.totalRows());
        assertEquals(List.of("1", "count(*)"), result.columns());
        assertEquals(List.of("1", "3"), List.of(result.rows().get(0)));
    }

    // ---------- 字符串常量 + 聚合（回归：引号必须被剥掉）----------
    // 常量项的表头与取值都取自表达式原文，原文是 '总计'（带引号）。
    // 早先这两处漏了剥离，界面上表头和值都会带着一对引号

    @Test
    @DisplayName("字符串常量 + 聚合：列名与值都去掉引号")
    void stringConstantWithAggregate() {
        QueryResult result = page(threeRows(), "select '总计', count(*) from t", 1, 50);

        assertEquals(1, result.totalRows());
        assertEquals(List.of("总计", "count(*)"), result.columns());
        assertEquals(List.of("总计", "3"), List.of(result.rows().get(0)));
    }

    @Test
    @DisplayName("字符串常量在聚合里放到后面也一样去引号（列序无关）")
    void stringConstantAfterAggregate() {
        QueryResult result = page(threeRows(), "select count(*), '总计' from t", 1, 50);

        assertEquals(List.of("count(*)", "总计"), result.columns());
        assertEquals(List.of("3", "总计"), List.of(result.rows().get(0)));
    }

    @Test
    @DisplayName("字符串常量 + 数值聚合：同样去引号")
    void stringConstantWithSum() {
        QueryResult result = page(threeRows(), "select '工资合计', sum(salary) from t", 1, 50);

        assertEquals(List.of("工资合计", "sum(salary)"), result.columns());
        assertEquals(List.of("工资合计", "600"), List.of(result.rows().get(0)));
    }

    @Test
    @DisplayName("多个字符串常量 + 聚合：逐个去引号，不串位")
    void multipleStringConstantsWithAggregate() {
        QueryResult result = page(threeRows(), "select 'a', count(*), 'b' from t", 1, 50);

        assertEquals(List.of("a", "count(*)", "b"), result.columns());
        assertEquals(List.of("a", "3", "b"), List.of(result.rows().get(0)));
    }

    @Test
    @DisplayName("数字常量 + 字符串常量 + 聚合混写：只剥字符串的引号")
    void numberAndStringConstantsWithAggregate() {
        QueryResult result = page(threeRows(), "select 1, 'x', count(*) from t", 1, 50);

        assertEquals(List.of("1", "x", "count(*)"), result.columns());
        assertEquals(List.of("1", "x", "3"), List.of(result.rows().get(0)));
    }

    @Test
    @DisplayName("聚合里的字符串常量受 WHERE 影响，但值仍是常量本身")
    void stringConstantWithAggregateRespectsWhere() {
        QueryResult result = page(threeRows(),
                "select '合计', count(*) from t where salary > 150", 1, 50);

        assertEquals(1, result.totalRows());
        assertEquals(List.of("合计", "count(*)"), result.columns());
        assertEquals(List.of("合计", "2"), List.of(result.rows().get(0)));
    }

    @Test
    @DisplayName("单引号未闭合时按解析错误处理，不做越界的引号剥离")
    void unclosedQuoteRejectedNotStripped() {
        // 单独的 ' 会同时满足「以 ' 开头」和「以 ' 结尾」，
        // 剥离逻辑的长度判断（>= 2）就是为它准备的：不能让 substring 越界。
        // 这个输入在解析阶段就该被识别为引号未闭合
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> page(threeRows(), "select ' from t", 1, 50));

        assertTrue(e.getMessage().contains("单引号未闭合"), e.getMessage());
    }

    @Test
    @DisplayName("空字符串字面量作为常量：只剩一对引号，剥完是空串")
    void emptyStringConstant() {
        QueryResult result = page(threeRows(), "select '' from t limit 1", 1, 50);

        assertEquals(List.of(""), result.columns());
        assertEquals(List.of(""), firstColumn(result.rows()));
    }

    @Test
    @DisplayName("导出复用的投影列表头与界面表头一致（都不带引号）")
    void projectionColumnsHeaderMatchesResultColumns() {
        // 界面表格用 resolveColumnNames()，导出用 prepareColumns().names()，
        // 两处必须给出同一个表头，否则界面显示「标签」而导出文件里是「'标签'」
        QueryStatement stmt = parser.parse("select '标签', name from t");
        QueryEngine engine = new QueryEngine(threeRows());

        QueryEngine.ProjectionColumns cols = engine.prepareColumns(stmt);
        QueryResult result = engine.executePage(stmt, 1, 50);

        assertEquals(List.of("标签", "name"), List.of(cols.names()));
        assertEquals(result.columns(), List.of(cols.names()));
    }

    @Test
    @DisplayName("导出投影的取值也去引号，与界面一致")
    void projectionColumnsValuesMatch() {
        QueryStatement stmt = parser.parse("select '标签', name from t limit 1");
        QueryEngine engine = new QueryEngine(threeRows());

        QueryEngine.ProjectionColumns cols = engine.prepareColumns(stmt);
        String[] row = engine.projectRow(stmt, cols, 0);

        assertEquals(List.of("标签", "u1"), List.of(row));
    }

    @Test
    @DisplayName("纯聚合不被校验误伤（回归锁）")
    void pureAggregateStillWorks() {
        QueryResult result = page(threeRows(), "select count(*), avg(salary), min(salary) from t", 1, 50);

        assertEquals(1, result.totalRows());
        assertEquals(List.of("3", "200", "100"), List.of(result.rows().get(0)));
    }

    @Test
    @DisplayName("普通列 + 聚合在解析阶段就报错，不会拿到一列列名字符串")
    void mixedSelectFailsAtParseTime() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> page(threeRows(), "select name, count(*) from t", 1, 50));

        assertTrue(e.getMessage().contains("不能混用普通列"), e.getMessage());
    }

    // ==================== 大小写敏感的列名与提示 ====================
    // 列名一律大小写敏感：写对就能查，写错就报错。
    // 只是「仅大小写不同」时，报错之外再给一句紫色提示

    private static ColumnStore caseStore() {
        return store(new String[]{"UserName", "Salary"}, new Object[][]{
                {"u1", 100}, {"u2", 200}, {"u3", 300},
        });
    }

    @Test
    @DisplayName("聚合参数写对大小写就能查到（改前会被转成小写而查不到）")
    void aggregateFieldCaseMatchesExactly() {
        QueryResult result = page(caseStore(), "select sum(Salary) from t", 1, 50);

        assertEquals(List.of("600"), firstColumn(result.rows()));
    }

    @Test
    @DisplayName("聚合参数大小写写错时，抛出带提示的异常")
    void aggregateFieldWrongCaseCarriesHint() {
        QueryHintException e = assertThrows(QueryHintException.class,
                () -> page(caseStore(), "select sum(salary) from t", 1, 50));

        assertEquals("error:Unknown column:salary", e.getMessage());
        assertTrue(e.getHint().contains("Salary"), e.getHint());
    }

    @Test
    @DisplayName("其它路径的列名同样大小写敏感，且同样带提示")
    void otherPathsAlsoSuggest() {
        // where
        QueryHintException where = assertThrows(QueryHintException.class,
                () -> page(caseStore(), "select * from t where salary > 150", 1, 50));
        assertTrue(where.getHint().contains("Salary"), where.getHint());

        // order by
        QueryHintException order = assertThrows(QueryHintException.class,
                () -> page(caseStore(), "select * from t order by salary", 1, 50));
        assertTrue(order.getHint().contains("Salary"), order.getHint());
    }

    @Test
    @DisplayName("写对大小写时一切照常，不因校验而变慢或报错")
    void exactCaseWorksEverywhere() {
        assertEquals(3, page(caseStore(), "select UserName from t", 1, 50).totalRows());
        assertEquals(2, page(caseStore(), "select * from t where Salary > 150", 1, 50).totalRows());
        assertEquals(3, page(caseStore(), "select * from t order by Salary", 1, 50).totalRows());
    }

    @Test
    @DisplayName("有多个大小写候选时不猜，退回普通报错（无提示）")
    void ambiguousCaseGivesPlainError() {
        ColumnStore s = store(new String[]{"Salary", "SALARY"}, new Object[][]{
                {100, 1}, {200, 2},
        });

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> page(s, "select sum(salary) from t", 1, 50));

        assertFalse(e instanceof QueryHintException, "候选不唯一时不该猜");
        assertTrue(e.getMessage().contains("Unknown column:salary"), e.getMessage());
    }

    @Test
    @DisplayName("毫无关系的列名不给提示（回归锁）")
    void unrelatedColumnHasNoHint() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> page(caseStore(), "select bonus from t", 1, 50));

        assertFalse(e instanceof QueryHintException);
    }
}