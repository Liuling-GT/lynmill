package com.tinyquery.api;

import java.util.List;

/**
 * 图表聚合请求
 * 维度与度量来自界面上的下拉框，不是 SQL 里的 GROUP BY
 * 因此不需要动 parser
 * @param sql         与表格查询相同的 SQL，图表在它的结果集上聚合
 * @param dimension   维度列名（分组键）。数值列会自动分桶
 * @param measures    度量列名，最多两个；aggregation 为 count 时忽略
 * @param aggregation 聚合方式：sum / avg / count / max / min，两个度量共用
 * @param topN        文本维度下最多返回多少个分组，超出部分合并为「其他」；
 *                    为 null 时取默认值。数值维度分桶时忽略此参数
 */
public record ChartRequest(String sql, String dimension, List<String> measures,
                           String aggregation, Integer topN) {
}