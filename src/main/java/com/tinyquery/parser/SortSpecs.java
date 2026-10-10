package com.tinyquery.parser;

import java.util.List;

/**
 * 排序规格：按顺序串联的多列
 *
 * <p>比较时先看第一列，相等才看第二列，依次类推 ——
 * 与 SQL 的 {@code order by a asc, b desc} 一致。</p>
 *
 * <p>早先这里是个单列 record。扩成列表后，执行侧的「整体排序」与
 * 「有界堆」两条分支都只依赖一个 Comparator，串联逻辑收在构造比较器的地方，
 * 两条分支本身不用改。</p>
 *
 * @param specs 排序列，至少一个；空列表表示没有排序要求
 */
public record SortSpecs(List<SortSpec> specs) {

    public SortSpecs {
        // 防御性拷贝：调用方手里的 list 变了不该影响已解析的语句
        specs = List.copyOf(specs);
    }

    public boolean isEmpty() {
        return specs.isEmpty();
    }

    public int size() {
        return specs.size();
    }

    /** 第一排序列。调用方需自行保证列表非空 */
    public SortSpec first() {
        return specs.get(0);
    }
}
