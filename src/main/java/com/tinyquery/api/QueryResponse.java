package com.tinyquery.api;

import java.util.List;

public record QueryResponse(int code, String message, List<String[]> data,
                            boolean truncated, int totalRows, long elapsed) {
    // code: 状态码
    // message: 提示信息
    // data: 查询结果数据
    // truncated: 结果是否因行数上限被截断
    // totalRows: 过滤后命中的总行数（截断前）
    // elapsed: 查询耗时
}