package com.tinyquery.executor;

import com.tinyquery.parser.NameSuggester;
import com.tinyquery.parser.QueryHintException;

import java.util.*;

/**
 * 列式存储：一列一个 {@code List<Object>}，行通过下标对齐。
 *
 * <h2>不变式：写一次，读多次</h2>
 *
 * <p>加载器把整份文件读进来（{@link #addRow}），之后引擎只读不改。
 * 本类<b>没有任何删除、替换、重排行的方法</b>，因为查询从不修改数据 ——
 * 过滤靠下标列表（{@code List<Integer>}）表达，排序靠下标重排表达，
 * 都不碰这里的数据本身。同一次查询里多个阶段反复取同一列，拿到的必然是同一份。</p>
 *
 * <p><b>这条不变式只在"没人从外部改到内部数据"时成立</b>，所以注意
 * {@link #getColumns} 与 {@link #getColumnNames} <b>返回的是内部对象本身，不是副本</b>
 * （见各自的说明）。调用方当成只读的就好。</p>
 *
 * <p>好处是列数据只存一份，几十万行的表不会因为"传一次就要拷一次"而翻倍占用内存。</p>
 */
public class ColumnStore {

    //存储列数据，保持列的出现顺序
    private final Map<String, List<Object>> columns = new LinkedHashMap<>();
    //列的有序名字列表，支持按索引访问列名
    private final List<String> columnNames = new ArrayList<>();
    //列名哈希表，快速判断某列是否已存在，去重
    private final Set<String> columnNameSet = new HashSet<>();

    public void addRow(Map<String, Object> row) {
        int rowIndex = columns.isEmpty() ? 0 : columns.values().iterator().next().size();

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String colName = entry.getKey();
            Object value = entry.getValue();

            List<Object> col = columns.computeIfAbsent(colName, k -> {
                List<Object> created = new ArrayList<>();
                for (int i = 0; i < rowIndex; i++) {
                    created.add(null);
                }
                return created;
            });
            col.add(value);

            if (columnNameSet.add(colName)) {
                columnNames.add(colName);
            }
        }

        for (List<Object> col : columns.values()) {
            while (col.size() <= rowIndex) {
                col.add(null);
            }
        }
    }

    /**
     * 取一列的全部值。
     *
     * <p><b>返回的是内部列表本身，不是副本</b>：一次查询里这个方法会被调用很多次
     * （过滤、排序、投影、聚合各取一遍），每列几十万个元素，拷贝一次就是几十万次引用复制。
     * 所以<b>调用方只读，不要 add / set / sort</b>。</p>
     *
     * <p>改了就破坏了本类的「写一次读多次」不变式（见类注释），
     * 而且会同时影响同一次查询中其他已经取过这一列的地方 —— 症状是
     * 结果时对时错，很难查。</p>
     */
    public List<Object> getColumns(String columnName) {
        List<Object> col = columns.get(columnName);
        if (col == null) {
            // 列名依旧大小写敏感，写错就是查不到；但如果只是大小写对不上，
            // 顺带给一句提示 —— 表里明明有 Salary 却看到「Unknown column:salary」
            // 是件很让人困惑的事
            String suggestion = NameSuggester.suggest(columnName, columnNames);
            if (suggestion != null) {
                throw new QueryHintException("error:Unknown column:" + columnName,
                        NameSuggester.describe(columnName, suggestion));
            }
            throw new IllegalArgumentException("error:Unknown column:" + columnName);
        }
        return col;
    }

    /** 列名列表，同样<b>返回内部对象本身</b>，调用方只读 */
    public List<String> getColumnNames() {
        return columnNames;
    }

    /**
     * 行数。
     *
     * <p>名字容易误解成"列数"或"某列的下标" —— 实际返回的是<b>总行数</b>，
     * 取第一列的长度得来（所有列等长，见类注释的不变式）。
     * 表为空时返回 0。</p>
     */
    public int getColumnIndex() {
        if (columnNames.isEmpty()) return 0;
        return columns.get(columnNames.get(0)).size();
    }
}
