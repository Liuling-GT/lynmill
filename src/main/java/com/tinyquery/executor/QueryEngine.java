package com.tinyquery.executor;

import com.tinyquery.i18n.Messages;
import com.tinyquery.parser.*;
import com.tinyquery.parser.where.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public record QueryEngine(ColumnStore store) {

    // AVG 结果的保留位数，超出部分四舍五入
    private static final int AVG_SCALE = 10;

    // 推断列类型时的抽样行数。百万行全扫没必要，前 200 行足够定类型
    private static final int TYPE_SAMPLE_SIZE = 200;

    // 图表聚合的默认上限。柱状图 50 根柱子还能看，再多就没法读了
    private static final int DEFAULT_CHART_TOP_N = 50;

    // 数值维度分桶的目标段数。实际段数会浮动 ——
    // 步长取整后才能保证桶边界是整数，段数因此不会是精确的 7
    private static final int TARGET_BUCKETS = 7;

    // 分桶的兜底上限。步长算法本身会把段数收敛到 4~10，
    // 这里只是防御性约束，防止异常数据导致段数失控
    private static final int MAX_BUCKETS = 30;

    // 维度值为 null 或空串时显示的类别名，避免前端出现一个空白的类别。
    // 取值见 chart.emptyCategory —— 它是按请求语言取的，不能是静态常量

    // 投影时列的种类。在行循环外判定一次即可，
    // 否则每行每列都要做一次 colName.matches("\\d+") 的正则匹配
    private static final int COL_FIELD = 0;
    private static final int COL_NUMBER_LITERAL = 1;
    private static final int COL_STRING_LITERAL = 2;

    /**
     * 分页执行查询
     * 返回的是「结果集里第 (page-1)*pageSize 到 page*pageSize 行」。
     * 分页只影响返回哪一段，不影响 totalRows —— 前端靠 totalRows 算总页数
     *
     * <p>结果集由 SQL 的 OFFSET / LIMIT 划定：先跳过 offset 行，再取至多 limit 行。
     * 分页在这个结果集内翻，三者是「与」的关系 ——
     * {@code limit 10 offset 5} 时无论翻到第几页总数都不会超过 10，
     * 且第一页的第一行是排序后的第 6 行。</p>
     *
     * 注意：每翻一页都要重新执行一次完整的 filter（+ 排序）
     * 没有游标也没有缓存。单页耗时与不分页时相当，但翻页会线性累积服务端开销
     * @param page     页码，从 1 开始
     * @param pageSize 每页行数，调用方保证已在合法区间内
     */
    public QueryResult executePage(QueryStatement stmt, int page, int pageSize) {
        // 聚合结果固定一行，分页对它没有意义
        if (stmt.isAggregate()) {
            List<String[]> rows = executeAggregate(stmt);
            List<String> names = resolveColumnNames(stmt);
            return new QueryResult(names, numberTypes(names.size()), rows, rows.size(), false);
        }

        List<Integer> indexes = filter(stmt);
        int matched = indexes.size();

        // OFFSET / LIMIT 划定结果集：先跳过、后截断，分页在这个范围内切。
        // 不生效的话 select ... limit 10 会返回整表，是个不报错的静默错误
        ResultRange range = resultRange(stmt, matched);
        int totalRows = range.size();

        if (stmt.hasOrderBy()) {
            // 排序范围要同时覆盖「跳过的 offset 行」「本页末尾」和「LIMIT 上限」。
            // 用 long 相加相乘再收窄，避免 page * pageSize 与 offset 相加溢出 int
            long need = (long) range.offset() + Math.min((long) page * pageSize, totalRows);
            indexes = sortAndLimit(indexes, stmt.sort(), (int) Math.min(need, matched));
        } else if (indexes.size() > range.offset() + totalRows) {
            indexes = indexes.subList(0, range.offset() + totalRows);
        }

        // 结果集 = 跳过 offset 之后的部分。
        // 类型推断必须只看这一段 —— offset 之前的行不属于结果集，
        // 拿它们抽样会把列类型带偏（前几行恰好是文本，数值列就会被判成 string）
        List<Integer> resultSet = range.offset() >= indexes.size()
                ? List.of()
                : indexes.subList(range.offset(), indexes.size());

        // 切出本页区间。上一页的数据直接跳过，不参与投影
        int from = (page - 1) * pageSize;
        List<Integer> pageIndexes;
        if (from >= resultSet.size()) {
            // 页码超出范围，返回空页而不是报错 —— 前端可能停在删空了的页上
            pageIndexes = List.of();
        } else {
            int to = Math.min(from + pageSize, resultSet.size());
            pageIndexes = resultSet.subList(from, to);
        }

        boolean hasMore = (long) page * pageSize < totalRows;

        // 投影列只解析一次，行投影与类型推断共用
        ProjectionColumns cols = prepareColumns(stmt);

        // 类型从「整个结果集」抽样，而不是只看当前页 ——
        // 当前页可能整列都是空值，会把它判成 empty
        return new QueryResult(resolveColumnNames(stmt), resolveColumnTypes(cols, resultSet),
                buildRows(cols, pageIndexes), totalRows, hasMore);
    }

    /**
     * LIMIT / OFFSET 划定的结果集区间
     * @param offset 结果集在命中行中的起始位置（即已跳过的行数）
     * @param size   结果集行数（已套用 limit 上限）
     */
    private record ResultRange(int offset, int size) {
    }

    /**
     * 由 LIMIT / OFFSET 与命中行数算出结果集区间
     * executePage 与 listRows 共用同一套算术，保证「界面看到的」与「导出的」是同一段
     */
    private static ResultRange resultRange(QueryStatement stmt, int matched) {
        int offset = stmt.hasOffset() ? stmt.offset() : 0;
        // offset 超出总行数时结果集为空，不能是负数
        int afterOffset = Math.max(matched - offset, 0);
        int size = stmt.hasLimit() ? Math.min(afterOffset, stmt.limit()) : afterOffset;
        return new ResultRange(offset, size);
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



    // 解析结果表的列名
    private List<String> resolveColumnNames(QueryStatement stmt) {
        // 聚合：直接用表达式原文作为表头，如 count(*) / avg(粉丝数)。
        // 常量项要剥引号：原文是 '总计'，表头应当是 总计
        if (stmt.isAggregate()) {
            List<AggregationInfo> aggs = stmt.getAggregations();
            List<String> names = new ArrayList<>(aggs.size());
            for (AggregationInfo agg : aggs) {
                names.add(unquote(agg.original()));
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
            names.add(unquote(colName));
        }
        return names;
    }

    /**
     * 去掉字符串字面量两侧的单引号；不是字面量则原样返回。
     *
     * <p>明细投影、常量项表头、常量项取值三处共用同一条规则 ——
     * 早先常量那两处漏了剥离，于是 {@code select '总计', count(*)}
     * 的表头和值都带着引号输出。</p>
     */
    private static String unquote(String text) {
        if (isStringLiteral(text)) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /**
     * 判断是否是两侧带单引号的字符串字面量（如 {@code '总计'}）。
     *
     * <p>长度判断是必需的：单独的 {@code '} 会同时满足前后缀条件，
     * 直接 substring 会越界。</p>
     */
    private static boolean isStringLiteral(String text) {
        return text != null && text.length() >= 2 && text.startsWith("'") && text.endsWith("'");
    }

    // ==================== 列类型推断 ====================

    // 聚合结果的列类型：聚合值一律是数值
    private static List<String> numberTypes(int size) {
        List<String> types = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            types.add("number");
        }
        return types;
    }

    /**
     * 推断投影列的值类型，供前端选择图表轴
     * 只抽样前若干行：全扫百万行成本不低，而类型在前几行就定了。
     * 字面量列的类型是已知的，不用抽样
     *
     * @return 与列一一对应的类型：number / string / empty
     */
    private List<String> resolveColumnTypes(ProjectionColumns cols, List<Integer> indexes) {
        int colCount = cols.names().length;
        List<String> types = new ArrayList<>(colCount);

        for (int i = 0; i < colCount; i++) {
            int kind = cols.kinds()[i];
            if (kind == COL_NUMBER_LITERAL) {
                types.add("number");
            } else if (kind == COL_STRING_LITERAL) {
                types.add("string");
            } else {
                types.add(inferType(cols.columns().get(i), indexes));
            }
        }
        return types;
    }

    /**
     * 从样本推断列类型
     * ValueNormalizer 只产出 Integer / BigDecimal / String 三种，
     * 因此非数值一律视为字符串。遇到第一个「有值」的单元格即可定论
     *
     * <p>「有值」的判定统一走 isPresent：null、空串、纯空格串都算无值。
     * 早先这里只跳过 null，于是列首的一个空串就把整列钉成 string ——
     * 同一个概念在本类里有了两套裁判，图表也因此把数值列当文本分组。</p>
     */
    private static String inferType(List<Object> column, List<Integer> indexes) {
        int sample = Math.min(indexes.size(), TYPE_SAMPLE_SIZE);
        for (int k = 0; k < sample; k++) {
            Object value = column.get(indexes.get(k));
            if (!isPresent(value)) {
                continue;
            }
            return (value instanceof Integer || value instanceof BigDecimal) ? "number" : "string";
        }

        // 样本里全是空值。退回扫描整列，避免因为头部空值把数值列误判成 empty
        for (int idx : indexes) {
            Object value = column.get(idx);
            if (!isPresent(value)) {
                continue;
            }
            return (value instanceof Integer || value instanceof BigDecimal) ? "number" : "string";
        }
        return "empty";
    }

    // 该列是否是数值列。用于判断维度要不要分桶
    private static boolean isNumericColumn(List<Object> column, List<Integer> indexes) {
        return "number".equals(inferType(column, indexes));
    }

    // 根据 where 条件返回行号列表
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
     * 按条件过滤并排序，返回结果集内的全部行号
     * 供导出复用。导出必须与界面看到的是同一段 ——
     * 行序一致，行数与范围也一致：LIMIT / OFFSET 划定的结果集就是导出的范围
     *
     * <p>早先这里刻意「不做行数截断」，于是 SQL 写了 limit 10、界面显示 10 行，
     * 点导出却拿到全部命中行。行序对得上而行数对不上，用户只会觉得导出坏了。</p>
     */
    public List<Integer> listRows(QueryStatement stmt) {
        List<Integer> indexes = filter(stmt);
        int matched = indexes.size();

        // 与 executePage 共用同一套 LIMIT / OFFSET 算术
        ResultRange range = resultRange(stmt, matched);
        int need = (int) Math.min((long) range.offset() + range.size(), matched);

        if (stmt.hasOrderBy()) {
            // 排到「offset + 结果集行数」即可，不必整体排序
            indexes = sortAndLimit(indexes, stmt.sort(), need);
        } else if (indexes.size() > need) {
            indexes = indexes.subList(0, need);
        }

        if (range.offset() >= indexes.size()) {
            return new ArrayList<>();
        }
        // 返回独立副本：subList 是视图，调用方再动 store 会让它跟着变
        return new ArrayList<>(indexes.subList(range.offset(), indexes.size()));
    }

    /**
     * 排序并截断
     * 结果集不超过上限时直接整体排序；超过时用容量为 limit 的有界堆
     * 只保留最优的 N 个，复杂度从 O(n log n) 降到 O(n log k)
     * 在 limit 远小于命中行数时（如百万行取前 10）收益显著
     */
    private List<Integer> sortAndLimit(List<Integer> indexes, SortSpecs sort, int limit) {
        // LIMIT 0 表示不要任何行；空结果也无需排序。
        // 这个提前返回同时避免了下面对 PriorityQueue 传非法容量
        if (limit <= 0 || indexes.isEmpty()) {
            return new ArrayList<>();
        }

        // 比较器只构造一次：多列时它内部要按列取 store 的列引用，
        // 放进循环里会随每次比较重复解析列名
        Comparator<Integer> order = comparator(sort);

        if (indexes.size() <= limit) {
            // 全部都在返回范围内，直接整体排序即可。
            // List.sort 是稳定排序，同值行保持原有顺序
            List<Integer> sorted = new ArrayList<>(indexes);
            sorted.sort(order);
            return sorted;
        }

        // 堆里保留「最不该出现在结果里」的那些行，堆顶即当前最差的一行；
        // 新行只要优于堆顶就替换掉它，最终堆里就是最优的 N 行。
        // 升序取最小 N 个 → 最差的是最大值 → 用最大堆
        // 降序取最大 N 个 → 最差的是最小值 → 用最小堆
        // 两种情况都等价于「按目标顺序反转」——
        // 多列时反转的是整条链，不是链上的某一列
        Comparator<Integer> worstFirst = order.reversed();
        PriorityQueue<Integer> heap = new PriorityQueue<>(limit, worstFirst);

        for (int idx : indexes) {
            if (heap.size() < limit) {
                heap.add(idx);
                continue;
            }
            // 与堆顶（当前最差）比较，优于它才替换。
            // 注意：有界堆不保证同值行保持原顺序，与上面的稳定排序略有差别。
            // 「同值」是多列全等的口径，所以列数变多后这种差别比单列时更容易出现
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

    /**
     * 构造排序比较器：按列表顺序串联，前一项相等才看后一项
     *
     * <p>用 {@link Comparator#thenComparing} 串联，「相等才继续」的语义天然吻合。
     * 每列的方向各自独立 —— {@code order by a asc, b desc} 是两段不同方向的比较器相加。</p>
     */
    private Comparator<Integer> comparator(SortSpecs sort) {
        Comparator<Integer> chain = null;
        // 列引用在链构造时取一次。同一列写两次（order by a, a desc）也照取，
        // 不做去重 —— 去重会让「后者覆盖前者」这种用户写法变得难以解释
        Map<String, List<Object>> columnCache = new HashMap<>();

        for (SortSpec spec : sort.specs()) {
            List<Object> column = columnCache.computeIfAbsent(spec.column(), store::getColumns);
            Comparator<Integer> byValue = (a, b) -> compareCells(column.get(a), column.get(b));
            if (spec.desc()) {
                byValue = byValue.reversed();
            }
            chain = (chain == null) ? byValue : chain.thenComparing(byValue);
        }

        // specs 至少一个（构造函数保证），这里的非空判断只为消除静态分析告警
        return chain == null ? (a, b) -> 0 : chain;
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
     * 编译后的条件节点
     * 把解析产物 Condition 转换成执行形态，转换时一次性解析好列引用与比较值，
     * 避免在行循环里重复做这些「每行结果都相同」的工作
     */
    private interface BoundCondition {
        // 判断第 rowIndex 行是否满足本节点
        boolean test(int rowIndex);
    }

    /**
     * 绑定后的简单条件
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

            // 通用路径
            // ：大数、小数走 BigDecimal 比较
            BigDecimal cellNumber = toBigDecimal(cell);
            if (cellNumber != null) {
                // 列是数值但条件值不是数字。此处才报错是刻意的：
                // 若在编译期就报错，where name = 'abc' 这类纯字符串条件会被误伤
                if (numberValue == null) {
                    throw new IllegalArgumentException(Messages.get("parse.invalidNumber", stringValue));
                }
                return matchNumber(cellNumber, op, numberValue);
            }

            if (cell instanceof String cellStr) {
                return matchString(cellStr, op, stringValue);
            }

            return false;
        }
    }

    // 绑定后的 AND 节点
    private record BoundAnd(BoundCondition left, BoundCondition right) implements BoundCondition {
        @Override
        public boolean test(int rowIndex) {
            return left.test(rowIndex) && right.test(rowIndex);
        }
    }

    // 绑定后的 OR 节点
    private record BoundOr(BoundCondition left, BoundCondition right) implements BoundCondition {
        @Override
        public boolean test(int rowIndex) {
            return left.test(rowIndex) || right.test(rowIndex);
        }
    }

    // 绑定后的 NOT 节点
    private record BoundNot(BoundCondition inner) implements BoundCondition {
        @Override
        public boolean test(int rowIndex) {
            return !inner.test(rowIndex);
        }
    }

    // 未知条件类型：一律视为不匹配，与原实现保持一致
    private record BoundNever() implements BoundCondition {
        @Override
        public boolean test(int rowIndex) {
            return false;
        }
    }

    /**
     * 把解析出的条件树编译成绑定形态
     * 只做「解析」，不做「校验」：条件值是否合法要等真的遇到数值单元格才知道
     * 放在这里提前判断会误伤纯字符串条件
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

    // 数值比较
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

    // 字符串比较
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

    // 把单元格转成 BigDecimal，非数值返回 null
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

    // 解析 SQL 中的数值字面量，非数值返回 null
    private static BigDecimal parseNumber(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 解析布尔字面量，使 BOOLEAN 列（已归一为 1/0）也能用 = 'true' 这类写法查询
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

    // 能精确转成 long 时返回该值，否则返回 null（走 BigDecimal 通用路径）
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
     * 预先解析好的投影列
     * 导出时每行都要投影，列的种类判定与列引用必须在循环外做一次
     * 否则会退化成「每行每列都做一次正则匹配 + HashMap 查找」
     *
     * <p>{@code names} 已剥掉字符串字面量的引号，与 {@link #resolveColumnNames}
     * 的表头保持一致 —— 导出直接写这一份，不能再带引号。</p>
     */
    public record ProjectionColumns(String[] names, int[] kinds, String[] literals, List<List<Object>> columns) {
    }

    // 解析 SELECT 列表，得到投影所需的全部信息
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
            } else if (isStringLiteral(colName)) {
                kinds[i] = COL_STRING_LITERAL;
                literals[i] = unquote(colName);
                columns.add(null);
            } else {
                kinds[i] = COL_FIELD;
                literals[i] = null;
                columns.add(store.getColumns(colName));
            }
        }

        return new ProjectionColumns(headerNames(actualCols), kinds, literals, columns);
    }

    /**
     * 把投影列名转成表头：字符串字面量去掉引号，其余原样。
     *
     * <p>界面表格与导出各取一次表头，两处必须一致，
     * 否则会出现「界面显示 标签、导出文件里是 '标签'」这种对不上的情况。</p>
     */
    private static String[] headerNames(String[] cols) {
        String[] names = new String[cols.length];
        for (int i = 0; i < cols.length; i++) {
            names[i] = unquote(cols[i]);
        }
        return names;
    }

    // 按预解析的列信息投影一行
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

    // 按已解析的列信息投影多行
    private List<String[]> buildRows(ProjectionColumns cols, List<Integer> indexes) {
        List<String[]> result = new ArrayList<>(indexes.size());
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

            // 常量项（select 1, count(*) 里的 1）：聚合结果只有一行，
            // 常量在这一行里就是它自己，输出表达式原文即可。
            // 字符串常量要剥引号（'总计' → 总计），否则界面会连引号一起显示
            // 普通列走不到这里 —— SQLParser 的 validateAggregateSelect 已经把它拦在解析阶段
            if ("const".equals(func)) {
                row[i] = unquote(agg.original());
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

    // 统计某列在命中行中「非空」的单元格数，对应 SQL 的 COUNT(列) 语义
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

    // 判断单元格是否有值：null、空串（CSV 空字段归一后的形态）、纯空格串都视为无值。
    // 列类型推断（inferType）与 COUNT(列) 共用这一套判定，避免两处标准再次漂移
    private static boolean isPresent(Object cell) {
        if (cell == null) {
            return false;
        }
        if (cell instanceof String str) {
            return !str.trim().isEmpty();
        }
        return true;
    }

    // 格式化数值结果：去掉多余的尾零，且不使用科学计数法
    private String formatNumber(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        // stripTrailingZeros 会把 100 变成 1E+2，补回 0 位保证输出可读
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    // ==================== 图表聚合 ====================

    /** 度量引用：列名 + 已解析的列数据，避免在多处按名字重复查表 */
    private record MeasureRef(String name, List<Object> column) {
    }

    /**
     * 按维度列分组、对度量列聚合，供图表使用
     * 与 SQL 聚合的区别：分组键来自界面上的下拉框，不是 SQL 里的 GROUP BY
     * 因此不需要动 parser。分组在内存里完成，数据已在 ColumnStore 中
     * 维度是数值列时走分桶（直方图），文本列时走分组。
     * 两种模式对前端是同一种形状：类别标签 + 每个度量一列值
     *
     * <p>刻意不套用 LIMIT / OFFSET。下面只做 {@code filter(stmt)}，
     * 不经过 {@code resultRange} 那套算术 —— 也就是说 SQL 里的 {@code limit 10}
     * 对图表无效，图表统计的是全部命中行。</p>
     *
     * <p>为什么这样才对：分页是「一屏看 50 行」的浏览手段，图表要看的是
     * 整份数据的分布。若跟着 LIMIT 走，{@code select * from t limit 10}
     * 画出来的就是"前 10 行"的占比图 —— 而用户会把它当成全量占比，是彻底的误导。
     * 写 LIMIT 的人通常只是想少取点数据，不会想到"顺带把图表变成局部图"。</p>
     *
     * <p>代价是大表上图表比表格多扫很多行，所以它是界面上显式的另一档操作，
     * 不会跟着翻页自动触发。</p>
     *
     * @param dimension   维度列名（分组键）
     * @param measures    度量列名，最多两个；aggregation 为 count 时忽略
     * @param aggregation 聚合方式：sum / avg / count / max / min，多个度量共用
     * @param topN        文本维度下最多返回多少个分组；为 null 时取默认值
     */
    public ChartResult aggregateForChart(QueryStatement stmt, String dimension, List<String> measures,
                                         String aggregation, Integer topN) {
        // 只过滤、不截断 —— 见上面对「刻意忽略 LIMIT」的说明
        List<Integer> indexes = filter(stmt);

        List<Object> dimColumn = store.getColumns(dimension);
        boolean countMode = "count".equals(aggregation);

        // 度量列只解析一次。count 模式不需要任何度量列，
        // 此时 measureRefs 为空，系列数按 1 算（就是那一列计数）
        List<MeasureRef> measureRefs = new ArrayList<>();
        if (!countMode && measures != null) {
            for (String name : measures) {
                measureRefs.add(new MeasureRef(name, store.getColumns(name)));
            }
        }

        // 数值维度分桶。整列都是非数值时回退到分组，避免得到一张空图
        if (isNumericColumn(dimColumn, indexes)) {
            return bucketAndAggregate(indexes, dimColumn, measureRefs, aggregation);
        }
        return groupAndAggregate(indexes, dimColumn, measureRefs, aggregation, topN);
    }

    /**
     * 文本维度：按值分组，尾部合并「其他」
     * 分组数超过上限时只做合并，不做截断丢弃 —— 丢掉的那部分必须
     * 计入「其他」，否则总值对不上，用户会以为数据少了
     */
    private ChartResult groupAndAggregate(List<Integer> indexes, List<Object> dimColumn,
                                          List<MeasureRef> measureRefs, String aggregation,
                                          Integer topN) {
        int limit = (topN == null || topN <= 0) ? DEFAULT_CHART_TOP_N : topN;
        // 系列数：count 只有一列数，其余每个度量一条
        int seriesCount = Math.max(1, measureRefs.size());

        // 分桶累加。用 LinkedHashMap 保证同值时的顺序稳定
        Map<String, GroupAcc> buckets = new LinkedHashMap<>();
        // 空值类别名按当前语言取。不能提到静态常量里 —— 那份值在类加载时就定死了，
        // 之后切语言也改不了，英文用户会看到一个「(空)」类别
        String emptyLabel = Messages.get("chart.emptyCategory");
        for (int idx : indexes) {
            Object raw = dimColumn.get(idx);
            String key = (raw == null || String.valueOf(raw).isEmpty())
                    ? emptyLabel : String.valueOf(raw);

            buckets.computeIfAbsent(key, k -> new GroupAcc(seriesCount)).add(measureRefs, idx);
        }

        // 按第一个度量的值降序。多系列时只用一个口径排 ——
        // 各系列各排各的会让柱子对不上位，图就废了
        List<String> names = new ArrayList<>(buckets.keySet());
        List<GroupAcc> accs = new ArrayList<>(buckets.values());

        Integer[] order = new Integer[names.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> accs.get(b).sortValue(aggregation)
                .compareTo(accs.get(a).sortValue(aggregation)));

        int totalGroups = names.size();
        boolean truncated = totalGroups > limit;

        // 摊平：类别一列，每个系列一列
        List<String> categories = new ArrayList<>(Math.min(totalGroups, limit) + 1);
        List<List<BigDecimal>> seriesValues = new ArrayList<>(seriesCount);
        for (int s = 0; s < seriesCount; s++) {
            seriesValues.add(new ArrayList<>());
        }

        GroupAcc otherAcc = new GroupAcc(seriesCount);
        for (int i = 0; i < totalGroups; i++) {
            int at = order[i];
            if (truncated && i >= limit) {
                // 超出的部分整体并入「其他」，每个系列都要并
                otherAcc.merge(accs.get(at));
                continue;
            }
            categories.add(names.get(at));
            for (int s = 0; s < seriesCount; s++) {
                seriesValues.get(s).add(accs.get(at).seriesValue(s, aggregation));
            }
        }
        if (truncated) {
            categories.add(ChartResult.OTHER);
            for (int s = 0; s < seriesCount; s++) {
                seriesValues.get(s).add(otherAcc.seriesValue(s, aggregation));
            }
        }

        return assemble(categories, seriesValues, measureRefs, aggregation,
                totalGroups, truncated, false);
    }

    /**
     * 数值维度：分桶成直方图
     * 桶边界必须落在整数上，否则 X 轴标签会变成 "19-22.71" 这种没法看的东西
     * 代价是段数不固定 —— 步长取整后，7 段可能变成 4~10 段中的任意一个
     * 分桶后段数本来就少，因此不做「其他」合并
     */
    private ChartResult bucketAndAggregate(List<Integer> indexes, List<Object> dimColumn,
                                           List<MeasureRef> measureRefs, String aggregation) {
        int seriesCount = Math.max(1, measureRefs.size());

        // 1. 求 min / max。非数值落不到任何一个桶里，直接跳过
        BigDecimal min = null;
        BigDecimal max = null;
        for (int idx : indexes) {
            BigDecimal value = toBigDecimal(dimColumn.get(idx));
            if (value == null) {
                continue;
            }
            if (min == null || value.compareTo(min) < 0) {
                min = value;
            }
            if (max == null || value.compareTo(max) > 0) {
                max = value;
            }
        }

        // 兜底：走到这里说明 isNumericColumn 判过是数值列，理论上不会全空
        if (min == null) {
            return new ChartResult(List.of(), List.of(), 0, false, true);
        }

        // 2. 步长与桶边界。start 向下取整到步长的整数倍，保证边界是「好看」的值
        BigDecimal step = niceStep(max.subtract(min));
        BigDecimal start = min.divide(step, 0, RoundingMode.FLOOR).multiply(step);

        int bucketCount = max.subtract(start)
                .divide(step, 0, RoundingMode.CEILING)
                .intValue();
        if (bucketCount < 1) {
            // min == max 时跨度为 0，退化成单桶
            bucketCount = 1;
        }
        if (bucketCount > MAX_BUCKETS) {
            bucketCount = MAX_BUCKETS;
        }

        // 3. 落桶累加。左闭右开 [from, to)，最后一桶闭区间，保证 max 不被漏掉
        GroupAcc[] buckets = new GroupAcc[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new GroupAcc(seriesCount);
        }

        for (int idx : indexes) {
            BigDecimal value = toBigDecimal(dimColumn.get(idx));
            if (value == null) {
                continue;
            }
            int b = value.subtract(start)
                    .divide(step, 0, RoundingMode.FLOOR)
                    .intValue();
            if (b < 0) {
                b = 0;
            }
            if (b >= bucketCount) {
                b = bucketCount - 1;
            }
            buckets[b].add(measureRefs, idx);
        }

        // 4. 生成标签。空桶保留不删 —— 直方图的意义就是看分布，
        // 「这一段没有数据」本身是重要信息
        List<String> categories = new ArrayList<>(bucketCount);
        List<List<BigDecimal>> seriesValues = new ArrayList<>(seriesCount);
        for (int s = 0; s < seriesCount; s++) {
            seriesValues.add(new ArrayList<>(bucketCount));
        }

        for (int i = 0; i < bucketCount; i++) {
            BigDecimal from = start.add(step.multiply(BigDecimal.valueOf(i)));
            BigDecimal to = from.add(step);
            categories.add(formatNumber(from) + "-" + formatNumber(to));
            for (int s = 0; s < seriesCount; s++) {
                seriesValues.get(s).add(buckets[i].seriesValue(s, aggregation));
            }
        }

        return assemble(categories, seriesValues, measureRefs, aggregation,
                bucketCount, false, true);
    }

    // 把摊平后的结果组装成 ChartResult。两种聚合模式共用
    private static ChartResult assemble(List<String> categories, List<List<BigDecimal>> seriesValues,
                                        List<MeasureRef> measureRefs, String aggregation,
                                        int totalGroups, boolean truncated, boolean bucketed) {
        List<ChartSeries> series = new ArrayList<>(seriesValues.size());
        for (int s = 0; s < seriesValues.size(); s++) {
            series.add(new ChartSeries(seriesName(measureRefs, s, aggregation), seriesValues.get(s)));
        }
        return new ChartResult(categories, series, totalGroups, truncated, bucketed);
    }

    // 系列名：count 没有度量列，其余写成 sum(salary) 这样的表达式，前端直接当图例
    private static String seriesName(List<MeasureRef> measureRefs, int index, String aggregation) {
        if ("count".equals(aggregation) || index >= measureRefs.size()) {
            return Messages.get("chart.seriesCount");
        }
        return aggregation + "(" + measureRefs.get(index).name() + ")";
    }

    /**
     * 把理想步长向上取到「好看的值」：1、2、5 乘以 10 的整数次幂
     * 例：跨度 26、目标 7 段 → 理想步长 3.71 → 取 5，得 6 段
     *  跨度 17000  → 理想 2428 → 取 5000，得 4 段
     * 全程用 BigDecimal，不经过 double —— 浮点误差会让 0.1 变成
     * 0.1000000000000000055，桶边界就会出现 "19-22.700000000000003"
     */
    private static BigDecimal niceStep(BigDecimal span) {
        if (span == null || span.signum() <= 0) {
            return BigDecimal.ONE;
        }

        BigDecimal ideal = span.divide(BigDecimal.valueOf(TARGET_BUCKETS), 10, RoundingMode.HALF_UP);

        // 找到不超过 ideal 的最大的 10 的幂
        BigDecimal base = BigDecimal.ONE;
        if (ideal.compareTo(BigDecimal.ONE) >= 0) {
            while (base.multiply(BigDecimal.TEN).compareTo(ideal) <= 0) {
                base = base.multiply(BigDecimal.TEN);
            }
        } else {
            while (base.compareTo(ideal) > 0) {
                base = base.movePointLeft(1);
            }
        }

        // 候选步长：1×base、2×base、5×base，取第一个不小于 ideal 的。
        // 都不满足时落到 10×base（理论上到不了，防御性保留）
        for (BigDecimal mantissa : new BigDecimal[]{
                BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("5")}) {
            BigDecimal candidate = base.multiply(mantissa);
            if (candidate.compareTo(ideal) >= 0) {
                return candidate;
            }
        }
        return base.multiply(BigDecimal.TEN);
    }

    /**
     * 分组累加器
     * 每个度量一个 MeasureAcc，索引与请求里的度量顺序一一对应
     * 用数组而不是 Map，是因为度量个数在分组开始前就定死了
     */
    private static final class GroupAcc {
        private final MeasureAcc[] accs;
        private int rowCount;   // 该分组的行数（count 用）

        GroupAcc(int seriesCount) {
            // 至少留一个位置：count 模式下没有度量列，
            // 但 sortValue / seriesValue 会走到 accs[0]，留个空壳省去到处判空
            accs = new MeasureAcc[Math.max(1, seriesCount)];
            for (int i = 0; i < accs.length; i++) {
                accs[i] = new MeasureAcc();
            }
        }

        /**
         * 累加一行的度量值
         * count 模式传空列表 —— 只需要 rowCount
         * 非数值单元格只计行数、不参与求和，与 aggregateNumber 的判定保持一致
         */
        void add(List<MeasureRef> measureRefs, int rowIndex) {
            rowCount++;
            for (int i = 0; i < measureRefs.size() && i < accs.length; i++) {
                accs[i].add(measureRefs.get(i).column().get(rowIndex));
            }
        }

        // 排序用的值：取第一个度量的聚合值。count 模式取行数
        BigDecimal sortValue(String aggregation) {
            return seriesValue(0, aggregation);
        }

        // 第 seriesIndex 个系列的聚合值
        BigDecimal seriesValue(int seriesIndex, String aggregation) {
            if ("count".equals(aggregation)) {
                return BigDecimal.valueOf(rowCount);
            }
            if (seriesIndex >= accs.length) {
                return BigDecimal.ZERO;
            }
            return accs[seriesIndex].value(aggregation);
        }

        // 把另一个累加器并入自身，用于「其他」的合并。每个度量都要并
        void merge(GroupAcc other) {
            rowCount += other.rowCount;
            for (int i = 0; i < accs.length && i < other.accs.length; i++) {
                accs[i].merge(other.accs[i]);
            }
        }
    }

    /**
     * 单个度量的累加器
     * sum / min / max / 计数分别记录，因为不同聚合方式需要的中间量不同
     * 而且「其他」合并时 avg 必须按行数加权，不能把平均值直接相加
     */
    private static final class MeasureAcc {
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min;
        BigDecimal max;
        int count;      // 参与累加的数值单元格数（avg 的分母）

        void add(Object cell) {
            BigDecimal value = toBigDecimal(cell);
            if (value == null) {
                return;
            }
            sum = sum.add(value);
            count++;
            if (min == null || value.compareTo(min) < 0) {
                min = value;
            }
            if (max == null || value.compareTo(max) > 0) {
                max = value;
            }
        }

        // 按聚合方式算出最终值。空分组一律返回 0，避免排序时 NPE
        BigDecimal value(String aggregation) {
            return switch (aggregation) {
                case "sum" -> sum;
                case "avg" -> count == 0 ? BigDecimal.ZERO
                        : sum.divide(BigDecimal.valueOf(count), AVG_SCALE, RoundingMode.HALF_UP);
                case "max" -> max == null ? BigDecimal.ZERO : max;
                case "min" -> min == null ? BigDecimal.ZERO : min;
                default -> BigDecimal.ZERO;
            };
        }

        void merge(MeasureAcc other) {
            sum = sum.add(other.sum);
            count += other.count;
            if (other.min != null && (min == null || other.min.compareTo(min) < 0)) {
                min = other.min;
            }
            if (other.max != null && (max == null || other.max.compareTo(max) > 0)) {
                max = other.max;
            }
        }
    }
}