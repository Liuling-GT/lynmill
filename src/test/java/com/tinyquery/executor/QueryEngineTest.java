package com.tinyquery.executor;

import com.tinyquery.datasource.ValueNormalizer;
import com.tinyquery.parser.QueryStatement;
import com.tinyquery.parser.SQLParser;
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
}