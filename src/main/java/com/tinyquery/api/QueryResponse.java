package com.tinyquery.api;

import java.util.List;

public record QueryResponse(int code, String message, String hint, List<String> columns,
                            List<String> columnTypes,
                            List<String[]> data, boolean hasMore, int totalRows, int page, int pageSize,
                            long elapsed) {
    // code: 状态码
    // message: 提示信息
    // hint: 补充提示（前端用紫色并排展示在 message 下方），无提示时为 null。
    //       典型场景：列名只差大小写，如「您可能想要访问 "Salary"」
    // columns: 结果表头，与 data 中每行的元素顺序一一对应
    // columnTypes: 与 columns 一一对应的值类型（number / string / empty）
    // data: 查询结果数据（本页内容）
    // hasMore: 是否还有下一页（等价于 totalRows > page * pageSize）
    // totalRows: 过滤后命中的总行数（分页前）
    // page: 当前页码
    // pageSize: 每页行数
    // elapsed: 本次查询耗时（毫秒），由 QueryTimer 用 nanoTime 测得。
    //          不足 1ms 时是 0（真实取整，不是没测到）
    //
    // 【hasMore / elapsed 目前没有界面消费方，这是有意保留的】
    //   · hasMore 会随结果一起透出到「JSON」标签页（那是接口响应的原样镜像），
    //     界面上真正用来翻页的是 totalRows 与 pageSize，没有依赖它去禁用「下一页」——
    //     最后一页多翻一次只会拿到空数组，无害。留着是因为它是分页接口的常规字段，
    //     也方便外部调用方判断该不该继续翻。
    //   · elapsed 后端算了并返回，前端不显示。留着是为了排查性能问题时有据可查
    //     （接口层面能直接看到耗时），不必临时加日志。
    //   两者都属于「不算错、但也没有人读」，不要误以为是漏接了前端逻辑。
}