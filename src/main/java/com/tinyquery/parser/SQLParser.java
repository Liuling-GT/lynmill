package com.tinyquery.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SQLParser {

    // LIMIT 子句必须从语句末尾剥离，否则会被 WHERE 或表名的切分吃掉。
    // 只接受 LIMIT n 形式；LIMIT offset, n 属于分页语义，本轮暂不支持。
    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("\\s+limit\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_OFFSET_PATTERN =
            Pattern.compile("\\s+limit\\s+\\d+\\s*,\\s*\\d+\\s*$", Pattern.CASE_INSENSITIVE);

    // ORDER BY 同样从末尾剥离，且必须在 LIMIT 之后剥离 ——
    // 先把 LIMIT 摘掉，ORDER BY 才落到语句末尾
    private static final Pattern ORDER_BY_PATTERN =
            Pattern.compile("\\s+order\\s+by\\s+(\\S+)(?:\\s+(asc|desc))?\\s*$", Pattern.CASE_INSENSITIVE);

    // 用于判断「出现了 order by 但没被上面的模式匹配到」的写法
    private static final Pattern CONTAINS_ORDER_BY =
            Pattern.compile("\\border\\s+by\\b", Pattern.CASE_INSENSITIVE);

    public QueryStatement parse(String sql) {

        // 预处理
        sql = sql.replaceAll("(?i)\\band\\b", " and ")
                .replaceAll("(?i)\\bor\\b", " or ")
                .replaceAll("(?i)\\bnot\\b", " not ")
                .replaceAll("(>=|<=|!=|<>|>|<|=)", " $1 ")
                .replaceAll("\\s+", " ")
                .trim();

        if (!sql.toLowerCase().contains("select")) {
            throw new IllegalArgumentException("error:Missing keyword SELECT");
        }
        if (!sql.toLowerCase().contains("from")) {
            throw new IllegalArgumentException("error:Missing keyword FROM");
        }

        // LIMIT 必须在 WHERE 之前剥离：WHERE 分支会把「where 之后的所有内容」
        // 整段当作条件，LIMIT 留在里面会让条件解析直接失败
        Integer limit = parseLimit(sql);
        if (limit != null) {
            Matcher matcher = LIMIT_PATTERN.matcher(sql);
            matcher.find();
            sql = sql.substring(0, matcher.start()).trim();
        }

        // ORDER BY 在 LIMIT 之后剥离（此时它才位于末尾），同样必须在 WHERE 之前
        SortSpec sort = parseOrderBy(sql);
        if (sort != null) {
            Matcher matcher = ORDER_BY_PATTERN.matcher(sql);
            matcher.find();
            sql = sql.substring(0, matcher.start()).trim();
        }

        // where 部分
        String wherePart = null;
        int whereIndex = sql.toLowerCase().indexOf("where");
        if (whereIndex != -1) {
            wherePart = sql.substring(whereIndex + 5).trim();
            sql = sql.substring(0, whereIndex).trim();
        }

        // 列名
        String selectPart = sql.substring(
                sql.toLowerCase().indexOf("select") + 6,
                sql.toLowerCase().indexOf("from")
        ).trim();

        if (selectPart.isEmpty()) {
            throw new IllegalArgumentException("error:No column specified between SELECT and FROM");
        }

        String[] columns;
        if ("*".equals(selectPart)) {
            columns = new String[]{"*"};
        } else {
            String[] raw = selectPart.split(",");
            columns = new String[raw.length];
            for (int i = 0; i < raw.length; i++) {
                String col = raw[i].trim();
                columns[i] = col;
                if (columns[i].isEmpty()) {
                    throw new IllegalArgumentException("error:Empty column name. Please check for extra commas.");
                }
            }
        }

        // 表名
        String tableName = sql.substring(sql.toLowerCase().indexOf("from") + 4).trim();

        if (tableName.isEmpty()) {
            throw new IllegalArgumentException("error:Missing table name after FROM");
        }

        // 解析 where
        Condition condition = null;
        if (wherePart != null && !wherePart.isEmpty()) {
            condition = parseCondition(wherePart);
        }
        return new QueryStatement(columns, tableName, condition, sort, limit);
    }

    /**
     * 解析语句末尾的 LIMIT 子句。
     *
     * <p>用「行首 limit + 数字 + 语句结束」的锚定匹配，因此
     * {@code WHERE note = 'limit 10'} 这类字符串字面量不会被误判。</p>
     *
     * @return 解析出的行数上限；未指定时返回 null
     */
    private Integer parseLimit(String sql) {
        // offset 形式属于分页语义，静默忽略 offset 会给出错误结果，故明确拒绝
        if (LIMIT_OFFSET_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException("error:LIMIT 暂不支持 offset 形式，请使用 LIMIT n");
        }

        Matcher matcher = LIMIT_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return null;
        }

        String raw = matcher.group(1);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("error:LIMIT 数值超出范围：" + raw);
        }
    }

    /**
     * 解析语句末尾的 ORDER BY 子句。
     *
     * <p>同样用锚定匹配，避免 {@code WHERE note = 'a order by b'} 这类字面量被误判。
     * 排序列名取第一个非空白片段，方向缺省为升序。</p>
     *
     * @return 排序规格；未指定时返回 null
     */
    private SortSpec parseOrderBy(String sql) {
        Matcher matcher = ORDER_BY_PATTERN.matcher(sql);
        if (!matcher.find()) {
            // 没有匹配到完整形式，但语句里确实出现了 order by，
            // 说明写法不受支持。不静默忽略，否则表名会被污染成「t order by」这种难以理解的形态
            if (CONTAINS_ORDER_BY.matcher(sql).find()) {
                throw new IllegalArgumentException(
                        "error:ORDER BY 写法不受支持，只支持单列排序，形如 order by 列名 [asc|desc]");
            }
            return null;
        }

        String column = matcher.group(1).trim();
        // 方向缺省为升序
        boolean desc = "desc".equalsIgnoreCase(matcher.group(2));
        return new SortSpec(column, desc);
    }

    private Condition parseCondition(String expr) {
        expr = expr.trim();

        // OR 优先级最低，先找
        int orIndex = findTopLevelKeyword(expr, "or");
        if (orIndex != -1) {
            Condition left = parseCondition(expr.substring(0, orIndex));
            Condition right = parseCondition(expr.substring(orIndex + 2));
            return new OrCondition(left, right);
        }

        // AND
        int andIndex = findTopLevelKeyword(expr, "and");
        if (andIndex != -1) {
            Condition left = parseCondition(expr.substring(0, andIndex));
            Condition right = parseCondition(expr.substring(andIndex + 3));
            return new AndCondition(left, right);
        }

        // NOT
        if (expr.toLowerCase().startsWith("not ")) {
            return new NotCondition(parseCondition(expr.substring(4)));
        }

        // 简单条件：field op value
        String[] parts = expr.split(" ");
        if (parts.length < 3) {
            throw new IllegalArgumentException("error:Invalid WHERE clause format. Expected: field operator value (e.g., age > 25)");
        }

        String field = parts[0];
        String op = parts[1];
        String value = parts[2];

        if (!op.matches("(>=|<=|!=|<>|>|<|=)")) {
            throw new IllegalArgumentException("error:Unsupported operator: " + op + ". Supported operators: >=，<=，!=，<>，>，<，=");
        }

        if (value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        }

        return new SimpleCondition(field, op, value);
    }

    // 找到最外层关键字位置
    private int findTopLevelKeyword(String expr, String keyword) {
        int depth = 0;
        String lower = expr.toLowerCase();
        for (int i = 0; i <= lower.length() - keyword.length(); i++) {
            char c = lower.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && lower.startsWith(keyword, i)) {
                boolean leftOk = i == 0 || lower.charAt(i - 1) == ' ';
                boolean rightOk = i + keyword.length() >= lower.length()
                        || lower.charAt(i + keyword.length()) == ' ';
                if (leftOk && rightOk) return i;
            }
        }
        return -1;
    }
}
