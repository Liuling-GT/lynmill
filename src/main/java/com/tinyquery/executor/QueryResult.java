package com.tinyquery.executor;

import java.util.List;

/**
 * 查询执行结果
 * @param rows      结果表格
 * @param totalRows 过滤后命中的总行数（截断前）
 * @param truncated 是否因行数上限被截断
 */
public record QueryResult(List<String[]> rows, int totalRows, boolean truncated) {
}
