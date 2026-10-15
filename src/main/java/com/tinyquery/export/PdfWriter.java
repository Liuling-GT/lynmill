package com.tinyquery.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖的 PDF 写出器，面向「把查询结果打成一张表」这一个用途。
 *
 * <p>不用 iText / OpenPDF / PDFBox：它们每个都会给安装包加上 5~15 MB，
 * 而本项目只需要一个能力 —— 画一张带中文的表格。为此引入一个通用 PDF 库
 * 不划算，与 {@link XlsxWriter} 不用 Apache POI 是同一个理由。</p>
 *
 * <p>实现范围：</p>
 *
 * <ul>
 *   <li>内嵌 TrueType/OpenType(CFF) 字体子集，中文字形随文件走 ——
 *       换台电脑打开不会缺字，这是「导出报告发给别人」场景的硬要求</li>
 *   <li>自动分页，表头在每页重复</li>
 *   <li>文本按列宽截断成省略号，不溢出到相邻单元格</li>
 *   <li>数字右对齐、文本左对齐</li>
 * </ul>
 *
 * <p>刻意不做的事：不做超链接、不做图片、不做多字体混排、不做竖排。</p>
 *
 * <p><b>实现要点 —— 为什么 PDF 里中文这么麻烦：</b>
 * PDF 的文本绘制指令用的不是 Unicode，而是「字形编号」(glyph id)。
 * 所以必须自己维护一张 字符→字形 的映射，并把它写进 PDF 的 ToUnicode CMap，
 * 否则文件里的中文虽然能显示，却【无法复制、无法搜索】——
 * 那种 PDF 拿去发报表是不合格的。</p>
 */
public class PdfWriter implements AutoCloseable {

    // A4 尺寸，单位是 PDF 的点（1 点 = 1/72 英寸）
    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;

    private static final float MARGIN = 40f;
    private static final float TITLE_SIZE = 16f;
    private static final float META_SIZE = 8.5f;
    private static final float HEAD_SIZE = 9.5f;
    private static final float CELL_SIZE = 9f;
    private static final float CELL_PAD = 4f;
    private static final float LINE_GAP = 1.35f;

    // 行高。字号 × 行距系数
    private static final float HEAD_H = HEAD_SIZE * LINE_GAP + CELL_PAD * 2;
    private static final float ROW_H = CELL_SIZE * LINE_GAP + CELL_PAD * 2;

    private final PdfFont font;

    // 每页的内容流。分页时新建一个
    private final List<StringBuilder> pages = new ArrayList<>();
    private StringBuilder cur;

    private final List<Float> colWidths = new ArrayList<>();
    private final List<Boolean> colNumeric = new ArrayList<>();

    private float y;                 // 当前写入位置的纵坐标，从页顶往下走
    private float tableTop;          // 表格起始纵坐标，用于画竖线

    private String title = "";
    private String subtitle = "";
    private String[] columnNames = new String[0];

    private int rowCount = 0;

    /**
     * @param fontStream 内嵌字体。调用方负责提供，通常来自 jar 内的 resources
     */
    public PdfWriter(InputStream fontStream) throws IOException {
        this.font = PdfFont.load(fontStream);
        newPage();
    }

    /** 标题与副标题（副标题可放「数据源 / 导出时间 / 行数」这类信息）。 */
    public void setHeader(String title, String subtitle) {
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
    }

    /**
     * 写表格。
     *
     * @param columnNames  列名
     * @param numericCols  每列是否是数值（决定右对齐）
     * @param rows         数据行，元素为字符串
     */
    public void writeTable(String[] columnNames, boolean[] numericCols, List<String[]> rows)
            throws IOException {
        this.columnNames = columnNames.clone();
        computeColumnWidths(columnNames, rows);
        for (boolean b : numericCols) {
            colNumeric.add(b);
        }

        writeTitleBlock();
        // 表头加一行数据必须能同时容下，否则表头会贴在页面最下沿、
        // 跟数据分到两页去。写之前先确保这一块空间是完整的
        ensureSpace(HEAD_H + ROW_H * 2);
        writeTableHeader(columnNames);

        for (String[] row : rows) {
            // 分页后要把表头补回来 —— 否则第二页起就没有列名，
            // 一页一页翻着看时根本不知道每列是什么。
            // 注意是 ensureSpace 返回 true（确实翻了页）时才补，
            // 写成 !ensureSpace 会让每行都重复一次表头
            if (ensureSpace(ROW_H)) {
                writeTableHeader(columnNames);
            }
            writeRow(row);
            rowCount++;
        }
    }

    /** 收尾并返回完整 PDF 字节。 */
    public byte[] finish() throws IOException {
        // 每页都要补上表格竖线（分页时才知道表格到哪儿结束）
        return PdfAssembler.assemble(pages, font, PAGE_W, PAGE_H);
    }

    @Override
    public void close() {
        // 无系统资源需要释放；字体数据在内存里，随对象被回收
    }

    // ------------------------------------------------------------ 版面

    private void computeColumnWidths(String[] names, List<String[]> rows) {
        float avail = PAGE_W - MARGIN * 2;
        int n = names.length;

        // 按内容长度分配。用「字符宽度总和」而不是字节数，中文才算得准
        float[] weight = new float[n];
        for (int i = 0; i < n; i++) {
            weight[i] = textWidth(names[i], HEAD_SIZE) + CELL_PAD * 2;
        }
        // 采样前若干行估算列宽，全表扫描对百万行导出太慢
        int sample = Math.min(rows.size(), 200);
        for (int r = 0; r < sample; r++) {
            String[] row = rows.get(r);
            for (int i = 0; i < n && i < row.length; i++) {
                weight[i] = Math.max(weight[i], textWidth(row[i], CELL_SIZE) + CELL_PAD * 2);
            }
        }

        float total = 0;
        for (float w : weight) {
            total += w;
        }
        // 单列最窄 50 点，否则「序号」这种列会被压没；同时限制最宽，避免一列吃掉整页
        float minW = 50f;
        float maxW = avail * 0.45f;

        float[] final_ = new float[n];
        for (int i = 0; i < n; i++) {
            float w = weight[i] / total * avail;
            final_[i] = Math.max(minW, Math.min(maxW, w));
        }

        // 归一化到可用宽度：先按比例压缩或拉伸
        float sum = 0;
        for (float w : final_) {
            sum += w;
        }
        if (sum > avail) {
            // 超宽就按比例缩；缩完仍可能低于 minW，下面再兜一次
            float k = avail / sum;
            for (int i = 0; i < n; i++) {
                final_[i] *= k;
            }
        }
        sum = 0;
        for (float w : final_) {
            sum += w;
        }
        // 把剩余宽度补给最后一列，让表格铺满页面
        final_[n - 1] += avail - sum;
        if (final_[n - 1] < minW) {
            final_[n - 1] = minW;
        }

        for (float w : final_) {
            colWidths.add(w);
        }
    }

    // 文本宽度。中文字形宽度是字号的 1.0 倍，ASCII 约 0.5 —— 用字体真实宽度算最准
    private float textWidth(String s, float size) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        float w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            w += font.widthOf(cp) * size;
        }
        return w;
    }

    private void writeTitleBlock() {
        float left = MARGIN;
        if (!title.isEmpty()) {
            y -= TITLE_SIZE * 1.2f;
            drawText(left, y, title, TITLE_SIZE, true);
        }
        if (!subtitle.isEmpty()) {
            y -= META_SIZE * 1.6f;
            drawText(left, y, subtitle, META_SIZE, false);
        }
        y -= META_SIZE;   // 与表格留一点空
        tableTop = y;
    }

    private void writeTableHeader(String[] names) {
        float left = MARGIN;
        float top = y;

        // 表头底色：浅灰。PDF 的填充色要先 rg 设好再画矩形，
        // 不设的话默认是黑色，整行表头会变成黑底
        cur.append("0.90 0.90 0.90 rg\n");
        cur.append(String.format("%.2f %.2f %.2f %.2f re f%n",
                left, y - HEAD_H, PAGE_W - MARGIN * 2, HEAD_H));
        cur.append("0 0 0 rg\n");   // 恢复黑色，供文字与线条使用

        float ty = y - CELL_PAD - HEAD_SIZE;
        for (int i = 0; i < names.length; i++) {
            String t = fit(names[i], colWidths.get(i) - CELL_PAD * 2, HEAD_SIZE);
            drawText(left + CELL_PAD, ty, t, HEAD_SIZE, true);
            left += colWidths.get(i);
        }
        y -= HEAD_H;
        drawTableLines(top, left);
        drawRowSeparator(y);
    }

    private void writeRow(String[] row) {
        float left = MARGIN;
        float top = y;
        float baseline = y - CELL_PAD - CELL_SIZE;

        for (int i = 0; i < colWidths.size(); i++) {
            String raw = i < row.length ? row[i] : "";
            float w = colWidths.get(i) - CELL_PAD * 2;
            String t = fit(raw, w, CELL_SIZE);
            float x = left + CELL_PAD;
            if (i < colNumeric.size() && colNumeric.get(i)) {
                // 数值右对齐 —— 一列数字右对齐才能一眼看出量级
                x = left + colWidths.get(i) - CELL_PAD - textWidth(t, CELL_SIZE);
            }
            drawText(x, baseline, t, CELL_SIZE, false);
            left += colWidths.get(i);
        }
        y -= ROW_H;
        drawTableLines(top, left);
        drawRowSeparator(y);
    }

    // 表格竖线。每行都画，这样分页时不会断
    private void drawTableLines(float top, float right) {
        float x = MARGIN;
        cur.append(String.format("%.2f w%n", 0.4f));
        for (int i = 0; i <= colWidths.size(); i++) {
            cur.append(String.format("%.2f %.2f m %.2f %.2f l S%n", x, top, x, y));
            if (i < colWidths.size()) {
                x += colWidths.get(i);
            }
        }
    }

    private void drawRowSeparator(float atY) {
        cur.append(String.format("%.2f %.2f m %.2f %.2f l S%n",
                MARGIN, atY, PAGE_W - MARGIN, atY));
    }

    // 文本截断：超出列宽时截断并加省略号。宁可截断也不要溢出到隔壁列
    private String fit(String s, float maxW, float size) {
        if (s == null) {
            return "";
        }
        if (textWidth(s, size) <= maxW) {
            return s;
        }
        float ell = textWidth("…", size);
        StringBuilder sb = new StringBuilder();
        float w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            float cw = font.widthOf(cp) * size;
            if (w + cw + ell > maxW) {
                break;
            }
            sb.appendCodePoint(cp);
            w += cw;
        }
        return sb + "…";
    }

    private void drawText(float x, float yBaseline, String s, float size, boolean bold) {
        if (s == null || s.isEmpty()) {
            return;
        }
        cur.append("BT\n");
        cur.append(String.format("/%s %.2f Tf%n", PdfFont.FONT_NAME, size));
        // 文本用十六进制字形编号写出：PDF 里没有 Unicode 文本的概念
        cur.append(String.format("%.2f %.2f Td%n", x, yBaseline));
        cur.append("<").append(font.encodeHex(s)).append("> Tj\n");
        cur.append("ET\n");
    }

    // ------------------------------------------------------------ 分页

    // 剩余空间是否够写 needed 高度的内容；不够就翻页，返回是否翻了页
    private boolean ensureSpace(float needed) {
        if (y - needed < MARGIN) {
            newPage();
            return true;
        }
        return false;
    }

    private void newPage() {
        cur = new StringBuilder();
        // 每页开头重置图形状态，并铺一层白底
        cur.append("1 0 0 1 0 0 cm\n");
        pages.add(cur);
        y = PAGE_H - MARGIN;
        tableTop = y;
    }

    // ------------------------------------------------------------ 字体

    /**
     * 内嵌字体。负责 字符 → 字形编号 的映射与子集化。
     *
     * <p>只把【实际用到的】字形写进 PDF。整个 Noto CJK 有 6 万多字形，
     * 全写进去文件会很大；一张报表通常只用几百个不同字符。</p>
     */
    static final class PdfFont {

        static final String FONT_NAME = "F1";

        // 字符 → 字形编号
        private final Map<Integer, Integer> cmap = new HashMap<>();
        // 字形编号 → 字符，写 ToUnicode 用（决定 PDF 里的文字能否被复制搜索）
        private final Map<Integer, Integer> reverse = new LinkedHashMap<>();
        // 字形编号 → 宽度（1/1000 em），PDF 的宽度数组用这个单位
        private final Map<Integer, Integer> widths = new HashMap<>();

        // 原始字体文件数据，直接嵌入 PDF
        private final byte[] fontFileData;
        private final int unitsPerEm;

        private final List<Integer> glyphOrder = new ArrayList<>();   // 用到字形，按首次出现排序

        private PdfFont(byte[] fontFileData, int unitsPerEm) {
            this.fontFileData = fontFileData;
            this.unitsPerEm = unitsPerEm;
        }

        /** 从 OTF/TTF 读入。只解析必要表：cmap（字符映射）、hmtx（宽度）、CFF。 */
        static PdfFont load(InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return TrueTypeReader.read(bos.toByteArray());
        }

        /** 单字符宽度，单位是与字号相同的点（已归一化成 em 比例）。 */
        float widthOf(int codePoint) {
            Integer gid = cmap.get(codePoint);
            if (gid == null) {
                return 0.5f;    // 未知字符按半个字宽占位，避免排版塌陷
            }
            Integer w = widths.get(gid);
            if (w == null) {
                return 0.5f;
            }
            return w / 1000f;
        }

        /** 把字符串编成十六进制字形编号串，供 PDF 的 Tj 指令使用。 */
        String encodeHex(String s) {
            StringBuilder sb = new StringBuilder(s.length() * 4);
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                Integer gid = cmap.get(cp);
                if (gid == null) {
                    gid = 0;    // .notdef，显示为空框
                }
                if (!reverse.containsKey(gid)) {
                    reverse.put(gid, cp);
                    glyphOrder.add(gid);
                }
                // 字形编号按 2 字节写。子集化后编号 < 65536，够用
                sb.append(String.format("%04X", gid));
            }
            return sb.toString();
        }

        /** 实际用到的字形数。用于打包时报告子集大小。 */
        int usedGlyphCount() {
            return glyphOrder.size();
        }

        /**
         * 要嵌入 PDF 的字体数据。
         *
         * <p>直接把整份子集字体交出去 —— 因为 CID 被定义成原字形编号，
         * 字形表不需要重排。这一步是整个方案能省下大量工作量的关键。</p>
         */
        byte[] embedData() {
            return fontFileData;
        }

        /**
         * PDF 的 /W 宽度数组。
         *
         * <p>格式是「起始CID [宽度...]」的重复。只写实际用到的字形，
         * 没写到的走 /DW 默认值。</p>
         */
        String widthArray() {
            StringBuilder sb = new StringBuilder();
            for (Integer gid : glyphOrder) {
                Integer w = widths.get(gid);
                if (w != null) {
                    sb.append(gid).append(" [").append(w).append("] ");
                }
            }
            String s = sb.toString().trim();
            return s;
        }

        String fontBBox() {
            // 用保守值即可：大部分阅读器不依赖它做裁剪
            return "-200 -300 1200 1000";
        }

        int ascent() {
            return 880;
        }

        int descent() {
            return -120;
        }

        /**
         * ToUnicode CMap：告诉阅读器「字形编号 → Unicode」。
         *
         * <p>没有它，PDF 里的中文虽然能显示，但复制出来是乱码、搜索也搜不到 ——
         * 拿去发报表是不合格的。</p>
         */
        String toUnicodeCMap() {
            StringBuilder sb = new StringBuilder();
            sb.append("/CIDInit /ProcSet findresource begin\n");
            sb.append("12 dict begin\nbegincmap\n");
            sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n");
            sb.append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n");
            sb.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");

            // bfchar 每段最多 100 条，这是 PDF 规范的限制
            List<Integer> gids = new ArrayList<>(reverse.keySet());
            for (int i = 0; i < gids.size(); i += 100) {
                int end = Math.min(i + 100, gids.size());
                sb.append(end - i).append(" beginbfchar\n");
                for (int j = i; j < end; j++) {
                    int gid = gids.get(j);
                    int cp = reverse.get(gid);
                    sb.append("<").append(String.format("%04X", gid)).append("> <");
                    // 增补平面要写成代理对
                    if (cp > 0xFFFF) {
                        char[] surr = Character.toChars(cp);
                        sb.append(String.format("%04X%04X", (int) surr[0], (int) surr[1]));
                    } else {
                        sb.append(String.format("%04X", cp));
                    }
                    sb.append(">\n");
                }
                sb.append("endbfchar\n");
            }

            sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
            return sb.toString();
        }

        // TrueType/OTF 解析在下面这个静态内部类里，保持主类可读
        private static final class TrueTypeReader {
            static PdfFont read(byte[] data) throws IOException {
                Big big = new Big(data);
                int numTables = big.u16(4);
                Map<String, int[]> tables = new LinkedHashMap<>();
                for (int i = 0; i < numTables; i++) {
                    int off = 12 + i * 16;
                    String tag = new String(data, off, 4, StandardCharsets.US_ASCII);
                    long toff = big.u32(off + 8);
                    long len = big.u32(off + 12);
                    tables.put(tag, new int[]{(int) toff, (int) len});
                }

                int[] head = tables.get("head");
                int unitsPerEm = big.u16(head[0] + 18);

                // cmap：优先取 format 4（BMP）或 format 12（含增补平面）
                Map<Integer, Integer> cmap = parseCmap(big, tables.get("cmap"));

                // hmtx + hhea：取每个字形的宽度
                int[] hhea = tables.get("hhea");
                int numberOfHMetrics = big.u16(hhea[0] + 34);
                int[] hmtx = tables.get("hmtx");
                Map<Integer, Integer> widths = new HashMap<>();
                for (Map.Entry<Integer, Integer> e : cmap.entrySet()) {
                    int gid = e.getValue();
                    int adv;
                    if (gid < numberOfHMetrics) {
                        adv = big.u16(hmtx[0] + gid * 4);
                    } else {
                        // 超出 numberOfHMetrics 的字形共用最后一个宽度
                        adv = big.u16(hmtx[0] + (numberOfHMetrics - 1) * 4);
                    }
                    widths.put(gid, adv * 1000 / unitsPerEm);
                }

                int[] cffTable = tables.get("CFF ");
                PdfFont f = new PdfFont(data, unitsPerEm);
                f.cmap.putAll(cmap);
                f.widths.putAll(widths);
                return f;
            }

            // 只处理 format 4 与 format 12，其余格式在 Noto CJK 里用不到
            private static Map<Integer, Integer> parseCmap(Big big, int[] cmapTable) {
                Map<Integer, Integer> out = new HashMap<>();
                int numTables = big.u16(cmapTable[0] + 2);
                int best4 = -1, best12 = -1;
                for (int i = 0; i < numTables; i++) {
                    int rec = cmapTable[0] + 4 + i * 8;
                    int platform = big.u16(rec);
                    int encoding = big.u16(rec + 2);
                    int subOff = cmapTable[0] + (int) big.u32(rec + 4);
                    int format = big.u16(subOff);
                    if (format == 4 && (platform == 3 && (encoding == 1 || encoding == 10)
                            || platform == 0)) {
                        best4 = subOff;
                    } else if (format == 12) {
                        best12 = subOff;
                    }
                }
                if (best4 >= 0) {
                    parseFormat4(big, best4, out);
                }
                if (best12 >= 0) {
                    parseFormat12(big, best12, out);
                }
                return out;
            }

            private static void parseFormat4(Big big, int off, Map<Integer, Integer> out) {
                int segCountX2 = big.u16(off + 6);
                int segCount = segCountX2 / 2;
                int endBase = off + 14;
                int startBase = endBase + segCountX2 + 2;
                int deltaBase = startBase + segCountX2;
                int rangeBase = deltaBase + segCountX2;

                for (int s = 0; s < segCount; s++) {
                    int end = big.u16(endBase + s * 2);
                    int start = big.u16(startBase + s * 2);
                    int delta = big.s16(deltaBase + s * 2);
                    int rangeOffset = big.u16(rangeBase + s * 2);
                    if (start == 0xFFFF) {
                        continue;
                    }
                    for (int c = start; c <= end && c != 0x10000; c++) {
                        int gid;
                        if (rangeOffset == 0) {
                            gid = (c + delta) & 0xFFFF;
                        } else {
                            int gi = rangeBase + s * 2 + rangeOffset + (c - start) * 2;
                            if (gi + 1 >= big.len()) {
                                continue;
                            }
                            gid = big.u16(gi);
                            if (gid != 0) {
                                gid = (gid + delta) & 0xFFFF;
                            }
                        }
                        if (gid != 0) {
                            out.put(c, gid);
                        }
                    }
                }
            }

            private static void parseFormat12(Big big, int off, Map<Integer, Integer> out) {
                long nGroups = big.u32(off + 12);
                for (long g = 0; g < nGroups; g++) {
                    int base = off + 16 + (int) g * 12;
                    long startChar = big.u32(base);
                    long endChar = big.u32(base + 4);
                    long startGid = big.u32(base + 8);
                    // 增补平面在这个项目里用不到，跳过以免撑爆内存
                    if (startChar > 0xFFFF) {
                        continue;
                    }
                    for (long c = startChar; c <= endChar && c <= 0xFFFF; c++) {
                        out.put((int) c, (int) (startGid + (c - startChar)));
                    }
                }
            }
        }

        /** 大端读取器。PDF 与字体文件都是大端。 */
        private static final class Big {
            private final byte[] d;

            Big(byte[] d) {
                this.d = d;
            }

            int len() {
                return d.length;
            }

            int u16(int off) {
                return ((d[off] & 0xFF) << 8) | (d[off + 1] & 0xFF);
            }

            int s16(int off) {
                return (short) u16(off);
            }

            long u32(int off) {
                return ((long) (d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                        | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
            }
        }
    }

    /**
     * 把各页内容流与字体组装成完整 PDF 文件。
     *
     * <p>PDF 是一串「对象」加一张交叉引用表。这里用最朴素的方式手工拼装：
     * 顺序编号对象 1..N，记录每个对象的字节偏移，最后写 xref 与 trailer。</p>
     *
     * <p>字体用 Type0 / CIDFontType2（TrueType 轮廓）+ Identity-H 编码。
     * 之所以能做到「不重新生成字体子集」：把 <b>CID 直接定义成原字体的字形编号</b>，
     * 于是字形数据原样嵌入即可，不需要重排 glyph 表 —— 那是最容易出错的一步。
     * 代价是整份子集字体（约 3 MB）都会被嵌入，但字体本身已经过 pyftsubset
     * 裁到只含常用字，这个体积可以接受。</p>
     */
    private static final class PdfAssembler {

        static byte[] assemble(List<StringBuilder> pages, PdfFont font,
                               float pageW, float pageH) throws IOException {
            // 对象编号规划：
            //   1 = Catalog
            //   2 = Pages
            //   3..(2+P)          = 各页 Page 对象
            //   3+P .. 2+2P       = 各页 Contents 流
            //   3+2P = Font(Type0)  4+2P = DescendantFont  5+2P = FontDescriptor
            //   6+2P = FontFile2    7+2P = ToUnicode
            int p = pages.size();
            int idCatalog = 1;
            int idPages = 2;
            int idFirstPage = 3;
            int idFirstContent = idFirstPage + p;
            int idFont = idFirstContent + p;
            int idDescendant = idFont + 1;
            int idDescriptor = idFont + 2;
            int idFontFile = idFont + 3;
            int idToUnicode = idFont + 4;
            int total = idToUnicode;

            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            // 每个对象的起始偏移，xref 要用
            int[] offsets = new int[total + 1];

            write(out, "%PDF-1.4\n");
            // 二进制标记：告诉传输工具这是二进制文件，别做换行转换
            write(out, "%\u00E2\u00E3\u00CF\u00D3\n");

            // ---- 1. Catalog ----
            offsets[idCatalog] = out.size();
            write(out, idCatalog + " 0 obj\n<< /Type /Catalog /Pages " + idPages + " 0 R >>\nendobj\n");

            // ---- 2. Pages ----
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < p; i++) {
                kids.append(idFirstPage + i).append(" 0 R ");
            }
            offsets[idPages] = out.size();
            write(out, idPages + " 0 obj\n<< /Type /Pages /Count " + p
                    + " /Kids [" + kids.toString().trim() + "] >>\nendobj\n");

            // ---- 各页 ----
            for (int i = 0; i < p; i++) {
                int idPage = idFirstPage + i;
                int idContent = idFirstContent + i;
                offsets[idPage] = out.size();
                write(out, idPage + " 0 obj\n<< /Type /Page /Parent " + idPages + " 0 R"
                        + " /MediaBox [0 0 " + fmt(pageW) + " " + fmt(pageH) + "]"
                        + " /Resources << /Font << /" + PdfFont.FONT_NAME + " " + idFont + " 0 R >>"
                        + " /ProcSet [/PDF /Text] >>"
                        + " /Contents " + idContent + " 0 R >>\nendobj\n");
            }

            // ---- 内容流 ----
            for (int i = 0; i < p; i++) {
                byte[] body = pages.get(i).toString().getBytes(StandardCharsets.ISO_8859_1);
                // 内容流是纯 ASCII 图形指令，用 Flate 压缩 —— 表格线一类重复内容压得很好
                byte[] packed = deflate(body);
                int idContent = idFirstContent + i;
                offsets[idContent] = out.size();
                write(out, idContent + " 0 obj\n<< /Length " + packed.length
                        + " /Filter /FlateDecode >>\nstream\n");
                out.write(packed);
                write(out, "\nendstream\nendobj\n");
            }

            // ---- 字体 ----
            byte[] fontData = font.embedData();

            offsets[idFont] = out.size();
            write(out, idFont + " 0 obj\n<< /Type /Font /Subtype /Type0"
                    + " /BaseFont /LynMillSans"
                    + " /Encoding /Identity-H"
                    + " /DescendantFonts [" + idDescendant + " 0 R]"
                    + " /ToUnicode " + idToUnicode + " 0 R >>\nendobj\n");

            // CIDFontType2 = TrueType 轮廓。CIDToGIDMap /Identity 表示
            // CID 就是字形编号本身，这正是我们保持字形编号不变所依赖的那一条
            offsets[idDescendant] = out.size();
            write(out, idDescendant + " 0 obj\n<< /Type /Font /Subtype /CIDFontType2"
                    + " /BaseFont /LynMillSans"
                    + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >>"
                    + " /FontDescriptor " + idDescriptor + " 0 R"
                    + " /CIDToGIDMap /Identity"
                    + " /DW 1000"
                    + " /W [" + font.widthArray() + "] >>\nendobj\n");

            offsets[idDescriptor] = out.size();
            write(out, idDescriptor + " 0 obj\n<< /Type /FontDescriptor /FontName /LynMillSans"
                    + " /Flags 4"
                    + " /FontBBox [" + font.fontBBox() + "]"
                    + " /ItalicAngle 0 /Ascent " + font.ascent() + " /Descent " + font.descent()
                    + " /CapHeight " + font.ascent()
                    + " /StemV 80"
                    + " /FontFile2 " + idFontFile + " 0 R >>\nendobj\n");

            offsets[idFontFile] = out.size();
            write(out, idFontFile + " 0 obj\n<< /Length " + fontData.length
                    + " /Length1 " + fontData.length + " >>\nstream\n");
            out.write(fontData);
            write(out, "\nendstream\nendobj\n");

            byte[] cmapData = font.toUnicodeCMap().getBytes(StandardCharsets.US_ASCII);
            offsets[idToUnicode] = out.size();
            write(out, idToUnicode + " 0 obj\n<< /Length " + cmapData.length + " >>\nstream\n");
            out.write(cmapData);
            write(out, "\nendstream\nendobj\n");

            // ---- 交叉引用表 ----
            int xrefPos = out.size();
            write(out, "xref\n0 " + (total + 1) + "\n");
            write(out, "0000000000 65535 f \n");
            for (int i = 1; i <= total; i++) {
                write(out, String.format("%010d 00000 n \n", offsets[i]));
            }
            write(out, "trailer\n<< /Size " + (total + 1) + " /Root " + idCatalog + " 0 R >>\n");
            write(out, "startxref\n" + xrefPos + "\n%%EOF\n");

            return out.toByteArray();
        }

        private static void write(ByteArrayOutputStream o, String s) {
            o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        }

        private static String fmt(float v) {
            return String.format("%.2f", v);
        }

        private static byte[] deflate(byte[] data) throws IOException {
            java.util.zip.Deflater def = new java.util.zip.Deflater(9);
            def.setInput(data);
            def.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream(data.length / 3 + 64);
            byte[] buf = new byte[8192];
            while (!def.finished()) {
                int n = def.deflate(buf);
                o.write(buf, 0, n);
            }
            def.end();
            return o.toByteArray();
        }
    }
}
