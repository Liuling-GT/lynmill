package com.tinyquery.datasource.spec;

import com.tinyquery.executor.ColumnStore;

import java.util.List;
import java.util.Map;

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

    /**
     * 探测某个配置项的可选值，供表单把它渲染成下拉框。
     *
     * <p>为什么放在接口上而不是让 Controller 认 "xlsx"：本接口开篇就写明
     * 「新增数据源无需改动 DataSourceManager、Controller 和前端任何代码」。
     * 一旦 Controller 里出现 {@code if ("xlsx".equals(type))}，这条约定就破了，
     * 以后每加一种「有可选项」的数据源（数据库的表名、schema 名等等）都要再改一次
     * Controller。做成通用能力之后，前端也只认「这个字段可探测」，
     * 不认具体是哪一种数据源。</p>
     *
     * <p>默认返回空 map = 「本类型没有可探测的字段」，所以已有的加载器
     * 一行都不用改。</p>
     *
     * <p>返回值是「字段名 → 可选值列表」，因为一次探测可能同时得出多个字段的选项
     * （例如将来数据库的 schema 与 table）。约定：只包含字符串列表，
     * 前端拿到的不是字符串列表就忽略该项。</p>
     *
     * <p>实现方应当只做常量级探测（打开文件/建连读元数据），不加载数据 ——
     * 用户会在表单里反复点击。</p>
     *
     * @param config 当前表单里已填的配置。探测通常依赖其中的某个字段（如 xlsx 的 path）
     * @return 字段名 → 可选值。没有可探测内容时返回空 map
     */
    default Map<String, List<String>> probeOptions(DataSourceConfig config) {
        return Map.of();
    }
}
