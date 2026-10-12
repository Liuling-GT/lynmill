package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.*;
import com.tinyquery.datasource.spec.*;
import com.tinyquery.executor.ColumnStore;
import com.tinyquery.i18n.Messages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Excel（.xlsx）数据源加载器。
 *
 * <p>支持 .xlsx 而不是要求用户先转成 CSV：{@link CSVLoader} 里有两处专门为 Excel
 * 做的兼容（剥离 UTF-8 BOM、GBK 编码选项），只对「从 Excel 导出的文件」有意义，
 * 说明用户本来就在用 Excel 的数据。直接读 .xlsx 省掉手动转换这一步。</p>
 *
 * <p>没有编码选项：xlsx 内部一律是 UTF-8 的 XML，不存在 CSV 那种「同一个文件可能是
 * GBK 也可能是 UTF-8」的问题，给一个永远只有唯一正确答案的选项只会让人困惑。</p>
 *
 * <p>文件格式只认 .xlsx（ZIP 容器）。.xls 是 2003 的二进制格式，需要完全不同的
 * 解析器（POI 的 HSSF），本加载器不支持 —— 探测到就明确告诉用户去另存为 .xlsx，
 * 而不是抛一个看不懂的 ZIP 异常。</p>
 */
@Component
public class XlsxLoader implements DataLoader {

    private static final Logger log = LoggerFactory.getLogger(XlsxLoader.class);

    /** 配置项名，与前端表单字段一一对应 */
    static final String FIELD_PATH = "path";
    static final String FIELD_SHEET_NAME = "sheetName";

    private static final String EXTENSION_XLSX = ".xlsx";
    private static final String EXTENSION_XLS = ".xls";

    /**
     * ZIP 的魔数（PK\x03\x04）。用来区分「真的不是 xlsx」与「文件路径写错了」：
     * 这两种失败对用户来说要做的事完全不同。
     */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    @Override
    public String type() {
        return "xlsx";
    }

    @Override
    public String label() {
        return Messages.get("type.xlsx");
    }

    @Override
    public List<FieldSpec> fields() {
        return List.of(
                FieldSpec.text(FIELD_PATH, Messages.get("field.filePath"), true,
                        Messages.get("field.xlsxPathDesc"),
                        "data/sample.xlsx"),
                // 工作表名留空即第一张表。不给下拉是因为要列出来就得先打开文件，
                // 而字段清单是纯静态的、在没选文件时就要渲染出来
                FieldSpec.text(FIELD_SHEET_NAME, Messages.get("field.sheetName"), false,
                        Messages.get("field.sheetNameDesc"),
                        "")
        );
    }

    // ==================== 自检 ====================

    @Override
    public ConnectionTestResult test(DataSourceConfig config) {
        File file = resolveReadableFile(config);
        if (file == null) {
            return testFailureFor(config);
        }

        try (XlsxReader reader = XlsxReader.open(file.getAbsolutePath())) {
            List<String> sheets = reader.sheetNames();
            String requested = config == null ? null : config.getString(FIELD_SHEET_NAME);

            if (requested != null && !requested.isBlank()
                    && !sheets.contains(requested.trim())) {
                // 说清有哪些表可选：用户打错字时最想知道的就是这个
                return ConnectionTestResult.fail(Messages.get("error.xlsxSheetNotFound",
                        requested.trim(), file.getName(), String.join("、", sheets)));
            }

            // 真的读一次表头，而不是只看结构：「能打开」不等于「能读」。
            // 只探测结构的话，一个内容损坏的文件会自检通过、注册成功，
            // 问题被推迟到用户第一次查询时才暴露
            List<LinkedHashMap<String, Object>> rows = reader.readSheet(requested);
            if (rows.isEmpty()) {
                // 只有表头没有数据行是合法的（用户可能刚建好表还没填），
                // 但要说清楚，免得用户以为读错了
                return ConnectionTestResult.ok(Messages.get("test.xlsxHeaderOnly",
                        sheets.size(), "—"));
            }

            Map<String, Object> first = rows.get(0);
            return ConnectionTestResult.ok(Messages.get("test.xlsxReadable",
                    sheets.size(), first.size(), rows.size()));

        } catch (DataSourceException e) {
            return ConnectionTestResult.fail(e.getMessage());
        }
    }

    // ==================== 加载 ====================

    @Override
    public ColumnStore load(DataSourceConfig config) {
        String filePath = config.requireString(FIELD_PATH);
        String sheetName = config.getString(FIELD_SHEET_NAME);

        // 先做一遍文件级校验。注册路径是直接调 load() 的（见 DataSourceManager.register），
        // 不经过 test()，所以这些「用户看得懂的原因」必须在这里也过一遍：
        // 否则用户拿到的是一个来自 FileInputStream 的「系统找不到指定的文件」，
        // 既重复了路径、又没说清该怎么办
        ConnectionTestResult problem = testFailureFor(config);
        if (problem != null) {
            throw new DataSourceException(problem.message());
        }

        // 与 CSV 同样的预检。xlsx 是压缩包，解压后的常驻量通常比文件本身大得多，
        // 系数按文件大小估偏保守；这里仍用它是因为漏放会崩、误拒只是换个数据源
        MemoryGuard.checkFile(new File(filePath).toPath());

        ColumnStore store = new ColumnStore();
        try (XlsxReader reader = XlsxReader.open(new File(filePath).getAbsolutePath())) {
            List<LinkedHashMap<String, Object>> rows = reader.readSheet(sheetName);

            for (Map<String, Object> row : rows) {
                // 与 MySQL 共用同一条归一化路径：日期/时间的呈现、
                // 空串与 null 的处理保持一致，免得同一个值在不同数据源里长得不一样
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    normalized.put(entry.getKey(), normalize(entry.getValue()));
                }
                store.addRow(normalized);
            }

            if (rows.isEmpty()) {
                log.info("Excel 文件 {} 的工作表「{}」没有数据行，仅表头", filePath, sheetName);
            }
        } catch (DataSourceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DataSourceException("error.xlsxReadFailed",
                    new Object[]{filePath, e.getMessage()},
                    Messages.get("error.xlsxReadFailed", filePath, e.getMessage()), e);
        }
        return store;
    }

    /**
     * 单元格值归一化。
     *
     * <p>只处理两件事：空串当 null（与 CSV 的补空行为一致），
     * 其余原样透传。数字、布尔、日期文本都已经在
     * {@link XlsxReader} 里转成合适的类型了，这里不再猜。</p>
     */
    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s && s.isEmpty()) {
            return null;
        }
        return value;
    }

    // ==================== 文件校验 ====================

    /** 能读的 File；不能读时返回 null（具体原因由 {@link #testFailureFor} 给出） */
    private File resolveReadableFile(DataSourceConfig config) {
        if (config == null) {
            return null;
        }
        String path = config.getString(FIELD_PATH);
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile() || !file.canRead() || file.length() == 0) {
            return null;
        }
        return file;
    }

    /**
     * 按失败的具体原因给出提示；一切正常时返回 {@code null}。
     *
     * <p>与 CSV 的做法一致：能确定原因时说清原因，别一律「测试未通过」——
     * 「文件不存在」和「文件是 .xls」用户要做的事完全不同。</p>
     *
     * <p>这里同时服务于 {@code test()} 与 {@code load()}。注册路径直接调 {@code load()}，
     * 不经过 {@code test()}，所以校验逻辑必须放在这里共用，不能只写在 test 里。</p>
     */
    private ConnectionTestResult testFailureFor(DataSourceConfig config) {
        String path = config == null ? null : config.getString(FIELD_PATH);
        if (path == null || path.isBlank()) {
            return ConnectionTestResult.fail(Messages.get("error.pathRequired"));
        }

        File file = new File(path);
        if (!file.exists()) {
            return ConnectionTestResult.fail(Messages.get("error.fileNotFound", file.getAbsolutePath()));
        }
        if (!file.isFile()) {
            return ConnectionTestResult.fail(Messages.get("error.notAFile", file.getAbsolutePath()));
        }
        if (!file.canRead()) {
            return ConnectionTestResult.fail(Messages.get("error.fileNotReadable", file.getAbsolutePath()));
        }
        if (file.length() == 0) {
            return ConnectionTestResult.fail(Messages.get("error.fileEmpty", file.getAbsolutePath()));
        }

        // 扩展名是 .xls 就直接说清楚。必须排在下面的魔数检查之前：
        // .xls 不是 ZIP，会被通用的「不是有效 xlsx」拦下，
        // 而那句话对 .xls 用户没有帮助：他需要知道的是「去另存为 .xlsx」
        String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(EXTENSION_XLS)) {
            return ConnectionTestResult.fail(Messages.get("error.xlsNotSupported", file.getName()));
        }

        // 扩展名对但内容不是 ZIP —— 常见于把 .xls 改名成 .xlsx，
        // 或文件下载到一半损坏。必须说清是「文件本身不是 xlsx」，
        // 否则用户会去反复检查路径
        if (!isZipContainer(file)) {
            return ConnectionTestResult.fail(Messages.get("error.notXlsx", file.getName()));
        }

        return null;
    }

    /** 看头部是不是 ZIP 魔数 */
    static boolean isZipContainer(File file) {
        byte[] head = new byte[4];
        try (var in = Files.newInputStream(file.toPath())) {
            int read = in.readNBytes(head, 0, 4);
            if (read < 4) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (head[i] != ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** 列出文件里的工作表名。供「测试」按钮展示，也便于排错时确认表名 */
    public List<String> sheetNames(String path) {
        try (XlsxReader reader = XlsxReader.open(new File(path).getAbsolutePath())) {
            return reader.sheetNames();
        }
    }

    /**
     * 探测一个文件是不是 xlsx。
     *
     * <p>给自检与将来的「拖入文件自动识别类型」用。判据是
     * 「扩展名是 .xlsx 且内容确实是 ZIP」，两者都满足才算 ——
     * 只看扩展名会把改名文件放进来，只看魔数会把任意 ZIP 当成表格。</p>
     */
    static boolean looksLikeXlsx(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(EXTENSION_XLSX) && isZipContainer(file);
    }

    /** 文件里所有条目的名字，排错用（例如确认有没有 sharedStrings.xml） */
    List<String> entriesOf(File file) {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                names.add(entry.getName());
            }
        } catch (IOException e) {
            return List.of();
        }
        return names;
    }
}
