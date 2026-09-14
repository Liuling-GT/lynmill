package com.tinyquery.datasource;

import com.tinyquery.executor.ColumnStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 数据源加载器。
 *
 * <p>CSV 没有强制标准，不同工具导出的形态差异很大。本加载器针对非标准形态提供开关，
 * <b>默认全部关闭</b>，以避免误伤真正的数据：</p>
 * <ul>
 *   <li>注释行：默认不识别。开启后只认「trim 之后的整行前缀」，如 #、//、--</li>
 *   <li>引号感知：默认关闭。开启后引号内的分隔符不再切分，" " 内的 "" 表示字面引号</li>
 *   <li>分隔符：默认逗号，可改为分号、制表符等</li>
 * </ul>
 *
 * <p>已默认处理：UTF-8 BOM 剥离、短行补空（避免 ColumnStore 列错位）。</p>
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
                // 注释前缀：默认关闭，避免误伤以 # 开头的数据行
                FieldSpec.text("commentPrefixes", "注释前缀", false,
                        "多个前缀用英文逗号分隔，留空则不识别注释",
                        "#,//,--"),
                // 引号感知：默认关闭。描述只讲行为与例子，不展开转义规则
                FieldSpec.bool("quoteAware", "引号感知", false,
                        "引号内的分隔符不切分，例如 a,\"b,c\",d 解析为 3 列。文件中引号不成对时请关闭"),
                // 分隔符：默认英文逗号，把“反斜杠加字母 t”写清楚，避免用户去按 Tab 键
                FieldSpec.text("delimiter", "分隔符", false,
                        "默认英文逗号；填 \\t（反斜杠加字母 t 两个字符）表示制表符",
                        ",")
        );
    }

    @Override
    public ColumnStore load(DataSourceConfig config) {
        String filePath = config.requireString("path");
        char delimiter = resolveDelimiter(config);
        List<String> commentPrefixes = resolveCommentPrefixes(config);
        boolean quoteAware = config.getBoolean("quoteAware", false);

        ColumnStore store = new ColumnStore();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
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
}
