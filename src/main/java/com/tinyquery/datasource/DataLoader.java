package com.tinyquery.datasource;

import com.tinyquery.executor.ColumnStore;

import java.util.List;

/**
 * 数据源加载器。每支持一种数据源，实现一个本接口的 Spring Bean 即可。
 * 本接口同时承担「描述自己」「加载数据」「自检」三个职责，这是有意内聚的：
 * 这三件事都只有加载器自己最清楚。放在这里之后，新增数据源无需改动
 * DataSourceManager、Controller 和前端任何代码
 * 注意：load 失败必须抛 DataSourceException，不要返回空的 ColumnStore，
 * 否则 register 会误报成功，问题被推迟到查询时才暴露
 */
public interface DataLoader {

    /** 类型标识，如 "csv" / "mysql"。全局唯一，重复会被启动校验拦下 */
    String type();

    /** 类型展示名称 */
    String label();

    /** 该类型需要的配置项清单，驱动前端表单动态渲染 */
    List<FieldSpec> fields();

    /**
     * 测试配置是否可用
     * 只做常量级探测（能否建连、目标对象是否存在），不加载数据
     * 因此可以放心让用户在表单里反复点击。返回失败结果而不是抛异常
     * 是为了让「测试未通过」成为正常的业务反馈而非错误
     */
    ConnectionTestResult test(DataSourceConfig config);

    /** 加载数据到内存 */
    ColumnStore load(DataSourceConfig config);
}