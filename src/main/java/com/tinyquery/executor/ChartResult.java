package com.tinyquery.executor;

import java.util.List;

/**
 * 图表聚合结果
 * 后端在全部命中行上聚合，只返回绘图所需的数据
 * 与分页无关 —— 图表看到的始终是完整数据，不随表格翻页而变
 * @param categories  维度值列表。文本维度是分组值（北京/上海），
 *                    数值维度是分桶区间（15-20 / 20-25）
 * @param series      数据系列列表，每个度量一条
 * @param totalGroups 分组总数（「其他」合并前）
 * @param truncated   是否因超出上限而把尾部合并成了「其他」。
 *                    分桶模式下恒为 false —— 段数本来就少
 * @param bucketed    是否走了数值分桶。前端据此提示用户 X 轴为何是区间
 */
public record ChartResult(List<String> categories, List<ChartSeries> series,
                          int totalGroups, boolean truncated, boolean bucketed) {

    /** 尾部合并后的类别名 */
    public static final String OTHER = "其他";
}