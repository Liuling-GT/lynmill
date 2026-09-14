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
        return new QueryStatement(columns, tableName, condition, limit);
    }

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
