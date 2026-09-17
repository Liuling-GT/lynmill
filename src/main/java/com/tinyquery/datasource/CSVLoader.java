package com.tinyquery.datasource;

import com.tinyquery.executor.ColumnStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 数据源加载器。
 * CSV 没有强制标准，不同工具导出的形态差异很大。本加载器针对非标准形态提供开关，
 * 默认全部关闭，以避免误伤真正的数据
 * 注释行：默认不识别。开启后只认「trim 之后的整行前缀」，如 #、//、--
 * 引号感知：默认关闭。开启后引号内的分隔符不再切分，" " 内的 "" 表示字面引号
 * 分隔符：默认逗号，可改为分号、制表符等
 * 已默认处理：UTF-8 BOM 剥离、短行补空（避免 ColumnStore 列错位）
 */
@Component
public class CSVLoader implements DataLoader {

    private static final Logger log = LoggerFactory.getLogger(CSVLoader.class);

    // UTF-8 BOM。Excel 导出的 CSV 常带此字符，不剥离会让首个列名多出不可见前缀，
    // 导致 WHERE 该列时永远匹配不上
    private static final String UTF8_BOM = "\uFEFF";

    // 制表符在输入框里的书写形式，便于表达 TSV
    private static final String TAB_LITERAL = "\\t";

    // 默认分隔符
    private static final char DEFAULT_DELIMITER = ',';

    // 编码配置的可选值。GBK 覆盖 Excel 中文版另存的 CSV，
    // 这类文件在按 UTF-8 读取时会整片乱码
    private static final String ENCODING_AUTO = "自动";
    private static final String ENCODING_UTF8 = "UTF-8";
    private static final String ENCODING_GBK = "GBK";

    // 探测用的样本大小。取文件头部若干字节尝试解码，
    // 不必读完整文件即可判断编码
    private static final int ENCODING_PROBE_BYTES = 8192;

    @Override
    public String type() {
        return "csv";
    }

    @Override
    public String label() {
        return "CSV 文件";
    }

    @Override
    public List<FieldSpec> fields() {
        return List.of(
                // 文件路径
                FieldSpec.text("path", "文件路径", true,
                        "CSV 文件的绝对路径或相对运行目录的路径",
                        "data/sample.csv"),
                // 文件编码：默认自动探测，探测不准时可手动指定
                FieldSpec.select("encoding", "文件编码", false,
                        "默认自动探测。若查询结果出现乱码（如「寮犱笁」这类无意义字符），"
                                + "可尝试改为 GBK。这是 CSV 文件自身的编码，与数据库无关",
                        List.of(ENCODING_AUTO, ENCODING_UTF8, ENCODING_GBK)),
                // 注释前缀：默认关闭，避免误伤以 # 开头的数据行
                FieldSpec.text("commentPrefixes", "注释前缀", false,
                        "多个前缀用英文逗号分隔，留空则不识别注释",
                        "#,//,--"),
                // 引号感知：默认关闭。描述只讲行为与例子，不展开转义规则
                FieldSpec.bool("quoteAware", "引号感知", false,
                        "引号内的分隔符不切分，例如 a,\"b,c\",d 解析为 3 列。文件中引号不成对时请关闭"),
                // 分隔符：默认英文逗号，把"反斜杠加字母 t"写清楚，避免用户去按 Tab 键
                FieldSpec.text("delimiter", "分隔符", false,
                        "默认英文逗号；填 \\t（反斜杠加字母 t 两个字符）表示制表符",
                        ",")
        );
    }

    @Override
    public ConnectionTestResult test(DataSourceConfig config) {
        // 路径读不到就直接给出原因，不抛异常
        String path = config.getString("path");
        if (path == null) {
            return ConnectionTestResult.fail("未填写文件路径");
        }

        File file = new File(path);
        if (!file.exists()) {
            return ConnectionTestResult.fail("文件不存在：" + file.getAbsolutePath());
        }
        if (!file.isFile()) {
            return ConnectionTestResult.fail("该路径不是文件：" + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            return ConnectionTestResult.fail("文件不可读（请检查权限）：" + file.getAbsolutePath());
        }
        if (file.length() == 0) {
            return ConnectionTestResult.fail("文件为空：" + file.getAbsolutePath());
        }

        // 与 load() 使用完全相同的切分与注释规则，保证「测得出」就等于「读得到」
        char delimiter = resolveDelimiter(config);
        List<String> commentPrefixes = resolveCommentPrefixes(config);
        boolean quoteAware = config.getBoolean("quoteAware", false);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), resolveCharset(file, config)))) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    if (line.startsWith(UTF8_BOM)) {
                        line = line.substring(UTF8_BOM.length());
                    }
                }

                String trimmedLine = line.trim();
                // 空行与注释行都不是表头，跳过
                if (trimmedLine.isEmpty() || isComment(trimmedLine, commentPrefixes)) {
                    continue;
                }

                List<String> headers = splitLine(line, delimiter, quoteAware).fields();
                if (headers.isEmpty()) {
                    return ConnectionTestResult.fail("未能解析出表头");
                }
                return ConnectionTestResult.ok("文件可读，共 " + headers.size() + " 列");
            }
        } catch (IOException e) {
            return ConnectionTestResult.fail("文件读取失败：" + e.getMessage());
        }

        return ConnectionTestResult.fail("未找到表头（文件只有空行或注释行）");
    }

    @Override
    public ColumnStore load(DataSourceConfig config) {
        String filePath = config.requireString("path");
        char delimiter = resolveDelimiter(config);
        List<String> commentPrefixes = resolveCommentPrefixes(config);
        boolean quoteAware = config.getBoolean("quoteAware", false);

        ColumnStore store = new ColumnStore();
// 显式指定编码，不依赖平台默认编码。
// FileReader 用平台默认编码，中文 Windows 上是 GBK，读 UTF-8 文件会乱码
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), resolveCharset(new File(filePath), config)))) {
            String line;
            List<String> headers = null;
            boolean firstLine = true;
            int unpairedQuoteLines = 0;

            while ((line = reader.readLine()) != null) {
                // 只在文件首行剥离 BOM
                if (firstLine) {
                    firstLine = false;
                    if (line.startsWith(UTF8_BOM)) {
                        line = line.substring(UTF8_BOM.length());
                    }
                }

                String trimmedLine = line.trim();
                // 空行跳过
                if (trimmedLine.isEmpty()) {
                    continue;
                }
                // 注释行跳过。必须在表头识别之前判断，否则注释会被当成表头
                if (isComment(trimmedLine, commentPrefixes)) {
                    continue;
                }

                SplitResult split = splitLine(line, delimiter, quoteAware);
                if (split.unpairedQuote()) {
                    unpairedQuoteLines++;
                }
                List<String> parts = split.fields();

                // 第一行有效内容作为表头
                if (headers == null) {
                    headers = new ArrayList<>();
                    for (String part : parts) {
                        headers.add(part.trim());
                    }
                    continue;
                }

                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    // 字段数少于表头的行补空串，避免 ColumnStore 出现列错位
                    // （原实现用 i < parts.length 提前退出，会漏键）
                    String value = i < parts.size() ? parts.get(i).trim() : "";
                    // 与 MySQL 共用同一条归一化路径
                    row.put(headers.get(i), ValueNormalizer.normalize(value));
                }
                store.addRow(row);
            }

            if (unpairedQuoteLines > 0) {
                // 不逐行刷屏，只汇总一次。引号不成对时，该行剩余内容会被粘成一个字段
                log.warn("CSV 文件 {} 有 {} 行引号不成对，已按原样拼接；若结果异常请关闭「引号感知」",
                        filePath, unpairedQuoteLines);
            }
        } catch (IOException e) {
            // 不再吞异常返回空 store，交由 GlobalExceptionHandler 统一转换
            throw new DataSourceException("CSV 文件读取失败：" + filePath + "（" + e.getMessage() + "）", e);
        }
        return store;
    }

    // 单行切分结果。unpairedQuote 表示该行处理完后引号仍未闭合
    private record SplitResult(List<String> fields, boolean unpairedQuote) {
    }

    private SplitResult splitLine(String line, char delimiter, boolean quoteAware) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (quoteAware && c == '"') {
                // 连续两个引号表示一个字面引号
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                    continue;
                }
                // 单个引号：进入或退出引号区
                inQuotes = !inQuotes;
                continue;
            }

            if (!inQuotes && c == delimiter) {
                fields.add(current.toString());
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        fields.add(current.toString());
        return new SplitResult(fields, inQuotes);
    }

    // 判断是否为注释行：只认整行（trim 之后）的前缀。
    // 这样「引号内出现 #」或「行中间出现 #」都不会被误判为注释
    private boolean isComment(String trimmedLine, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (trimmedLine.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // 解析注释前缀配置：多个前缀用英文逗号分隔，如 "#,//,--"。
    // 留空表示不识别注释（默认），避免误伤以 # 开头的数据行
    private List<String> resolveCommentPrefixes(DataSourceConfig config) {
        String raw = config.getString("commentPrefixes");
        if (raw == null) {
            return List.of();
        }

        List<String> prefixes = new ArrayList<>();
        for (String part : raw.split(",")) {
            String prefix = part.trim();
            if (!prefix.isEmpty()) {
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    // 解析分隔符配置：默认英文逗号，支持 \t 表示制表符，且必须是单个字符
    private char resolveDelimiter(DataSourceConfig config) {
        String raw = config.getString("delimiter");
        if (raw == null) {
            return DEFAULT_DELIMITER;
        }

        String value = TAB_LITERAL.equals(raw) ? "\t" : raw;
        if (value.length() != 1) {
            throw new DataSourceException(
                    "数据源 [" + config.name() + "] 的分隔符必须是单个字符：" + raw);
        }
        return value.charAt(0);
    }

    /**
     * 解析文件编码。
     *
     * <p>配置为「自动」或缺省时做一次探测：按 UTF-8 严格解码文件头部，
     * 出现非法字节序列说明不是 UTF-8，回退到 GBK。</p>
     *
     * <p>探测只读文件头部若干字节，不会为判断编码而读整个文件。</p>
     */
    private Charset resolveCharset(File file, DataSourceConfig config) {
        String configured = resolveEncodingName(config);

        if (ENCODING_GBK.equals(configured)) {
            return Charset.forName(ENCODING_GBK);
        }
        if (ENCODING_UTF8.equals(configured)) {
            return StandardCharsets.UTF_8;
        }

        // 自动探测
        return detectCharset(file);
    }

    // 读取配置里的编码名。缺失或为「自动」时返回自动标记
    private String resolveEncodingName(DataSourceConfig config) {
        String raw = config == null ? null : config.getString("encoding");
        if (raw == null || ENCODING_AUTO.equals(raw)) {
            return ENCODING_AUTO;
        }
        if (ENCODING_GBK.equalsIgnoreCase(raw)) {
            return ENCODING_GBK;
        }
        if (ENCODING_UTF8.equalsIgnoreCase(raw)) {
            return ENCODING_UTF8;
        }
        throw new DataSourceException("不支持的文件编码：" + raw + "，可选 " + ENCODING_AUTO + " / "
                + ENCODING_UTF8 + " / " + ENCODING_GBK);
    }

    /**
     * 探测文件编码。
     *
     * <p>用 UTF-8 解码器的严格模式解码头部的若干字节：能干净解完就认为是 UTF-8，
     * 出现非法字节序列则判定为 GBK。探测失败一律回退 UTF-8，保持默认行为不变。</p>
     */
    private Charset detectCharset(File file) {
        if (!file.isFile() || file.length() == 0) {
            return StandardCharsets.UTF_8;
        }

        byte[] head = new byte[ENCODING_PROBE_BYTES];
        int read;
        try (InputStream in = new FileInputStream(file)) {
            read = in.read(head);
        } catch (IOException e) {
            // 探测失败不影响后续读取，按默认编码继续
            return StandardCharsets.UTF_8;
        }
        if (read <= 0) {
            return StandardCharsets.UTF_8;
        }

        // 严格解码：非法字节序列会抛异常，而不是静默替换成 U+FFFD
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try {
            // 末尾可能截断多字节字符，只取已解码的前缀，末字符被丢弃也无妨
            decoder.decode(ByteBuffer.wrap(head, 0, read));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            // 合法的 UTF-8 编码序列被破坏，判定为 GBK
            log.info("文件 {} 不是有效的 UTF-8，按 GBK 读取", file.getPath());
            return Charset.forName(ENCODING_GBK);
        }
    }
}
