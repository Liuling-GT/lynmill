package com.tinyquery.api;

import java.util.List;

public record QueryResponse(int code, String message, List<String> columns, List<String[]> data,
                            boolean hasMore, int totalRows, int page, int pageSize, long elapsed) {
    // code: 状态码
    // message: 提示信息
    // columns: 结果表头，与 data 中每行的元素顺序一一对应
    // data: 查询结果数据（本页内容）
    // hasMore: 是否还有下一页
    // totalRows: 过滤后命中的总行数（分页前）
    // page: 当前页码
    // pageSize: 每页行数
    // elapsed: 查询耗时
}