package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.i18n.Messages;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * .xlsx 读取器。零依赖：xlsx 本质是一个 ZIP，里面装着若干 XML。
 *
 * <h2>为什么不用 Apache POI</h2>
 *
 * <p>POI 会给安装包增加 10~15 MB，还会拖进 xmlbeans、commons-compress 等传递依赖。
 * 本项目的定位是「本地零运维的小工具」，把体积翻两番去读一个表格文件不划算。</p>
 *
 * <h2>能读什么</h2>
 *
 * <ul>
 *   <li>共享字符串表（{@code sharedStrings.xml}）与内联字符串（{@code t="inlineStr"}）——
 *       真实文件两种都有：Excel 自己存盘用前者，openpyxl 等库生成的多为后者</li>
 *   <li>数字、布尔、公式的<b>缓存值</b></li>
 *   <li>日期（按 numFmt 识别，还原成 {@code yyyy-MM-dd}）</li>
 *   <li>跳过的空单元格（不补位会导致整行列错位）</li>
 *   <li>多工作表</li>
 * </ul>
 *
 * <h2>刻意不读什么</h2>
 *
 * <ul>
 *   <li><b>公式求值</b>。只取 Excel 上次算好存下来的缓存值。若文件是别的程序生成的、
 *       从没被 Excel 打开过，公式单元格可能没有缓存值 —— 那时读出来是空，
 *       而不是算错。宁可空着也不给一个自己算的数</li>
 *   <li>样式、配色、图表、图片、批注、合并单元格的合并关系（合并区域的左上角有值，
 *       其余为空 —— 这与 Excel 的显示一致，不额外填充）</li>
 *   <li>.xls（2003 二进制格式）。那是完全不同的格式，需要 POI 的 HSSF 才能读</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 *
 * <p>非线程安全。每次读取 new 一个，用完即弃。</p>
 */
public class XlsxReader implements Closeable {

    // ---------- 包结构常量 ----------

    private static final String ENTRY_SHARED_STRINGS = "xl/sharedStrings.xml";
    private static final String ENTRY_WORKBOOK = "xl/workbook.xml";
    private static final String ENTRY_WORKBOOK_RELS = "xl/_rels/workbook.xml.rels";
    private static final String ENTRY_STYLES = "xl/styles.xml";
    private static final String DIR_WORKSHEETS = "xl/worksheets/";

    // XML 里的命名空间。xlsx 的标签都带这个默认命名空间，
    // 用 getElementsByTagName 时不写前缀会一个都匹配不到
    private static final String NS_MAIN =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_PKG_REL =
            "http://schemas.openxmlformats.org/package/2006/relationships";

    /** 单元格引用，如 A1 / AB12。列字母在前、行号在后 */
    private static final Pattern CELL_REF = Pattern.compile("^([A-Z]+)(\\d+)$");

    /**
     * 日期格式里出现的字符。<b>只要格式串里含有这些，就当日期处理。</b>
     *
     * <p>这是个近似判断 —— 真正的 Excel 格式串语法很复杂（有转义、有方括号里的条件）。
     * 但反过来漏判的代价更小：一个日期被当数字读出来，用户看得出那是错的；
     * 而把普通数字误判成日期，会得到一个看起来像 1900 年的荒唐值。</p>
     *
     * <p>所以这里刻意<b>偏保守</b>：只认那些几乎只出现在日期/时间格式里的字符，
     * 不含 {@code m}（月/分）与 {@code s}（秒）单独出现的情形，
     * 因为 {@code 0.00"m"} 这类单位后缀也含 m。</p>
     */
    private static final Set<Character> DATE_FORMAT_CHARS = Set.of('y', 'd', 'h');

    /** 内置的日期/时间格式 id。见 ECMA-376 第 18.8.30 节 */
    private static final Set<Integer> BUILTIN_DATE_FORMATS = Set.of(
            14, 15, 16, 17, 18, 19, 20, 21, 22, // 各种日期
            27, 28, 29, 30, 31, 32, 33, 34, 35, 36, // 东亚历法日期
            45, 46, 47, // 时间
            50, 51, 52, 53, 54, 55, 56, 57, 58 // 东亚历法日期时间
    );

    // ---------- 读到的内容 ----------

    /** 工作表名 → 该表的 ZIP 条目路径，保持 workbook.xml 里的顺序 */
    private final Map<String, String> sheetEntries = new LinkedHashMap<>();

    private SharedStrings sharedStrings = SharedStrings.empty();
    private Styles styles = Styles.empty();

    private final String fileName;

    private XlsxReader(String fileName) {
        this.fileName = fileName;
    }

    /**
     * 打开一个 xlsx 文件。
     *
     * <p>只读元信息（工作表清单、共享字符串表、样式表），不解析任何单元格数据 ——
     * 大文件里 sheet1.xml 可能几十 MB，光为了列出工作表名不该把它读进来。</p>
     */
    public static XlsxReader open(String path) {
        XlsxReader reader = new XlsxReader(path);
        reader.loadStructure(path);
        return reader;
    }

    // ==================== 元信息 ====================

    /** 所有工作表名，按文件里的顺序 */
    public List<String> sheetNames() {
        return new ArrayList<>(sheetEntries.keySet());
    }

    // ==================== 读取单元格 ====================

    /**
     * 读一张工作表。
     *
     * @param sheetName 工作表名。为 null 或空白时取第一张表
     * @return 行 → 列名-值。行按文件顺序，列按 A、B、C… 顺序
     * @throws DataSourceException 表不存在、或文件损坏
     */
    public List<LinkedHashMap<String, Object>> readSheet(String sheetName) {
        String entry = resolveSheetEntry(sheetName);
        Document doc = parseEntry(entry);
        if (doc == null) {
            throw new DataSourceException(Messages.get("error.xlsxSheetUnreadable", sheetName, fileName));
        }

        Element sheetData = firstChild(doc.getDocumentElement(), "sheetData");
        if (sheetData == null) {
            // 空工作表是合法的（新建但没填内容）。返回空列表而不是报错
            return List.of();
        }

        // 先收集所有行，同时记下最大列号 —— 列名要在看到全部数据后才能定
        List<Map<Integer, Object>> rawRows = new ArrayList<>();
        int maxColumn = -1;

        for (Element row : children(sheetData, "row")) {
            Map<Integer, Object> cells = new HashMap<>();
            for (Element cell : children(row, "c")) {
                int columnIndex = columnIndexOf(cell.getAttribute("r"));
                if (columnIndex < 0) {
                    // 没有 r 属性的单元格极罕见（部分生成器会省），
                    // 跳过而不是猜位置 —— 猜错会让整行数据错位
                    continue;
                }
                cells.put(columnIndex, valueOf(cell));
                if (columnIndex > maxColumn) {
                    maxColumn = columnIndex;
                }
            }
            rawRows.add(cells);
        }

        if (rawRows.isEmpty()) {
            return List.of();
        }

        // 第一行是表头
        Map<Integer, Object> headerRow = rawRows.get(0);
        List<String> headerNames = buildHeaderNames(headerRow, maxColumn);

        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i < rawRows.size(); i++) {
            Map<Integer, Object> cells = rawRows.get(i);
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < headerNames.size(); c++) {
                row.put(headerNames.get(c), cells.get(c));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 生成列名。
     *
     * <p>表头为空、或与前面重名时补一个可用的名字。不补的话
     * {@link LinkedHashMap} 会把后面的同名键覆盖掉，用户会莫名其妙少一列。</p>
     */
    private static List<String> buildHeaderNames(Map<Integer, Object> headerRow, int maxColumn) {
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();

        for (int c = 0; c <= maxColumn; c++) {
            Object raw = headerRow.get(c);
            String name = raw == null ? "" : String.valueOf(raw).trim();

            if (name.isEmpty()) {
                name = Messages.get("xlsx.columnFallback", columnLetter(c));
            }
            // 重名就加序号。Excel 里同名表头很常见（合并单元格展开后尤其明显）
            if (!used.add(name)) {
                int suffix = 2;
                while (!used.add(name + "_" + suffix)) {
                    suffix++;
                }
                name = name + "_" + suffix;
            }
            names.add(name);
        }
        return names;
    }

    // ==================== 单元格取值 ====================

    private Object valueOf(Element cell) {
        String type = cell.getAttribute("t");
        String styleIndex = cell.getAttribute("s");

        if ("inlineStr".equals(type)) {
            Element is = firstChild(cell, "is");
            return is == null ? null : textOf(is);
        }

        Element v = firstChild(cell, "v");
        if (v == null) {
            // 没有 <v> 的单元格：要么是空单元格，要么是公式但从未被 Excel 算过。
            // 两种都返回 null，不猜
            return null;
        }
        String raw = v.getTextContent();
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        if ("s".equals(type)) {
            return sharedStrings.get(parseIntSafely(raw));
        }
        if ("b".equals(type)) {
            // 布尔在文件里是 1/0。用 Boolean 而不是 true/false 字符串，
            // 让前端按布尔渲染
            return "1".equals(raw);
        }
        if ("str".equals(type)) {
            // 公式的字符串结果，直接就是文本
            return raw;
        }
        if ("e".equals(type)) {
            // 错误值，如 #DIV/0!。# 开头是 Excel 的原样显示，保留
            return raw;
        }
        if ("d".equals(type)) {
            // ISO 8601 日期（少见，但规范里有）
            return raw;
        }

        // 剩下的都是数字（含没有 t 属性的默认情形）
        return numberOrDate(raw, styleIndex);
    }

    /**
     * 数字单元格：判断它是普通数字还是被存成数字的日期。
     *
     * <p>Excel 把日期存成「从 1900-01-01 起的天数」，所以必须看格式才能知道
     * 一个 {@code 45999} 到底是四万五还是 2025 年某天。</p>
     */
    private Object numberOrDate(String raw, String styleIndex) {
        BigDecimal number = parseDecimalSafely(raw);
        if (number == null) {
            // 解析不了就原样返回文本，至少不丢数据
            return raw;
        }

        if (styles.isDateFormat(parseIntSafely(styleIndex))) {
            String formatted = formatExcelDate(number);
            if (formatted != null) {
                return formatted;
            }
        }

        // 整数就用 long，避免前端显示成 1.0 —— 用户会觉得那是浮点错误
        if (number.stripTrailingZeros().scale() <= 0) {
            try {
                return number.longValueExact();
            } catch (ArithmeticException overflow) {
                // 超出 long 范围的大数（如身份证号被存成数字）。退回 double，
                // 精度可能有损，但总比读不出来好
                return number.doubleValue();
            }
        }
        return number.doubleValue();
    }

    /** Excel 的日期序列号 → 可读文本。算不出来时返回 null，调用方会退回数字 */
    private static String formatExcelDate(BigDecimal serial) {
        double days = serial.doubleValue();
        // 负数与超大值都不是合理的日期（Excel 自己也不认）。
        // 上限取 2958465 = 9999-12-31，与 Excel 的最大日期一致
        if (days < 0 || days > 2958465.0) {
            return null;
        }

        long wholeDays = (long) Math.floor(days);
        double fraction = days - wholeDays;

        LocalDate date = excelSerialToDate(wholeDays);
        if (date == null) {
            return null;
        }

        if (fraction <= 0) {
            return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        // 时刻按一天的秒数换算。四舍五入到秒，避免 0.9999999 秒变成 23:59:60
        long secondsOfDay = Math.round(fraction * 86400.0);
        if (secondsOfDay >= 86400) {
            // 进位到第二天（0.99999999 这类舍入到整天的情形）
            date = date.plusDays(1);
            secondsOfDay -= 86400;
        }
        LocalTime time = LocalTime.ofSecondOfDay(secondsOfDay);

        // 恰好是 0 点（如 00:00:00 的日期时间格式）就只给日期，
        // 少几个字符，也不让用户以为时间有意义
        if (time.equals(LocalTime.MIDNIGHT)) {
            return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        return LocalDateTime.of(date, time).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * Excel 序列号 → 日期。
     *
     * <p><b>1900 闰年 bug</b>：Excel 为了兼容 Lotus 1-2-3，认为 1900 年是闰年，
     * 于是序列号 60 对应一个不存在的 1900-02-29。所以：
     * 序列号 &lt; 60 时从 1899-12-31 起算，≥ 60 时减掉那一天（等价于从 1899-12-30 起算）。
     * 这不是我们的 bug，是必须照抄的历史包袱 —— 不照抄就会与 Excel 显示差一天。</p>
     */
    private static LocalDate excelSerialToDate(long serial) {
        if (serial < 60) {
            return LocalDate.of(1899, 12, 31).plusDays(serial);
        }
        return LocalDate.of(1899, 12, 30).plusDays(serial);
    }

    // ==================== 打开与解析 ====================

    private void loadStructure(String path) {
        boolean sawWorkbook = false;
        try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(path))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (ENTRY_WORKBOOK.equals(name)) {
                    parseWorkbook(readAll(zip));
                    sawWorkbook = true;
                } else if (ENTRY_SHARED_STRINGS.equals(name)) {
                    sharedStrings = parseSharedStrings(readAll(zip));
                } else if (ENTRY_STYLES.equals(name)) {
                    styles = parseStyles(readAll(zip));
                } else if (ENTRY_WORKBOOK_RELS.equals(name)) {
                    // rels 要在 workbook 之后才有用，但 ZIP 里的顺序不保证。
                    // 先存下来，parseWorkbook 时若还没到就靠命名约定兜底
                    relsXml = readAll(zip);
                }
            }
        } catch (IOException e) {
            throw new DataSourceException(Messages.get("error.xlsxUnreadable", path, e.getMessage()), e);
        }

        // 走到了却没有 workbook.xml，说明这压根不是 xlsx：
        // 可能是个普通 ZIP，也可能文件损坏了。
        // 不报错的话，sheetEntries 会是空的，问题被推迟到 readSheet 才以
        // 一个莫名其妙的空指针或「工作表不存在」的形式暴露出来
        if (!sawWorkbook) {
            throw new DataSourceException(Messages.get("error.notXlsx", path));
        }
    }

    private byte[] relsXml;

    /**
     * 解析 workbook.xml，拿到「工作表名 → 条目路径」。
     *
     * <p>不能靠猜路径：sheet1.xml 未必是第一张表，而且 sheet 的 r:id 要通过
     * workbook.xml.rels 才能映射到真实文件。<b>顺序也不能靠文件名</b> ——
     * 用户看到的顺序是 workbook.xml 里的顺序。</p>
     */
    private void parseWorkbook(byte[] xml) {
        Document doc = parse(xml);
        if (doc == null) {
            throw new DataSourceException(Messages.get("error.xlsxUnreadable", fileName, "workbook.xml"));
        }

        // rId → 目标路径。拿不到 rels 时退回 sheet1.xml、sheet2.xml… 的约定
        Map<String, String> relTargets = parseRels(relsXml);

        Element sheets = firstChild(doc.getDocumentElement(), "sheets");
        if (sheets == null) {
            throw new DataSourceException(Messages.get("error.xlsxNoSheet", fileName));
        }

        int fallbackIndex = 1;
        for (Element sheet : children(sheets, "sheet")) {
            String name = sheet.getAttribute("name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            String relId = sheet.getAttributeNS(NS_REL, "id");

            String target = relTargets.get(relId);
            String entry;
            if (target != null) {
                entry = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            } else {
                entry = DIR_WORKSHEETS + "sheet" + fallbackIndex + ".xml";
            }
            fallbackIndex++;
            sheetEntries.put(name, entry);
        }

        if (sheetEntries.isEmpty()) {
            throw new DataSourceException(Messages.get("error.xlsxNoSheet", fileName));
        }
    }

    /** 解析 workbook.xml.rels：rId → 相对路径（如 worksheets/sheet1.xml） */
    private static Map<String, String> parseRels(byte[] xml) {
        Map<String, String> map = new HashMap<>();
        if (xml == null) {
            return map;
        }
        Document doc = parse(xml);
        if (doc == null) {
            return map;
        }
        for (Element rel : children(doc.getDocumentElement(), "Relationship")) {
            String id = rel.getAttribute("Id");
            String target = rel.getAttribute("Target");
            if (!id.isEmpty() && !target.isEmpty()) {
                map.put(id, target);
            }
        }
        return map;
    }

    private static SharedStrings parseSharedStrings(byte[] xml) {
        Document doc = parse(xml);
        if (doc == null) {
            return SharedStrings.empty();
        }
        List<String> items = new ArrayList<>();
        for (Element si : children(doc.getDocumentElement(), "si")) {
            // si 里可能是单个 <t>，也可能是一组 <r><t>（富文本分段）——
            // 分段时必须拼起来，只取第一个会丢字
            items.add(textOf(si));
        }
        return new SharedStrings(items);
    }

    private static Styles parseStyles(byte[] xml) {
        Document doc = parse(xml);
        if (doc == null) {
            return Styles.empty();
        }
        Element root = doc.getDocumentElement();

        // 自定义格式：numFmtId → 格式串
        Map<Integer, String> customFormats = new HashMap<>();
        Element numFmts = firstChild(root, "numFmts");
        if (numFmts != null) {
            for (Element numFmt : children(numFmts, "numFmt")) {
                customFormats.put(parseIntSafely(numFmt.getAttribute("numFmtId")),
                        numFmt.getAttribute("formatCode"));
            }
        }

        // cellXfs 里每一项是一个样式，numFmtId 指向格式。
        // 单元格的 s 属性是这里的下标
        List<Integer> styleToFormat = new ArrayList<>();
        Element cellXfs = firstChild(root, "cellXfs");
        if (cellXfs != null) {
            for (Element xf : children(cellXfs, "xf")) {
                styleToFormat.add(parseIntSafely(xf.getAttribute("numFmtId")));
            }
        }

        return new Styles(styleToFormat, customFormats);
    }

    private String resolveSheetEntry(String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            return sheetEntries.values().iterator().next();
        }
        String entry = sheetEntries.get(sheetName.trim());
        if (entry == null) {
            // 把可选的工作表名列出来 —— 用户打错字时最想知道的是「到底有哪些」
            throw new DataSourceException(Messages.get("error.xlsxSheetNotFound",
                    sheetName, fileName, String.join("、", sheetEntries.keySet())));
        }
        return entry;
    }

    private Document parseEntry(String entryPath) {
        try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(fileName))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryPath.equals(entry.getName())) {
                    return parse(readAll(zip));
                }
            }
        } catch (IOException e) {
            throw new DataSourceException(Messages.get("error.xlsxUnreadable", fileName, e.getMessage()), e);
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    /**
     * 解析一段 XML。
     *
     * <p><b>必须关掉外部实体</b>：默认的 DocumentBuilder 会去解析 DTD 里引用的外部文件，
     * 一个精心构造的 xlsx 能借此读取本机任意文件（XXE）。这里不加载外部 DTD、
     * 不解析外部实体、不开 XInclude。</p>
     */
    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);

            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            // 不联网取 DTD（部分 XML 声明会带 DTD 地址）
            builder.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));

            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return null;
        }
    }

    /** 取元素下所有直接子元素里指定标签名的那些（含命名空间） */
    private static List<Element> children(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && matches(node, tagName)) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static Element firstChild(Element parent, String tagName) {
        List<Element> list = children(parent, tagName);
        return list.isEmpty() ? null : list.get(0);
    }

    private static boolean matches(Node node, String tagName) {
        String local = node.getLocalName();
        if (local != null) {
            return tagName.equals(local);
        }
        // 没有命名空间感知时退回全名比较（去掉前缀）
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return tagName.equals(colon < 0 ? name : name.substring(colon + 1));
    }

    /**
     * 取元素里的全部文本。
     *
     * <p>共享字符串与内联字符串都可能是「富文本」：一个 {@code <si>} 里含多个
     * {@code <r><t>} 分段（比如「<b>粗</b>体」）。必须拼接全部分段，
     * 只取第一个 {@code <t>} 会丢字。</p>
     *
     * <p>注意：{@code <rPh>}（注音，日语假名）里的 {@code <t>} 不该算进来，
     * 那是给读音用的，不是单元格内容。</p>
     */
    private static String textOf(Element element) {
        StringBuilder sb = new StringBuilder();
        collectText(element, sb);
        return sb.toString();
    }

    private static void collectText(Element element, StringBuilder sb) {
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(node.getNodeValue());
            } else if (node.getNodeType() == Node.ELEMENT_NODE) {
                String local = node.getLocalName();
                if ("rPh".equals(local)) {
                    continue; // 注音，跳过
                }
                if ("t".equals(local) || "r".equals(local) || "is".equals(local)) {
                    collectText((Element) node, sb);
                }
            }
        }
    }

    // ==================== 单元格引用 ====================

    /** A → 0，B → 1，Z → 25，AA → 26 */
    static int columnIndexOf(String cellRef) {
        if (cellRef == null || cellRef.isEmpty()) {
            return -1;
        }
        Matcher matcher = CELL_REF.matcher(cellRef.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return -1;
        }
        String letters = matcher.group(1);
        int index = 0;
        for (int i = 0; i < letters.length(); i++) {
            index = index * 26 + (letters.charAt(i) - 'A' + 1);
        }
        return index - 1;
    }

    /** 0 → A，25 → Z，26 → AA。列名兜底时用（表头为空的情况） */
    static String columnLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    // ==================== 小工具 ====================

    private static int parseIntSafely(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal parseDecimalSafely(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void close() {
        // 没有常驻资源：每次读取都现开现关 ZIP。
        // 保留 close() 是为了让调用方能放进 try-with-resources，
        // 将来若改为缓存句柄也不必改调用方
    }

    // ==================== 内部数据结构 ====================

    /** 共享字符串表。真实文件里可能没有这个部件（那时字符串是内联的） */
    private record SharedStrings(List<String> items) {
        static SharedStrings empty() {
            return new SharedStrings(List.of());
        }

        /** 越界返回 null 而不是抛异常：文件损坏时宁可这一格空着，也不该整个读不出来 */
        String get(int index) {
            if (index < 0 || index >= items.size()) {
                return null;
            }
            return items.get(index);
        }
    }

    /**
     * 样式表里与「是不是日期」有关的部分。
     *
     * <p>只保留判断所需的信息，不保存字体配色 —— 那些本工具用不上，
     * 存下来只是徒增内存。</p>
     */
    private record Styles(List<Integer> styleToFormat, Map<Integer, String> customFormats) {
        static Styles empty() {
            return new Styles(List.of(), Map.of());
        }

        boolean isDateFormat(int styleIndex) {
            if (styleIndex < 0 || styleIndex >= styleToFormat.size()) {
                return false;
            }
            int formatId = styleToFormat.get(styleIndex);
            if (BUILTIN_DATE_FORMATS.contains(formatId)) {
                return true;
            }
            String code = customFormats.get(formatId);
            return code != null && looksLikeDateFormat(code);
        }
    }

    /**
     * 格式串是不是日期/时间。
     *
     * <p>判断办法：去掉引号里的字面量与方括号里的条件段，再看剩下还有没有
     * 日期特征字符。这样 {@code 0.00" 天"} 不会被误判（「天」在引号里），
     * 而 {@code yyyy/m/d} 会命中。</p>
     */
    static boolean looksLikeDateFormat(String formatCode) {
        if (formatCode == null || formatCode.isEmpty()) {
            return false;
        }
        String stripped = stripLiterals(formatCode);
        for (int i = 0; i < stripped.length(); i++) {
            char c = Character.toLowerCase(stripped.charAt(i));
            if (DATE_FORMAT_CHARS.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉引号内字面量、方括号条件段、以及反斜杠转义的下一个字符 */
    private static String stripLiterals(String code) {
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        boolean inBracket = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\\') {
                i++; // 跳过被转义的字符
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (inQuotes) {
                continue;
            }
            if (c == '[') {
                inBracket = true;
                continue;
            }
            if (c == ']') {
                inBracket = false;
                continue;
            }
            if (inBracket) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
