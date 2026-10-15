package com.tinyquery.api;

import java.util.List;

/**
 * PDF 版面预估的返回。
 *
 * <p>回答用户的一个具体问题：「我这张表导出来会不会被截断？」</p>
 *
 * <p>背景：A4 纵向可用宽度只有 515 点，16 列的表每列只分到 32 点
 * （约 3.6 个中文字），于是每列都被截成「帖… 发… 内…」这种样子，
 * 连列名都认不出来。用户导完打开才发现没法看，只能重导。</p>
 *
 * <p>所以导出前先把各档位的预估给出来，让用户一眼看到「换横向就好了」，
 * 而不是自己猜。这对用户是省一次返工，对产品是少一个「这功能不好用」的印象。</p>
 *
 * @param columns  总列数
 * @param rows     总行数
 * @param options  各档位的预估，按「代价从低到高」排列：
 *                 纵向 1.0 → 纵向 0.85 → 纵向 0.7 → 横向 1.0 → 横向 0.85 → 横向 0.7
 * @param best     推荐档位的下标，指向 options 里的最优选择；全都放不下时为 -1。
 *                 判断标准是「第一个能全部放下的档位」，没有则在横向里挑放不下最少的一个
 */
public record PdfLayoutResponse(int columns,
                                int rows,
                                List<Option> options,
                                int best) {

    /**
     * 一个档位的预估结果。
     *
     * @param landscape      是否横向
     * @param scale          字号缩放，1.0 = 原始大小
     * @param availableWidth 该方向下的可用宽度（点）
     * @param truncatedCount 会被截断的列数，0 = 全部放得下
     * @param truncatedNames 会被截断的列名，最多给前若干个 —— 全给出来弹框会撑爆
     * @param fits           是否全部放得下
     */
    public record Option(boolean landscape,
                         float scale,
                         float availableWidth,
                         int truncatedCount,
                         List<String> truncatedNames,
                         boolean fits) {
    }
}
