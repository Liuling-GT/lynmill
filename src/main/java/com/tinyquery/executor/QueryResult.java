package com.tinyquery.executor;

import java.util.List;

/**
 * 查询执行结果
 * 相比直接返回 List;String[];，额外携带列名、列类型与分页信息，
 * 让调用方无需自行推断表头，也能判断是否还有下一页
 * @param columns     结果表头。select * 时取自 ColumnStore 的列名，
 *                    聚合时取自聚合表达式原文（如 count(*)），
 *                    显式列时取自 SELECT 列表
 * @param columnTypes 与 columns 一一对应的值类型：number / string / empty。
 *                    供前端选择图表轴，避免靠字符串试解析去猜
 * @param rows        结果表格，每行的元素顺序与 columns 一一对应
 * @param totalRows   结果集的总行数 —— 过滤命中、跳过 OFFSET、套用 LIMIT 之后的行数。
 *                    分页只影响本次返回哪一段，不影响它；前端靠它算总页数
 * @param hasMore     是否还有下一页数据。等价于「本次没有取完」
 */
public record QueryResult(List<String> columns, List<String> columnTypes,
                          List<String[]> rows, int totalRows, boolean hasMore) {
}
