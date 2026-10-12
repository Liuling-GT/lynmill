package com.tinyquery.parser;

import com.tinyquery.i18n.Messages;

import java.util.Collection;
import java.util.Locale;

/**
 * 名称纠错：用户写错大小写时，给出「您可能想要访问 X」的提示。
 *
 * <p>只做提示，不改变查询语义 —— 列名与数据源名依旧大小写敏感，
 * 写错就是查不到。这里只是帮用户在报错之余看见正确的写法，
 * 免得对着「Unknown column:salary」和表里明晃晃的 {@code Salary} 干瞪眼。</p>
 */
public final class NameSuggester {

    private NameSuggester() {
        // 工具类，不实例化
    }

    /**
     * 在候选名单里找「仅大小写不同」的那一个。
     *
     * <p>候选恰好一个才返回；没有候选、或有多个候选都返回 null ——
     * 表里同时存在 {@code Salary} 与 {@code salary} 时无从判断用户想要哪个，
     * 猜错不如不猜。</p>
     *
     * @param requested  用户写的名字
     * @param candidates 可供选择的名字（表里的列名 / 已注册的数据源名）
     * @return 唯一的大小写变体；无唯一答案时返回 null
     */
    public static String suggest(String requested, Collection<String> candidates) {
        if (requested == null || requested.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }

        String lower = requested.trim().toLowerCase(Locale.ROOT);
        String hit = null;
        for (String candidate : candidates) {
            if (candidate == null || candidate.equals(requested)) {
                continue;
            }
            if (candidate.toLowerCase(Locale.ROOT).equals(lower)) {
                if (hit != null) {
                    // 已有候选，说明不止一个 —— 不猜
                    return null;
                }
                hit = candidate;
            }
        }
        return hit;
    }

    /**
     * 把候选拼成给用户看的一句话。
     *
     * <p>候选名原样嵌进文案（引号里的那个），不翻译 —— 它是表里真实存在的名字。</p>
     */
    public static String describe(String requested, String suggestion) {
        if (suggestion == null) {
            return null;
        }
        return Messages.get("hint.didYouMean", suggestion);
    }
}
