package com.tinyquery.export;

import java.util.ArrayList;
import java.util.List;

/**
 * 版面预估：在真正导出之前，先算出「这张表放不放得下」。
 *
 * <p>为什么要有这个类：A4 纵向的可用宽度只有 515 点，16 列的表每列只能分到
 * 32 点（约 3.6 个中文字）。放不下时 {@link PdfWriter} 会把每列等比压到最窄
 * 再截断，结果是每列只剩两三个字加省略号 —— 用户导完打开才发现没法看，
 * 只能重导一次。</p>
 *
 * <p>所以导出前先把预估结果给用户看：换横向能放下多少、缩字号能放下多少。
 * 这是「导出前知情」，而不是「导出后返工」。</p>
 *
 * <p>估算用的是与 {@link PdfWriter} 完全相同的列宽算法，所以预估结果与最终
 * 版面对得上 —— 两边各写一套的话，预估说放得下、实际却截断了，比不预估还糟。</p>
 */
public final class PdfLayout {

    /** 单列最窄宽度，与 PdfWriter.computeColumnWidths 保持一致 */
    private static final float MIN_COL_W = 50f;

    /** 字号可选档位。低于 0.7 就不是「小字」而是「看不清」了 */
    public static final float[] SCALES = {1.0f, 0.85f, 0.7f};

    private PdfLayout() {
    }

    /**
     * 单列放不下的判定结果。
     *
     * @param columnName   列名
     * @param neededWidth  该列要放下完整表头所需的宽度
     * @param actualWidth  实际分到的宽度
     */
    public record ColumnFit(String columnName, float neededWidth, float actualWidth) {
        public boolean fits() {
            return neededWidth <= actualWidth + 0.5f;   // 半点的容差，避免浮点误差误报
        }
    }

    /**
     * 一次预估的结果。
     *
     * @param landscape  是否横向
     * @param scale      字号缩放
     * @param totalWidth 该档位下的可用宽度
     * @param truncated  会被截断的列（空列表 = 全部放得下）
     */
    public record Estimate(boolean landscape, float scale, float totalWidth,
                           List<ColumnFit> truncated) {

        /** 是否所有列都能完整显示 */
        public boolean allFit() {
            return truncated.isEmpty();
        }

        /** 放不下的列数 */
        public int truncatedCount() {
            return truncated.size();
        }
    }

    /**
     * 预估给定配置下的版面。
     *
     * <p>列宽算法照搬 {@link PdfWriter}：按内容宽度加权分配，单列有下限也有上限。</p>
     *
     * <p><b>关于 scale</b>：它必须传给度量器才算数。一开始我把 scale 只当成本函数的
     * 参数、指望度量器自己知道，结果是三档字号量出来完全一样 —— 因为度量器用的是
     * PdfWriter 的当前字号。所以这里的约定是：度量器接收 scale，由它换算实际字号。</p>
     *
     * @param measurer   文字度量器，会收到 scale 并据此换算字号
     * @param names      列名
     * @param sampleRows 采样数据行，用于估算内容宽度。传空列表则只按表头估
     * @param landscape  是否横向
     * @param scale      字号缩放，0.5~1.0
     */
    public static Estimate estimate(Measurer measurer, String[] names, List<String[]> sampleRows,
                                    boolean landscape, float scale) {
        float pageW = landscape ? PdfWriter.A4_HEIGHT : PdfWriter.A4_WIDTH;
        float avail = pageW - 80f;   // 左右各 40 点边距

        int n = names.length;
        if (n == 0) {
            return new Estimate(landscape, scale, avail, List.of());
        }

        // 与 PdfWriter 一样按内容宽度加权。这里用「完整表头宽度」作为该列的需求，
        // 因为它决定了用户能不能认出这一列是什么 —— 数据被截断只是精度损失，
        // 表头被截断则整列失去意义
        float[] weight = new float[n];
        float[] needed = new float[n];
        for (int i = 0; i < n; i++) {
            needed[i] = measurer.width(names[i], true, scale);
            weight[i] = needed[i];
        }

        int sample = Math.min(sampleRows.size(), 200);
        for (int r = 0; r < sample; r++) {
            String[] row = sampleRows.get(r);
            for (int i = 0; i < n && i < row.length; i++) {
                weight[i] = Math.max(weight[i], measurer.width(row[i], false, scale));
            }
        }

        float total = 0;
        for (float w : weight) {
            total += w;
        }
        if (total <= 0) {
            total = n;
        }

        float maxW = avail * 0.45f;
        float[] actual = new float[n];
        for (int i = 0; i < n; i++) {
            float w = weight[i] / total * avail;
            actual[i] = Math.max(MIN_COL_W, Math.min(maxW, w));
        }

        // 归一化到可用宽度，与 PdfWriter 一致
        float sum = 0;
        for (float w : actual) {
            sum += w;
        }
        float k = avail / sum;
        for (int i = 0; i < n; i++) {
            actual[i] *= k;
        }

        List<ColumnFit> truncated = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ColumnFit fit = new ColumnFit(names[i], needed[i], actual[i]);
            if (!fit.fits()) {
                truncated.add(fit);
            }
        }

        return new Estimate(landscape, scale, avail, truncated);
    }

    /**
     * 一次性算出所有档位的预估，供前端列出「换横版/缩字号能解决多少」。
     *
     * <p>顺序固定为：纵向 1.0 → 纵向 0.85 → 横向 1.0 → 横向 0.85 → 横向 0.7。
     * 从改动最小的档位开始试，用户优先看到代价最低的方案。</p>
     */
    public static List<Estimate> estimateAll(Measurer measurer, String[] names,
                                             List<String[]> sampleRows) {
        List<Estimate> out = new ArrayList<>();
        for (float s : SCALES) {
            out.add(estimate(measurer, names, sampleRows, false, s));
        }
        for (float s : SCALES) {
            out.add(estimate(measurer, names, sampleRows, true, s));
        }
        return out;
    }

    /**
     * 文字度量：给出宽度（点）。
     *
     * <p>必须接收 scale 并据此换算字号 —— 否则不同字号档位量出来是同一个宽度，
     * 「缩字号能不能解决」这个判断就永远失真。</p>
     */
    public interface Measurer {
        float width(String text, boolean header, float scale);
    }
}
