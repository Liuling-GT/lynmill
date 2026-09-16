package com.tinyquery.parser;

/**
 * 排序规格
 * 当前只支持单列排序。将来若扩展为多列，把 QueryStatement 里的本类型
 * 换成 List;SortSpec; 即可，比较器按顺序串联
 * @param column 排序列名
 * @param desc   true 为降序，false 为升序
 */
public record SortSpec(String column, boolean desc) {
}
