package com.tinyquery.parser;

import com.tinyquery.i18n.Messages;

import com.tinyquery.parser.where.Condition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record QueryStatement(String[] selectColumns, String tableName, Condition condition,
                             SortSpecs sort, Integer limit, Integer offset) {

    public QueryStatement {
        // limit 为 null 表示不限制行数。注意不是"稍后由引擎套一个默认上限" ——
        // 引擎不设任何默认上限，null 就是全部命中行（见 QueryEngine#resultRange）
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException(Messages.get("parse.limitNegative", limit));
        }
        // offset 为 null 表示不跳过任何行
        if (offset != null && offset < 0) {
            throw new IllegalArgumentException(Messages.get("parse.offsetNegative", offset));
        }
    }

    public boolean hasWhere() {
        return condition != null;
    }

    // 是否指定了排序。语法的空列表与 null 都算「没写 order by」
    public boolean hasOrderBy() {
        return sort != null && !sort.isEmpty();
    }

    // 是否显式指定了 LIMIT
    public boolean hasLimit() {
        return limit != null;
    }

    // 是否需要跳过若干行。offset 为 null 或 0 都表示不跳 ——
    // offset 0 与不写 offset 完全等价，没必要让调用方区分
    public boolean hasOffset() {
        return offset != null && offset > 0;
    }

    // 聚合函数名。判定「是不是聚合表达式」只用这一处口径，
    // 不要在各个调用点各写一遍 startsWith 链
    private static final String[] AGGREGATE_FUNCTIONS = {"count(", "sum(", "avg(", "max(", "min("};

    /** 判断单个 SELECT 项是否是聚合表达式，如 count(*) / avg(salary) */
    public static boolean isAggregateExpr(String selectColumn) {
        String lower = selectColumn.trim().toLowerCase();
        for (String func : AGGREGATE_FUNCTIONS) {
            if (lower.startsWith(func)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAggregate() {
        for (String col : selectColumns) {
            if (isAggregateExpr(col)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断单个 SELECT 项是否是「看起来像列名」的裸标识符。
     *
     * <p>数字字面量、带引号的字符串是常量，不算列引用 ——
     * {@code select 1, count(*)} 是合法的：聚合结果只有一行，常量在这一行里就是它自己。
     * 但 {@code select name, count(*)} 不行：那一行里没有 {@code name} 可引用。</p>
     *
     * <p>只看语法形态，不去查数据源 —— 解析阶段拿不到列清单。
     * 代价是把 {@code select abc from t}（abc 其实是列名时）也认成列引用，
     * 但那种写法本来就该报「列不存在」，报错时机提前一点不影响正确性。</p>
     */
    private static boolean looksLikeColumnRef(String selectColumn) {
        String col = selectColumn.trim();
        if (col.isEmpty()) {
            return false;
        }
        // 带引号的字符串常量：'abc'
        if (col.length() >= 2 && col.startsWith("'") && col.endsWith("'")) {
            return false;
        }
        // 数字字面量：1、3.14、-5、1e3
        if (col.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) {
            return false;
        }
        // 其余按标识符处理（允许 a.b 这样的限定名）
        return true;
    }

    /**
     * 校验聚合查询的 SELECT 列表，混入列引用时抛异常。
     *
     * <p>聚合结果固定一行，明细列没有「当前行」可引用。此前的行为是静默把列名
     * 当成字符串输出（{@code select name, count(*)} 得到 {@code [name, 3]}），
     * 表头写着 name、值也是 name，用户分不清是查询坏了还是数据本身如此。</p>
     *
     * <p>常量项放行：{@code select 1, count(*)} 得到 {@code [1, 3]}，语义正确。</p>
     */
    public void validateAggregateSelect() {
        if (!isAggregate()) {
            return;
        }
        for (String col : selectColumns) {
            if (isAggregateExpr(col)) {
                continue;
            }
            if (!looksLikeColumnRef(col)) {
                continue;               // 常量，放行
            }
            throw new IllegalArgumentException(
                    Messages.get("parse.mixedSelectColumns", col.trim()));
        }
    }

    public List<AggregationInfo> getAggregations() {
        List<AggregationInfo> list = new ArrayList<>();
        for (String col : selectColumns) {
            String trimmed = col.trim();
            if (isAggregateExpr(trimmed)) {
                int lp = trimmed.indexOf("(");
                // 取最后一个右括号，贴合 sum(f(x)) 这类嵌套写法；
                // 早先取第一个，遇到嵌套会从中间切断
                int rp = trimmed.lastIndexOf(")");
                if (rp < lp) {
                    // 缺右括号时早先会 substring(lp+1, -1) 抛 StringIndexOutOfBoundsException，
                    // 那是个非业务异常，前端只会看到「服务内部错误」，与括号毫无关联
                    throw new IllegalArgumentException(Messages.get("parse.aggregateMissingParen", trimmed));
                }
                // 函数名归一化成小写（SQL 关键字不敏感，COUNT / Count / count 都要认），
                // 但列名必须保持用户原文 —— 早先整串 toLowerCase 会把 sum(Salary) 的
                // Salary 也改掉，于是用户写对了大小写反而报「Unknown column:salary」
                String func = trimmed.substring(0, lp).trim().toLowerCase(Locale.ROOT);
                String field = trimmed.substring(lp + 1, rp).trim();
                if (field.equals("*")) {
                    field = null;
                }
                list.add(new AggregationInfo(func, field, trimmed));
            } else {
                // "const" 现在只服务真正的常量项（select 1, count(*) 里的 1）——
                // 列引用已经在 validateAggregateSelect 里被拦下，走不到这里
                list.add(new AggregationInfo("const", null, trimmed));
            }
        }
        return list;
    }
}