package com.tinyquery.api;

/**
 * 查询请求
 * page / pageSize 都为 null 时不用分页，一次返回全部命中行
 * （不设上限 —— 大表上可能很大，分页就是为了避免这种情况）
 * 分页参数走 API 而不是写进 SQL，是为了避开 SQLParser 对 LIMIT offset 的切分歧义
 * @param sql      待执行的 SQL
 * @param page     页码，从 1 开始；为 null 表示不分页
 * @param pageSize 每页行数；为 null 时由后端取默认值
 */
public record QueryRequest(String sql, Integer page, Integer pageSize) {
}
