package com.tinyquery.parser;

import java.util.ArrayList;
import java.util.List;

public record QueryStatement(String[] selectColumns, String tableName, Condition condition, Integer limit) {

    public QueryStatement {
        // limit 为 null 表示未显式指定，由 QueryEngine 套用默认上限
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("error:LIMIT 不能为负数：" + limit);
        }
    }

    public boolean hasWhere() {
        return condition != null;
    }

    // 是否显式指定了 LIMIT
    public boolean hasLimit() {
        return limit != null;
    }

    public boolean isAggregate() {
        for (String col : selectColumns) {
            String lower = col.trim().toLowerCase();
            if (lower.startsWith("count(") ||
                    lower.startsWith("sum(") ||
                    lower.startsWith("avg(") ||
                    lower.startsWith("max(") ||
                    lower.startsWith("min(")) {
                return true;
            }
        }
        return false;
    }

    public List<AggregationInfo> getAggregations() {
        List<AggregationInfo> list = new ArrayList<>();
        for (String col : selectColumns) {
            String lower = col.trim().toLowerCase();
            if (lower.startsWith("count(") || lower.startsWith("sum(") ||
                    lower.startsWith("avg(") || lower.startsWith("max(") || lower.startsWith("min(")) {
                String func = lower.substring(0, lower.indexOf("("));
                String field = lower.substring(lower.indexOf("(") + 1, lower.indexOf(")")).trim();
                if (field.equals("*")) {
                    field = null;
                }
                list.add(new AggregationInfo(func, field, col.trim()));
            } else {
                list.add(new AggregationInfo("const", null, col.trim()));
            }
        }
        return list;
    }
}