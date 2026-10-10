package com.tinyquery.datasource.loader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 构造测试用的 xlsx 文件。
 *
 * <p>真实文件（见 {@code XlsxReaderRealFileTest}）覆盖不到一些关键分支：
 * 它们都用内联字符串，没有一个日期单元格。而共享字符串表与日期恰恰是最容易写错的地方，
 * 所以这里手工拼最小可用的 xlsx。</p>
 *
 * <p>xlsx 是 ZIP + XML，拼起来并不难 —— 这也是「手写解析可行」的一个佐证。</p>
 */
public final class XlsxFixture {

    private XlsxFixture() {
    }

    /** 一份 xlsx 的各个部件。任何一个为 null 就不写进 ZIP */
    public static final class Builder {
        private String sharedStrings;
        private String styles;
        private String workbook;
        private String workbookRels;
        private final Map<String, String> sheets = new LinkedHashMap<>();

        /** 共享字符串表。条目顺序即索引顺序（t="s" 的单元格按下标引用） */
        public Builder sharedStrings(String... values) {
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
              .append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
              .append("count=\"").append(values.length).append("\" uniqueCount=\"").append(values.length).append("\">");
            for (String v : values) {
                sb.append("<si><t>").append(escapeXml(v)).append("</t></si>");
            }
            sb.append("</sst>");
            this.sharedStrings = sb.toString();
            return this;
        }

        /** 共享字符串表，支持富文本分段（一个 si 里多个 r/t） */
        public Builder sharedStringsRich(String... segmentsOfFirst) {
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
              .append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
            sb.append("<si>");
            for (String seg : segmentsOfFirst) {
                sb.append("<r><t>").append(escapeXml(seg)).append("</t></r>");
            }
            sb.append("</si></sst>");
            this.sharedStrings = sb.toString();
            return this;
        }

        /**
         * 样式表。
         *
         * @param numFmtIds cellXfs 里每个样式对应的 numFmtId，下标即单元格的 s 属性
         */
        public Builder styles(int... numFmtIds) {
            return styles(null, numFmtIds);
        }

        /**
         * @param customFormats 自定义格式，形如 {@code "164=yyyy/mm/dd"}；可为 null
         */
        public Builder styles(String customFormats, int... numFmtIds) {
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
              .append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");

            if (customFormats == null || customFormats.isEmpty()) {
                sb.append("<numFmts count=\"0\"/>");
            } else {
                String[] pairs = customFormats.split(",");
                sb.append("<numFmts count=\"").append(pairs.length).append("\">");
                for (String pair : pairs) {
                    String[] kv = pair.split("=", 2);
                    sb.append("<numFmt numFmtId=\"").append(kv[0].trim())
                      .append("\" formatCode=\"").append(escapeXml(kv[1].trim())).append("\"/>");
                }
                sb.append("</numFmts>");
            }

            sb.append("<cellXfs count=\"").append(numFmtIds.length).append("\">");
            for (int id : numFmtIds) {
                sb.append("<xf numFmtId=\"").append(id).append("\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>");
            }
            sb.append("</cellXfs></styleSheet>");
            this.styles = sb.toString();
            return this;
        }

        /** 加一张工作表。sheetXml 是 worksheet 内部的内容（不含根标签） */
        public Builder sheet(String name, String sheetXml) {
            sheets.put(name, sheetXml);
            return this;
        }

        public Path writeTo(Path target) throws IOException {
            buildWorkbook();

            try (OutputStream out = Files.newOutputStream(target);
                 ZipOutputStream zip = new ZipOutputStream(out)) {
                put(zip, "[Content_Types].xml", contentTypes());
                put(zip, "_rels/.rels", rootRels());
                put(zip, "xl/workbook.xml", workbook);
                put(zip, "xl/_rels/workbook.xml.rels", workbookRels);
                if (sharedStrings != null) {
                    put(zip, "xl/sharedStrings.xml", sharedStrings);
                }
                if (styles != null) {
                    put(zip, "xl/styles.xml", styles);
                }
                int i = 1;
                for (Map.Entry<String, String> e : sheets.entrySet()) {
                    put(zip, "xl/worksheets/sheet" + i + ".xml", worksheet(e.getValue()));
                    i++;
                }
            }
            return target;
        }

        private void buildWorkbook() {
            if (workbook != null) {
                return;
            }
            StringBuilder wb = new StringBuilder();
            wb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
              .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
              .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
              .append("<sheets>");
            int i = 1;
            for (String name : sheets.keySet()) {
                wb.append("<sheet name=\"").append(escapeXml(name)).append("\" sheetId=\"").append(i)
                  .append("\" r:id=\"rId").append(i).append("\"/>");
                i++;
            }
            wb.append("</sheets></workbook>");
            this.workbook = wb.toString();

            StringBuilder rels = new StringBuilder();
            rels.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            i = 1;
            for (int n = 0; n < sheets.size(); n++) {
                rels.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" ")
                    .append("Target=\"worksheets/sheet").append(i).append(".xml\"/>");
                i++;
            }
            // 共享字符串与样式也要有 relationship，否则严格的读取器会找不到
            if (sharedStrings != null) {
                rels.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" ")
                    .append("Target=\"sharedStrings.xml\"/>");
                i++;
            }
            if (styles != null) {
                rels.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" ")
                    .append("Target=\"styles.xml\"/>");
            }
            rels.append("</Relationships>");
            this.workbookRels = rels.toString();
        }

        private static String worksheet(String body) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetData>" + body + "</sheetData></worksheet>";
        }

        private static String contentTypes() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "</Types>";
        }

        private static String rootRels() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" "
                    + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
                    + "Target=\"xl/workbook.xml\"/></Relationships>";
        }

        private static void put(ZipOutputStream zip, String name, String content) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------- 单元格 XML 片段 ----------

    /** 共享字符串单元格：t="s"，v 里是共享字符串表的下标 */
    public static String sharedCell(String ref, int sharedIndex) {
        return cell(ref, "s", String.valueOf(sharedIndex));
    }

    /** 内联字符串单元格 */
    public static String inlineCell(String ref, String text) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + escapeXml(text) + "</t></is></c>";
    }

    /** 数字单元格，带样式索引（用于日期判断） */
    public static String numberCell(String ref, String value, int styleIndex) {
        return "<c r=\"" + ref + "\" s=\"" + styleIndex + "\" t=\"n\"><v>" + value + "</v></c>";
    }

    public static String numberCell(String ref, String value) {
        return "<c r=\"" + ref + "\" t=\"n\"><v>" + value + "</v></c>";
    }

    /** 布尔单元格 */
    public static String boolCell(String ref, boolean value) {
        return "<c r=\"" + ref + "\" t=\"b\"><v>" + (value ? "1" : "0") + "</v></c>";
    }

    /** 公式单元格。<b>只有缓存值</b>，没有公式本身 —— Excel 存盘就是这样 */
    public static String formulaCell(String ref, String cachedValue) {
        return "<c r=\"" + ref + "\"><f>SUM(A1:A2)</f><v>" + cachedValue + "</v></c>";
    }

    /** 没有任何内容的空单元格（只有引用与样式） */
    public static String emptyCell(String ref, int styleIndex) {
        return "<c r=\"" + ref + "\" s=\"" + styleIndex + "\"/>";
    }

    private static String cell(String ref, String type, String value) {
        return "<c r=\"" + ref + "\" t=\"" + type + "\"><v>" + value + "</v></c>";
    }

    public static String row(int rowNumber, String cells) {
        return "<row r=\"" + rowNumber + "\">" + cells + "</row>";
    }

    static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
