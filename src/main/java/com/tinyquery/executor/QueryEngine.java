package com.tinyquery.executor;

import com.tinyquery.parser.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public record QueryEngine(ColumnStore store) {

    // 明细查询单次返回的行数上限。
    // 限制的是「返回给前端渲染的行数」，目的是避免大结果集的 JSON 渲染卡死浏览器；
    // 这是硬天花板，显式 LIMIT 只能收窄它、不能突破它。聚合查询不受此限制。
    private static final int MAX_RESULT_ROWS = 1000;

    // AVG 结果的保留位数，超出部分四舍五入
    private static final int AVG_SCALE = 10;

    //执行查询，返回结果表格和执行元信息
    public QueryResult execute(QueryStatement stmt) {
        if (stmt.isAggregate()) {
            List<String[]> rows = executeAggregate(stmt);
            // 聚合结果固定一行，不存在截断
            return new QueryResult(rows, rows.size(), false);
        }

        List<Integer> indexes = filter(stmt);
        int totalRows = indexes.size();

        // 生效上限取「显式 LIMIT」与「默认上限」的较小者
        int effectiveLimit = stmt.hasLimit()
                ? Math.min(stmt.limit(), MAX_RESULT_ROWS)
                : MAX_RESULT_ROWS;

        boolean truncated = false;
        if (totalRows > effectiveLimit) {
            indexes = indexes.subList(0, effectiveLimit);
            truncated = true;
        }

        return new QueryResult(selectColumns(stmt, indexes), totalRows, truncated);
    }

    //根据 where 条件返回行号列表
    private List<Integer> filter(QueryStatement stmt) {
        List<Integer> result = new ArrayList<>();

        if (!stmt.hasWhere()) {
            for (int i = 0; i < store.getColumnIndex(); i++) {
                result.add(i);
            }
            return result;
        }

        for (int i = 0; i < store.getColumnIndex(); i++) {
            if (evalCondition(stmt.condition(), i)) {
                result.add(i);
            }
        }
        return result;
    }

    private boolean evalCondition(Condition cond, int rowIndex) {
        if (cond instanceof SimpleCondition sc) {
            List<Object> column = store.getColumns(sc.field());
            Object cell = column.get(rowIndex);
            return compare(cell, sc.op(), sc.value());
        }
        if (cond instanceof AndCondition ac) {
            return evalCondition(ac.left(), rowIndex) && evalCondition(ac.right(), rowIndex);
        }
        if (cond instanceof OrCondition oc) {
            return evalCondition(oc.left(), rowIndex) || evalCondition(oc.right(), rowIndex);
        }
        if (cond instanceof NotCondition nc) {
            return !evalCondition(nc.inner(), rowIndex);
        }
        return false;
    }

    //判断 cell 是否满足 op value
    private boolean compare(Object cell, String op, String value) {
        // 数值单元格（Integer / BigDecimal）统一按 BigDecimal 比较。
        // 大数与小数为 String 时会被字典序比较（"9" > "10"），故不再降级为字符串
        BigDecimal cellNumber = toBigDecimal(cell);
        if (cellNumber != null) {
            BigDecimal compareValue = parseNumber(value);
            if (compareValue == null) {
                // 兼容布尔字面量：BOOLEAN 列已归一为 1/0
                compareValue = parseBooleanLiteral(value);
            }
            if (compareValue == null) {
                // 不静默返回 false，否则用户会误以为「确实没有匹配行」
                throw new IllegalArgumentException("error:条件值不是合法数字：" + value);
            }

            int cmp = cellNumber.compareTo(compareValue);
            return switch (op) {
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                case "=" -> cmp == 0;
                case "!=", "<>" -> cmp != 0;
                default -> false;
            };
        }

        if (cell instanceof String cellStr) {
            return switch (op) {
                case "="  -> cellStr.equals(value);
                case "!=", "<>" -> !cellStr.equals(value);
                case ">"  -> cellStr.compareTo(value) > 0;
                case "<"  -> cellStr.compareTo(value) < 0;
                case ">=" -> cellStr.compareTo(value) >= 0;
                case "<=" -> cellStr.compareTo(value) <= 0;
                default -> false;
            };
        }

        return false;
    }

    //把单元格转成 BigDecimal，非数值返回 null
    private BigDecimal toBigDecimal(Object cell) {
        if (cell instanceof BigDecimal decimal) {
            return decimal;
        }
        if (cell instanceof Integer intValue) {
            return BigDecimal.valueOf(intValue);
        }
        if (cell instanceof Long longValue) {
            return BigDecimal.valueOf(longValue);
        }
        if (cell instanceof Double doubleValue) {
            return BigDecimal.valueOf(doubleValue);
        }
        return null;
    }

    //解析 SQL 中的数值字面量，非数值返回 null
    private BigDecimal parseNumber(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    //解析布尔字面量，使 BOOLEAN 列（已归一为 1/0）也能用 = 'true' 这类写法查询
    private BigDecimal parseBooleanLiteral(String value) {
        String lower = value.trim().toLowerCase();
        if ("true".equals(lower)) {
            return BigDecimal.ONE;
        }
        if ("false".equals(lower)) {
            return BigDecimal.ZERO;
        }
        return null;
    }

    //列选择：根据行号和列名，取出数据
    private List<String[]> selectColumns(QueryStatement stmt, List<Integer> indexes) {
        List<String[]> result = new ArrayList<>();

        String[] colNames = stmt.selectColumns();
        boolean selectAll = (colNames.length == 1 && "*".equals(colNames[0]));

        String[] actualCols;
        if (selectAll) {
            actualCols = store.getColumnNames().toArray(new String[0]);
        } else {
            actualCols = colNames;
        }

        //提前把所有需要的列取出来，只调用一次 getColumns()
        List<List<Object>> columns = new ArrayList<>();
        for (String colName : actualCols) {
            if (!colName.matches("\\d+") && !colName.startsWith("'")) {
                columns.add(store.getColumns(colName));
            } else {
                columns.add(null);
            }
        }

        for (int idx : indexes) {
            String[] row = new String[actualCols.length];
            for (int i = 0; i < actualCols.length; i++) {
                String colName = actualCols[i];
                if (colName.matches("\\d+")) {
                    row[i] = colName;
                } else if (colName.startsWith("'") && colName.endsWith("'")) {
                    row[i] = colName.substring(1, colName.length() - 1);
                }else {
                    Object val = columns.get(i).get(idx);
                    row[i] = String.valueOf(val);
                }
            }
            result.add(row);
        }
        return result;
    }

    private List<String[]> executeAggregate(QueryStatement stmt) {
        List<Integer> indexes = filter(stmt);
        List<AggregationInfo> aggs = stmt.getAggregations();

        String[] row = new String[aggs.size()];
        for (int i = 0; i < aggs.size(); i++) {
            AggregationInfo agg = aggs.get(i);
            String func = agg.function();
            String field = agg.field();

            // 处理常量（SELECT 123）
            if ("const".equals(func)) {
                row[i] = agg.original();
                continue;
            }

            // 处理计数：count(*) 统计命中行数；count(列) 只统计该列非空的值。
            // 原实现一律返回行数，把列名参数整个忽略了，与 SQL 语义不符
            if ("count".equals(func)) {
                row[i] = field == null
                        ? String.valueOf(indexes.size())
                        : String.valueOf(countPresent(field, indexes));
                continue;
            }

            // 处理数值聚合函数
            switch (func) {
                case "sum", "avg", "max", "min" -> row[i] = aggregateNumber(func, field, indexes);
                default -> row[i] = "0";
            }
        }

        List<String[]> result = new ArrayList<>();
        result.add(row);
        return result;
    }

    // 数值聚合：sum / avg / max / min。
    // 累加器统一用 BigDecimal，避免大数溢出与小数精度丢失；
    // 分子与分母同源，只统计真正参与累加的数值单元格，空值与非数值不参与
    private String aggregateNumber(String field, List<Integer> indexes) {
        List<Object> column = store.getColumns(field);
        return aggregateNumber("sum", field, indexes) == null ? "0" : null;
    }

    private String aggregateNumber(String func, String field, List<Integer> indexes) {
        List<Object> column = store.getColumns(field);

        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min = null;
        BigDecimal max = null;
        int count = 0;

        for (int idx : indexes) {
            BigDecimal value = toBigDecimal(column.get(idx));
            if (value == null) {
                continue;
            }
            sum = sum.add(value);
            if (min == null || value.compareTo(min) < 0) {
                min = value;
            }
            if (max == null || value.compareTo(max) > 0) {
                max = value;
            }
            count++;
        }

        if (count == 0) {
            return "0";
        }

        return switch (func) {
            case "sum" -> formatNumber(sum);
            case "avg" -> formatNumber(sum.divide(BigDecimal.valueOf(count), AVG_SCALE, RoundingMode.HALF_UP));
            case "max" -> formatNumber(max);
            case "min" -> formatNumber(min);
            default -> "0";
        };
    }

    //统计某列在命中行中「非空」的单元格数，对应 SQL 的 COUNT(列) 语义
    private int countPresent(String columnName, List<Integer> indexes) {
        List<Object> column = store.getColumns(columnName);
        int present = 0;
        for (int idx : indexes) {
            if (isPresent(column.get(idx))) {
                present++;
            }
        }
        return present;
    }

    //判断单元格是否有值：null 与空串（CSV 空字段归一后的形态）都视为无值
    private boolean isPresent(Object cell) {
        if (cell == null) {
            return false;
        }
        if (cell instanceof String str) {
            return !str.trim().isEmpty();
        }
        return true;
    }

    //格式化数值结果：去掉多余的尾零，且不使用科学计数法
    private String formatNumber(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        // stripTrailingZeros 会把 100 变成 1E+2，补回 0 位保证输出可读
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    //打印结果表格
    public void printResult(List<String[]> rows, QueryStatement stmt) {
        if (rows == null || rows.isEmpty()) {
            System.out.flush();
            System.err.println("?==null");
            return;
        }

        String[] colNames = stmt.selectColumns();
        boolean selectAll = (colNames.length == 1 && "*".equals(colNames[0]));
        if (selectAll) {
            colNames = store.getColumnNames().toArray(new String[0]);
        }

        int[] colWidths = new int[colNames.length];

        //取列名长度整齐打印
        for (int i = 0; i < colNames.length; i++) {
            colWidths[i] = colNames[i].length();
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (row[i].length() > colWidths[i]) {
                    colWidths[i] = row[i].length();
                }
            }
        }

        //打印表格
        printSeparator(colWidths);
        printRow(colNames, colWidths);
        printSeparator(colWidths);

        for (String[] row : rows) {
            printRow(row, colWidths);
        }

        printSeparator(colWidths);
        System.out.println(rows.size() + " records in total");
    }

    //表格规则
    private void printSeparator(int[] widths) {
        System.out.print("+");
        for (int w : widths) {
            System.out.print("-".repeat(w + 2) + "+");
        }
        System.out.println();
    }

    private void printRow(String[] row, int[] widths) {
        System.out.print("|");
        for (int i = 0; i < row.length; i++) {
            System.out.print(" " + padRight(row[i], widths[i]) + " |");
        }
        System.out.println();
    }

    private String padRight(String s, int width) {
        return String.format("%-" + width + "s", s);
    }
}