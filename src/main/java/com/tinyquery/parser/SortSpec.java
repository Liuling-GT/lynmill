package com.tinyquery.parser;

/**
 * 单个排序列
 * 多列排序见 SortSpecs —— 本类型只描述「哪一列、什么方向」
 * @param column 排序列名
 * @param desc   true 为降序，false 为升序
 */
public record SortSpec(String column, boolean desc) {
}
