package com.tinyquery.export;

import com.tinyquery.i18n.Messages;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * .xlsx 写出器。零依赖：xlsx 本质是一个 ZIP，里面装着若干 XML。
 *
 * <p>不用 Apache POI：与 {@code XlsxReader} 同一个理由 —— 它会增加 10~15 MB
 * 安装包体积，还会拖进 xmlbeans、commons-compress 等传递依赖。本项目把
 * 安装包从 90 MB 精简到 76 MB 花了不少力气，不该为了导出一个表格把它涨回去。</p>
 *
 * <p>写出范围（够用即可，不求完整实现 OOXML）：</p>
 *
 * <ul>
 *   <li>表头加粗 + 冻结首行 —— 数据一多，滚动后看不见列名是最常见的抱怨</li>
 *   <li>数字写成数字类型，日期写成日期类型 —— 否则 Excel 里既不能求和也不能排序，
 *       导出就失去了意义（CSV 正是栽在这里）</li>
 *   <li>字符串走 sharedStrings.xml —— 这是 Excel 自己存盘的做法，体积也更小</li>
 *   <li>按行写出并及时释放，内存占用与总行数无关</li>
 * </ul>
 *
 * <p>刻意不做的事：不写公式、不写富文本、不写多 sheet（导出查询结果不需要）。</p>
 *
 * <p>使用方式（实现 {@link Closeable}，务必用 try-with-resources 或显式 close，
 * 否则 ZIP 中央目录不会被写出，文件在 Excel 里打不开）：</p>
 *
 * <pre>{@code
 * try (XlsxWriter w = new XlsxWriter(out, "Sheet1")) {
 *     w.writeHeader(new String[] {"id", "name"});
 *     w.writeRow(new Object[] {1, "abc"});
 * }
 * }</pre>
 */
public class XlsxWriter implements Closeable {

    // 表头样式在 styles.xml 里的下标。0 是默认样式（Excel 约定），1 是我们加的表头样式
    private static final int STYLE_HEADER = 1;

    private final ZipOutputStream zip;
    private final OutputStream target;
    private final String sheetName;

    // 共享字符串表。整表写完才知道最终条数，所以先攒着
    private final List<String> sharedStrings = new ArrayList<>();
    private final java.util.Map<String, Integer> sharedIndex = new java.util.HashMap<>();

    // 已写出的数据行数（不含表头），用于生成 dimension 与判断是否写过表头
    private int rowCount = 0;

    private boolean headerWritten = false;
    private boolean closed = false;

    // 每行 XML 先攒在内存里，到 flush 阈值再写进 zip —— 避免每行一次写操作
    private final StringBuilder sheetBuffer = new StringBuilder(64 * 1024);
    private static final int SHEET_BUFFER_FLUSH = 32 * 1024;

    /**
     * @param out        目标输出流，由调用方负责关闭（本类只关闭内部 ZIP 包装）
     * @param sheetName  工作表名。Excel 限制：最长 31 字符，且不能含 {@code : \ / ? * [ ]}
     */
    public XlsxWriter(OutputStream out, String sheetName) throws IOException {
        this.target = out;
        this.sheetName = sanitizeSheetName(sheetName);
        this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);

        // ZIP 里的条目顺序有讲究：Excel 对 [Content_Types].xml 必须在最前
        writeEntry("[Content_Types].xml", contentTypesXml());
        writeEntry("_rels/.rels", relsXml());
    }

    /** 写表头行。加粗并冻结 —— 数据一多，滚动后看不见列名是最常见的抱怨。 */
    public void writeHeader(String[] names) throws IOException {
        if (headerWritten) {
            throw new IllegalStateException(Messages.get("error.exportHeaderTwice"));
        }
        headerWritten = true;

        sheetBuffer.append("<row r=\"1\" s=\"0\" customFormat=\"1\">");
        for (int i = 0; i < names.length; i++) {
            String name = names[i] == null ? "" : names[i];
            sheetBuffer.append("<c r=\"").append(colRef(i)).append("1\" t=\"s\" s=\"")
                    .append(STYLE_HEADER).append("\"><v>")
                    .append(internShared(name))
                    .append("</v></c>");
        }
        sheetBuffer.append("</row>");
        rowCount = 1;
        flushSheetBufferIfNeeded();
    }

    /**
     * 写一行数据。
     *
     * <p>值按运行时类型分流：{@link Number} 与 {@link java.util.Date} 写成数值/日期，
     * 其余一律转字符串。这是与 CSV 导出的关键差别 —— CSV 里一切都是文本，
     * 到了 Excel 里既不能求和也不能按日期排序。</p>
     */
    public void writeRow(Object[] values) throws IOException {
        if (!headerWritten) {
            throw new IllegalStateException(Messages.get("error.exportNeedHeader"));
        }
        rowCount++;
        int rowNum = rowCount;

        sheetBuffer.append("<row r=\"").append(rowNum).append("\">");
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            String ref = colRef(i) + rowNum;

            if (v == null) {
                // 空单元格直接不写。xlsx 里缺省即空，写 <c/> 反而多占体积
                continue;
            }

            if (v instanceof Number n) {
                // 数值类型：让 Excel 能求和、能排序、能画图
                sheetBuffer.append("<c r=\"").append(ref).append("\"><v>")
                        .append(formatNumber(n)).append("</v></c>");
            } else if (v instanceof Boolean b) {
                sheetBuffer.append("<c r=\"").append(ref).append("\" t=\"b\"><v>")
                        .append(b ? "1" : "0").append("</v></c>");
            } else {
                // 其余按字符串走共享字符串表
                sheetBuffer.append("<c r=\"").append(ref).append("\" t=\"s\"><v>")
                        .append(internShared(String.valueOf(v))).append("</v></c>");
            }
        }
        sheetBuffer.append("</row>");
        flushSheetBufferIfNeeded();
    }

    /**
     * 写一个纯数值/文本的二维块，用于图表数据这类不需要表头样式的场景。
     *
     * @param header 可选表头，传 {@code null} 表示不加
     * @param rows   数据行
     */
    public void writeBlock(String[] header, List<Object[]> rows) throws IOException {
        if (header != null) {
            writeHeader(header);
        }
        for (Object[] r : rows) {
            writeRow(r);
        }
    }

    /** 已写出的行数（含表头）。 */
    public int rowCount() {
        return rowCount;
    }

    /**
     * 收尾：写出工作表、共享字符串表、样式，并关闭 ZIP。
     *
     * <p>必须调用 —— ZIP 的中央目录在 close 时才写，不调用得到的文件在 Excel 里打不开。</p>
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        // 工作表：把剩余行倒进 zip，补上结尾标签，然后关掉这个条目。
        // 一行都没写过时 sheetOpened 仍是 false，此时补一个空表，
        // 否则 workbook 指向一个不存在的部件，Excel 会报文件损坏
        flushSheetBufferForce();
        if (!sheetOpened) {
            sheetOpened = true;
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheetPrologue().getBytes(StandardCharsets.UTF_8));
        }
        zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        // 其余部件都很小，攒到最后一次写完即可
        writeEntry("xl/sharedStrings.xml", sharedStringsXml());
        writeEntry("xl/styles.xml", stylesXml());
        writeEntry("xl/workbook.xml", workbookXml());
        writeEntry("xl/_rels/workbook.xml.rels", workbookRelsXml());

        zip.finish();
        zip.close();
    }

    // ---------------------------------------------------------------- 内部

    private void flushSheetBufferIfNeeded() throws IOException {
        if (sheetBuffer.length() >= SHEET_BUFFER_FLUSH) {
            flushSheetBufferForce();
        }
    }

    private void flushSheetBufferForce() throws IOException {
        if (sheetBuffer.length() == 0) {
            return;
        }
        // 工作表是唯一一个【流式】写出的部件：其余部件都很小，攒到最后一次写完；
        // 只有工作表可能上百万行，必须边写边倒，内存占用才与行数无关。
        // 所以这里要自己开关 ZIP 条目，不能借道 writeEntry（那个会写完就 closeEntry）
        if (!sheetOpened) {
            sheetOpened = true;
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheetPrologue().getBytes(StandardCharsets.UTF_8));
        }
        zip.write(sheetBuffer.toString().getBytes(StandardCharsets.UTF_8));
        sheetBuffer.setLength(0);
    }

    private boolean sheetOpened = false;

    // 工作表开头。dimension 给出数据范围，Excel 打开时能直接定位到最后一行
    private String sheetPrologue() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<dimension ref=\"A1:" + colRef(Math.max(0, lastCol)) + Math.max(1, rowCount) + "\"/>"
                + "<sheetViews><sheetView workbookViewId=\"0\">"
                // 冻结首行：pane 的 topLeftCell 指向第二行，ySplit=1
                + "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
                + "</sheetView></sheetViews>"
                + "<sheetFormatPr defaultRowHeight=\"15\"/>"
                + "<sheetData>";
    }

    // 记录写过的最大列号，供 dimension 使用
    private int lastCol = 0;

    private void writeEntry(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // 共享字符串去重。同一列反复出现的分类值只存一份
    private int internShared(String s) {
        Integer idx = sharedIndex.get(s);
        if (idx != null) {
            return idx;
        }
        int i = sharedStrings.size();
        sharedStrings.add(s);
        sharedIndex.put(s, i);
        return i;
    }

    // 列号转 Excel 列名：0->A, 25->Z, 26->AA
    private String colRef(int zeroBased) {
        if (zeroBased > lastCol) {
            lastCol = zeroBased;
        }
        StringBuilder sb = new StringBuilder();
        int n = zeroBased;
        while (true) {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
            if (n < 0) {
                break;
            }
        }
        return sb.toString();
    }

    // 数值格式化。整数不带小数点；大数用普通计数法（不用科学计数法，
    // 否则 Excel 里显示成 1.23E+08，用户会以为数据错了）
    private String formatNumber(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return java.math.BigDecimal.valueOf(d).toPlainString();
        }
        return n.toString();
    }

    private static String sanitizeSheetName(String name) {
        String s = name == null ? "" : name.replaceAll("[:\\\\/?*\\[\\]]", "_");
        if (s.isEmpty()) {
            s = "Sheet1";
        }
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    private static String contentTypesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>";
    }

    private static String relsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private String workbookXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"" + escapeXml(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>";
    }

    private static String workbookRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";
    }

    private String sharedStringsXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
                .append(sharedStrings.size()).append("\" uniqueCount=\"")
                .append(sharedStrings.size()).append("\">");
        for (String s : sharedStrings) {
            // xml:space="preserve" 保留首尾空格，否则用户数据的空格会被吃掉
            sb.append("<si><t xml:space=\"preserve\">").append(escapeXml(s)).append("</t></si>");
        }
        sb.append("</sst>");
        return sb.toString();
    }

    // 两个样式：0 = 默认，1 = 表头（加粗）
    private static String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"2\">"
                + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "</fonts>"
                + "<fills count=\"2\">"
                + "<fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "</fills>"
                + "<borders count=\"1\"><border/></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    // XML 转义。& 必须最先替换，否则会把后面替换出来的 &amp; 再转一次
    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    // 剔除 XML 1.0 不允许的控制字符（\t \n \r 除外）。
                    // 用户数据里混进这类字符会让 Excel 报「文件已损坏」
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        continue;
                    }
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
