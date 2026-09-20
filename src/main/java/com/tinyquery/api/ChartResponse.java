package com.tinyquery.api;

import com.tinyquery.executor.ChartSeries;

import java.util.List;

/**
 * 图表聚合响应。
 *
 * <p>与 QueryResponse 分开，是因为它不携带表格数据 ——
 * 图表只需要类别与各系列的聚合值，返回整个结果集是浪费。</p>
 *
 * @param code        0 表示成功，非 0 表示失败
 * @param message     提示信息
 * @param categories  维度值列表
 * @param series      数据系列列表，每个度量一条
 * @param totalGroups 分组总数（「其他」合并前）
 * @param truncated   是否发生了「其他」合并
 * @param bucketed    是否走了数值分桶（X 轴是区间而非原始值）
 * @param elapsed     耗时（毫秒）
 */
public record ChartResponse(int code, String message, List<String> categories,
                            List<ChartSeries> series, int totalGroups,
                            boolean truncated, boolean bucketed, long elapsed) {
}
