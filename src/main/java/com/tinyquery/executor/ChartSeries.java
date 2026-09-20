package com.tinyquery.executor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 图表的一条数据系列
 * 一个度量对应一个系列：柱状图里是一组同色的柱子，折线图里是一条线。
 * 饼图表示整体占比，只会有一个系列
 * @param name   系列名，形如 sum(salary)，前端用作图例与坐标轴名
 * @param values 与 ChartResult.categories 一一对应的聚合值
 */
public record ChartSeries(String name, List<BigDecimal> values) {
}