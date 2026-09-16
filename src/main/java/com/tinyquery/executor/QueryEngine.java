package com.tinyquery.executor;

import com.tinyquery.parser.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public record QueryEngine(ColumnStore store) {

    // AVG 结果的保留位数，超出部分四舍五入
    private static final int AVG_SCALE = 10;

    // 投影时列的种类。在行循环外判定一次即可，
    // 否则每行每列都要做一次 colName.matches("\\d+") 的正则匹配
    private static final int COL_FIELD = 0;
    private static final int COL_NUMBER_LITERAL = 1;
    private static final int COL_STRING_LITERAL = 2;

    /**
     * 分页执行查询
     * 返回的是「排序后第 offset 到 offset+pageSize 行」。分页只影响返回哪一段
     * 不影响 totalRows —— 前端靠 totalRows 算总页数
     * SQL 里的 LIMIT 是结果集的硬上限，分页在它划定的范围内翻。
     * 两者是「与」的关系：limit 10 时无论翻到第几页，总数都不会超过 10
     * 注意：每翻一页都要重新执行一次完整的 filter（+ 排序）
     * 没有游标也没有缓存。单页耗时与不分页时相当，但翻页会线性累积服务端开销
     * @param page     页码，从 1 开始
     * @param pageSize 每页行数，调用方保证已在合法区间内
     */
    public QueryResult executePage(QueryStatement stmt, int page, int pageSize) {
        // 聚合结果固定一行，分页对它没有意义
        if (stmt.isAggregate()) {
            List<String[]> rows = executeAggregate(stmt);
            return new QueryResult(resolveColumnNames(stmt), rows, rows.size(), false);
        }

        List<Integer> indexes = filter(stmt);
        int matched = indexes.size();

        // LIMIT 先收窄结果集范围，分页在这个范围内切。
        // 不生效的话 select ... limit 10 会返回整表，是个不报错的静默错误
        int totalRows = stmt.hasLimit() ? Math.min(matched, stmt.limit()) : matched;

        if (stmt.hasOrderBy()) {
            // 排序范围同时受「本页末尾」和「LIMIT 上限」约束，取较小者。
            // 用 long 相乘再收窄，避免 page * pageSize 溢出 int
            int need = (int) Math.min((long) page * pageSize, totalRows);
            indexes = sortAndLimit(indexes, stmt.sort(), need);
        } else if (indexes.size() > totalRows) {
            indexes = indexes.subList(0, totalRows);
        }

        // 切出本页区间。上一页的数据直接跳过，不参与投影
        int from = (page - 1) * pageSize;
        List<Integer> pageIndexes;
        if (from >= indexes.size()) {
            // 页码超出范围，返回空页而不是报错 —— 前端可能停在删空了的页上
            pageIndexes = List.of();
        } else {
            int to = Math.min(from + pageSize, indexes.size());
            pageIndexes = indexes.subList(from, to);
        }

        boolean hasMore = (long) page * pageSize < totalRows;

        return new QueryResult(resolveColumnNames(stmt), selectColumns(stmt, pageIndexes),
                totalRows, hasMore);
    }

    /**
     * 按 SELECT 列表投影单行，供流式导出使用。
     * 与 execute() 的区别：不做行数截断、不物化整个结果集，
     * 调用方取一行写一行，内存占用与总行数无关
     * @param stmt  已解析的语句
     * @param cols  预先解析好的投影列（见 prepareColumns）
     * @param rowIndex 行号
     */
    public String[] projectRow(QueryStatement stmt, ProjectionColumns cols, int rowIndex) {
        return buildRow(cols, rowIndex);
    }

    //解析结果表的列名
    private List<String> resolveColumnNames(QueryStatement stmt) {
        // 聚合：直接用表达式原文作为表头，如 count(*) / avg(粉丝数)
        if (stmt.isAggregate()) {
            List<AggregationInfo> aggs = stmt.getAggregations();
            List<String> names = new ArrayList<>(aggs.size());
            for (AggregationInfo agg : aggs) {
                names.add(agg.original());
            }
            return names;
        }

        String[] colNames = stmt.selectColumns();

        // select * 时列名只有数据加载完才知道
        if (colNames.length == 1 && "*".equals(colNames[0])) {
            return List.copyOf(store.getColumnNames());
        }

        List<String> names = new ArrayList<>(colNames.length);
        for (String colName : colNames) {
            // 字符串字面量作为列名时去掉引号，与结果行的取值保持一致
            // 长度判断是必需的：单独的 ' 会同时满足前后缀条件，substring 会越界
            if (colName.length() >= 2 && colName.startsWith("'") && colName.endsWith("'")) {
                names.add(colName.substring(1, colName.length() - 1));
            } else {
                names.add(colName);
            }
        }
        return names;
    }

    //根据 where 条件返回行号列表
    private List<Integer> filter(QueryStatement stmt) {
        // 行数在循环外取一次：getColumnIndex() 内部要做两次集合查找，
        // 直接写在 for 的循环条件里会随每一行重复执行
        int rowCount = store.getColumnIndex();

        // 预分配容量，避免 ArrayList 反复扩容
        List<Integer> result = new ArrayList<>(rowCount);

        if (!stmt.hasWhere()) {
            for (int i = 0; i < rowCount; i++) {
                result.add(i);
            }
            return result;
        }

        // 条件树编译一次：列引用与比较值都在这里解析，
        // 行循环内只做「取值 + 比较」，不再查表、不再解析字符串
        BoundCondition condition = bind(stmt.condition());

        for (int i = 0; i < rowCount; i++) {
            if (condition.test(i)) {
                result.add(i);
            }
        }
        return result;
    }

    // ==================== 排序 ====================

    /**
     * 按条件过滤并排序，返回全部命中行号（不做行数截断）。
     *
     * <p>供导出复用。导出必须与查询保持同样的行序，
     * 否则「先看一眼再导出」两边结果对不上。</p>
     */
    public List<Integer> listRows(QueryStatement stmt) {
        List<Integer> indexes = filter(stmt);
        if (stmt.hasOrderBy()) {
            // 导出不限行数，传入 Integer.MAX_VALUE 让它走「整体排序」分支
            indexes = sortAndLimit(indexes, stmt.sort(), Integer.MAX_VALUE);
        }
        return indexes;
    }

    /**
     * 排序并截断。
     *
     * <p>结果集不超过上限时直接整体排序；超过时用容量为 limit 的有界堆，
     * 只保留最优的 N 个，复杂度从 O(n log n) 降到 O(n log k)。
     * 在 limit 远小于命中行数时（如百万行取前 10）收益显著。</p>
     */
    private List<Integer> sortAndLimit(List<Integer> indexes, SortSpec sort, int limit) {
        // LIMIT 0 表示不要任何行；空结果也无需排序。
        // 这个提前返回同时避免了下面对 PriorityQueue 传非法容量
        if (limit <= 0 || indexes.isEmpty()) {
            return new ArrayList<>();
        }

        List<Object> column = store.getColumns(sort.column());

        if (indexes.size() <= limit) {
            // 全部都在返回范围内，直接整体排序即可。
            // List.sort 是稳定排序，同值行保持原有顺序
            List<Integer> sorted = new ArrayList<>(indexes);
            sorted.sort(comparator(column, sort.desc()));
            return sorted;
        }

        // 堆里保留「最不该出现在结果里」的那些行，堆顶即当前最差的一行；
        // 新行只要优于堆顶就替换掉它，最终堆里就是最优的 N 行。
        //   升序取最小 N 个 → 最差的是最大值 → 用最大堆
        //   降序取最大 N 个 → 最差的是最小值 → 用最小堆
        // 两种情况都等价于「按目标顺序反转」
        Comparator<Integer> worstFirst = comparator(column, sort.desc()).reversed();
        PriorityQueue<Integer> heap = new PriorityQueue<>(limit, worstFirst);

        for (int idx : indexes) {
            if (heap.size() < limit) {
                heap.add(idx);
                continue;
            }
            // 与堆顶（当前最差）比较，优于它才替换。
            // 注意：有界堆不保证同值行保持原顺序，与上面的稳定排序略有差别
            if (worstFirst.compare(idx, heap.peek()) > 0) {
                heap.poll();
                heap.add(idx);
            }
        }

        // 堆的弹出顺序与目标顺序相反，反转即得最终结果
        List<Integer> result = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            result.add(heap.poll());
        }
        Collections.reverse(result);
        return result;
    }

    // 构造排序比较器。desc 为 true 时降序
    private Comparator<Integer> comparator(List<Object> column, boolean desc) {
        Comparator<Integer> byValue = (a, b) -> compareCells(column.get(a), column.get(b));
        return desc ? byValue.reversed() : byValue;
    }

    /**
     * 排序用的全序比较。
     *
     * <p>同一列可能混有数值与文本（归一化器对不同行可能给出不同类型，
     * 比如某行是 8000、某行是 "N/A"），排序必须有确定的全序，
     * 否则结果不可预期。约定：</p>
     * <ol>
     *   <li>数值（Integer / BigDecimal）按数值大小，排在最前</li>
     *   <li>其余按字符串字典序，排在数值之后</li>
     *   <li>null 排在最后</li>
     * </ol>
     */
    private static int compareCells(Object a, Object b) {
        if (a == b) {
            return 0;               // 同一对象，或两者同为 null
        }
        if (a == null) {
            return 1;               // null 排最后
        }
        if (b == null) {
            return -1;
        }

        // 快速路径：同为 Integer 时直接比较。
        // 排序的比较次数是 n log n 量级，走 BigDecimal 会为每次比较分配对象
        if (a instanceof Integer intA && b instanceof Integer intB) {
            return Integer.compare(intA, intB);
        }

        BigDecimal numberA = toBigDecimal(a);
        BigDecimal numberB = toBigDecimal(b);
        if (numberA != null && numberB != null) {
            return numberA.compareTo(numberB);
        }
        if (numberA != null) {
            return -1;              // 数值排在文本之前
        }
        if (numberB != null) {
            return 1;
        }

        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    // ==================== 条件编译 ====================

    /**
     * 编译后的条件节点。
     *
     * <p>把解析产物 Condition 转换成执行形态，转换时一次性解析好列引用与比较值，
     * 避免在行循环里重复做这些「每行结果都相同」的工作。</p>
     */
    private interface BoundCondition {
        //判断第 rowIndex 行是否满足本节点
        boolean test(int rowIndex);
    }

    /**
     * 绑定后的简单条件。
     *
     * @param column       已解析的列数据，行循环内直接按行号取值
     * @param op           比较运算符
     * @param numberValue  预解析的数值比较值，无法解析为数值时为 null
     * @param stringValue  原始比较值，与字符串单元格比较时使用
     * @param integerValue 预解析的整数值，条件值不是整数时为 null（数值快速路径用）
     */
    private record BoundSimple(List<Object> column, String op, BigDecimal numberValue,
                               String stringValue, Long integerValue) implements BoundCondition {

        @Override
        public boolean test(int rowIndex) {
            Object cell = column.get(rowIndex);

            // 快速路径：整数值 + 条件值是整数时，直接用 long 比较。
            // 省掉每格一次的 BigDecimal 对象分配，百万行可减少百万次分配
            if (cell instanceof Integer cellInt && integerValue != null) {
                long target = integerValue;
                return switch (op) {
                    case ">" -> cellInt > target;
                    case "<" -> cellInt < target;
                    case ">=" -> cellInt >= target;
                    case "<=" -> cellInt <= target;
                    case "=" -> cellInt == target;
                    case "!=", "<>" -> cellInt != target;
                    default -> false;
                };
            }

            // 通用路径：大数、小数走 BigDecimal 比较
            BigDecimal cellNumber = toBigDecimal(cell);
            if (cellNumber != null) {
                // 列是数值但条件值不是数字。此处才报错是刻意的：
                // 若在编译期就报错，where name = 'abc' 这类纯字符串条件会被误伤
                if (numberValue == null) {
                    throw new IllegalArgumentException("error:条件值不是合法数字：" + stringValue);
                }
                return matchNumber(cellNumber, op, numberValue);
            }

            if (cell instanceof String cellStr) {
                return matchString(cellStr, op, stringValue);
            }

            return false;
        }
    }

    //绑定后的 AND 节点
    private record BoundAnd(BoundCondition left, BoundCondition right) implements BoundCondition {
        @Override
        public boolean test(int rowIndex) {
            return left.test(rowIndex) && right.test(rowIndex);
        }
    }

    //绑定后的 OR 节点
    private record BoundOr(BoundCondition left, BoundCondition right) implements BoundCondition {
        @Override
        public boolean test(int rowIndex) {
            return left.test(rowIndex) || right.test(rowIndex);
        }
    }

    //绑定后的 NOT 节点
    private record BoundNot(BoundCondition inner) implements BoundCondition {
        @Override
        public boolean test(int rowIndex) {
            return !inner.test(rowIndex);
        }
    }

    //未知条件类型：一律视为不匹配，与原实现保持一致
    private record BoundNever() implements BoundCondition {
        @Override
        public boolean test(int rowIndex) {
            return false;
        }
    }

    /**
     * 把解析出的条件树编译成绑定形态。
     *
     * <p>只做「解析」，不做「校验」：条件值是否合法要等真的遇到数值单元格才知道，
     * 放在这里提前判断会误伤纯字符串条件。</p>
     */
    private BoundCondition bind(Condition cond) {
        if (cond instanceof SimpleCondition sc) {
            // 列引用解析一次，行循环内不再查表
            List<Object> column = store.getColumns(sc.field());

            // 比较值解析一次，行循环内不再做字符串转数值
            BigDecimal numberValue = parseNumber(sc.value());
            if (numberValue == null) {
                numberValue = parseBooleanLiteral(sc.value());
            }

            return new BoundSimple(column, sc.op(), numberValue, sc.value(), toLongExact(numberValue));
        }
        if (cond instanceof AndCondition ac) {
            return new BoundAnd(bind(ac.left()), bind(ac.right()));
        }
        if (cond instanceof OrCondition oc) {
            return new BoundOr(bind(oc.left()), bind(oc.right()));
        }
        if (cond instanceof NotCondition nc) {
            return new BoundNot(bind(nc.inner()));
        }
        return new BoundNever();
    }

    //数值比较
    private static boolean matchNumber(BigDecimal cell, String op, BigDecimal value) {
        int cmp = cell.compareTo(value);
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

    //字符串比较
    private static boolean matchString(String cell, String op, String value) {
        return switch (op) {
            case "="  -> cell.equals(value);
            case "!=", "<>" -> !cell.equals(value);
            case ">"  -> cell.compareTo(value) > 0;
            case "<"  -> cell.compareTo(value) < 0;
            case ">=" -> cell.compareTo(value) >= 0;
            case "<=" -> cell.compareTo(value) <= 0;
            default -> false;
        };
    }

    //把单元格转成 BigDecimal，非数值返回 null
    private static BigDecimal toBigDecimal(Object cell) {
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
    private static BigDecimal parseNumber(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    //解析布尔字面量，使 BOOLEAN 列（已归一为 1/0）也能用 = 'true' 这类写法查询
    private static BigDecimal parseBooleanLiteral(String value) {
        String lower = value.trim().toLowerCase();
        if ("true".equals(lower)) {
            return BigDecimal.ONE;
        }
        if ("false".equals(lower)) {
            return BigDecimal.ZERO;
        }
        return null;
    }

    //能精确转成 long 时返回该值，否则返回 null（走 BigDecimal 通用路径）
    private static Long toLongExact(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            // 含小数或超出 long 范围
            return null;
        }
    }

    // ==================== 投影列预解析（供导出复用） ====================
    /**
     * 预先解析好的投影列。
     *
     * 导出时每行都要投影，列的种类判定与列引用必须在循环外做一次，
     * 否则会退化成「每行每列都做一次正则匹配 + HashMap 查找」
     */
    public record ProjectionColumns(String[] names, int[] kinds, String[] literals, List<List<Object>> columns) {
    }

    //解析 SELECT 列表，得到投影所需的全部信息
    public ProjectionColumns prepareColumns(QueryStatement stmt) {
        String[] colNames = stmt.selectColumns();
        boolean selectAll = (colNames.length == 1 && "*".equals(colNames[0]));

        String[] actualCols = selectAll
                ? store.getColumnNames().toArray(new String[0])
                : colNames;

        int colCount = actualCols.length;
        int[] kinds = new int[colCount];
        String[] literals = new String[colCount];
        List<List<Object>> columns = new ArrayList<>(colCount);

        for (int i = 0; i < colCount; i++) {
            String colName = actualCols[i];
            if (colName.matches("\\d+")) {
                kinds[i] = COL_NUMBER_LITERAL;
                literals[i] = colName;
                columns.add(null);
            } else if (colName.length() >= 2 && colName.startsWith("'") && colName.endsWith("'")) {
                kinds[i] = COL_STRING_LITERAL;
                literals[i] = colName.substring(1, colName.length() - 1);
                columns.add(null);
            } else {
                kinds[i] = COL_FIELD;
                literals[i] = null;
                columns.add(store.getColumns(colName));
            }
        }

        return new ProjectionColumns(actualCols, kinds, literals, columns);
    }

    //按预解析的列信息投影一行
    private String[] buildRow(ProjectionColumns cols, int rowIndex) {
        int colCount = cols.names().length;
        String[] row = new String[colCount];
        for (int i = 0; i < colCount; i++) {
            switch (cols.kinds()[i]) {
                case COL_NUMBER_LITERAL, COL_STRING_LITERAL -> row[i] = cols.literals()[i];
                default -> row[i] = String.valueOf(cols.columns().get(i).get(rowIndex));
            }
        }
        return row;
    }

    //列选择：根据行号和列名，取出数据
    private List<String[]> selectColumns(QueryStatement stmt, List<Integer> indexes) {
        List<String[]> result = new ArrayList<>(indexes.size());

        // 复用导出的预解析逻辑，避免两处各维护一份投影规则
        ProjectionColumns cols = prepareColumns(stmt);

        for (int idx : indexes) {
            result.add(buildRow(cols, idx));
        }
        return result;
    }

    // ==================== 聚合 ====================

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

        List<String[]> result = new ArrayList<>(1);
        result.add(row);
        return result;
    }

    // 数值聚合：sum / avg / max / min。
    // 累加器统一用 BigDecimal，避免大数溢出与小数精度丢失；
    // 分子与分母同源，只统计真正参与累加的数值单元格，空值与非数值不参与
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

    // ==================== 控制台打印 ====================

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