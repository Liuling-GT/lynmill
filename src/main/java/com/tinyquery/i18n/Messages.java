package com.tinyquery.i18n;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后端文案表。
 *
 * <p>不用 Spring 的 {@code MessageSource}：它绑定在 {@code ApplicationContext} 上，
 * 而本项目里需要文案的地方有一批拿不到它 —— {@code TokenFilter} 是 Filter
 * （在 MVC 之前），{@code DataLoader} 实现是普通 {@code @Component}，
 * {@code DataSourceException} 可能在任意层被构造。为了几十条文案给这些地方
 * 全部做依赖注入，代价大于收益。所以这里做成一张静态表：无状态、无 IO、
 * 可以在任何地方调用，词条数量是几十条量级，全部驻留内存的代价可以忽略。</p>
 *
 * <p>语言由 {@link LocaleFilter} 每个请求解析一次请求头并写进 {@link #current()}，
 * 请求线程内共享。不读 Cookie、不读会话：本服务只绑本机、单用户，语言是纯前端偏好，
 * 跟着请求走最简单也最不容易出错。拿不到请求上下文时（如后台线程）回落到中文，
 * 与默认语言一致。</p>
 */
public final class Messages {

    /** 支持的语言。加语言时这里和两张表都要动 */
    public static final String ZH = "zh";
    public static final String EN = "en";

    /** 前端传语言的请求头。用自定义头而不是 Accept-Language：
     *  Accept-Language 由浏览器按系统语言自动填，会把用户在前端的选择盖掉 */
    public static final String LOCALE_HEADER = "X-LynMill-Locale";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /**
     * 请求线程之外使用的语言（桌面端：托盘菜单、控制窗口、启动失败对话框）。
     *
     * <p>{@link #CURRENT} 是 ThreadLocal，由 {@link LocaleFilter} 在 HTTP 请求线程上设置。
     * 而托盘菜单构建在 AWT 事件分发线程、控制窗口在启动阶段，那两个线程上没有任何人
     * 设过语言，{@code current()} 只会返回默认值，桌面端于是永远是中文，
     * 跟用户在设置页选的语言无关。</p>
     *
     * <p>取值优先级是 ThreadLocal &gt; 本字段 &gt; 中文：请求线程有明确语言就用它；
     * 桌面线程没有，用启动时定下的这一份。</p>
     *
     * <p>启动期定一次而不是实时同步：用户在设置页切语言只改了浏览器里的
     * localStorage，服务端并不知道（语言是跟着请求头走的，见类注释）。要让桌面端
     * 实时跟着变，得额外做一个「前端通知后端」的接口，而托盘菜单在用户切完语言后
     * 本来就多半看不见（它只在右键时出现），重启即生效的代价可以接受。
     * 这比给 Messages 引入可变全局状态、再担心它与 ThreadLocal 打架要稳。</p>
     *
     * <p>用 {@code volatile}：写在启动线程，读在 AWT 线程。</p>
     */
    private static volatile String desktopLocale = ZH;

    /**
     * 设置桌面端（非请求线程）使用的语言。
     *
     * <p>由启动流程在创建托盘与窗口之前调用一次。传 null 或认不出的值一律当中文。</p>
     */
    public static void setDesktopLocale(String locale) {
        desktopLocale = normalize(locale);
    }

    /** 桌面端当前语言。测试用，也便于排查「托盘为什么是英文」 */
    public static String desktopLocale() {
        return desktopLocale;
    }

    private static final Map<String, String> ZH_TEXT = new ConcurrentHashMap<>();
    private static final Map<String, String> EN_TEXT = new ConcurrentHashMap<>();

    private Messages() {
    }

    /**
     * 当前语言。
     *
     * <p>取值顺序：请求线程的 ThreadLocal &gt; 桌面端语言 &gt; 中文。</p>
     *
     * <p>关键在于第一层是「请求线程表过态」而不是「ThreadLocal 非空」。
     * 早先的实现把中文存成 {@code CURRENT.remove()}（不存脏数据），
     * 于是「请求明确要中文」和「压根不在请求线程里」两种情况长得一模一样，
     * 都会被后面的桌面端语言接管 —— 结果是：桌面端配了英文时，
     * 一个明确要求中文的请求会拿到英文。现在用一个独立的哨兵标记
     * {@link #IN_REQUEST} 把这两种情况分开。</p>
     */
    public static String current() {
        if (Boolean.TRUE.equals(IN_REQUEST.get())) {
            // 请求线程已经明确表过态，哪怕是中文也不能被桌面端语言盖掉
            return EN.equals(CURRENT.get()) ? EN : ZH;
        }
        // 不在请求线程里（AWT、启动流程）—— 用桌面端那一份，
        // 否则托盘菜单永远是中文，跟用户的选择无关
        return desktopLocale;
    }

    /**
     * 标记当前线程处于请求上下文。
     *
     * <p>只靠 {@code CURRENT} 非空判断不出「请求要中文」，因为中文在 ThreadLocal 里
     * 存的就是 null。详见 {@link #current()}。</p>
     */
    private static final ThreadLocal<Boolean> IN_REQUEST = new ThreadLocal<>();

    public static void setCurrent(String locale) {
        // 先立标记再存值：current() 靠它区分「请求要中文」与「不在请求线程」
        IN_REQUEST.set(Boolean.TRUE);
        if (EN.equals(locale)) {
            CURRENT.set(EN);
        } else {
            // 认不出的值一律当中文，不留脏数据在 ThreadLocal 里
            CURRENT.remove();
        }
    }

    /**
     * 请求结束后清理。
     *
     * <p>必须调用。Tomcat 复用线程，不清会把上一个请求的语言带给下一个请求，
     * 表现是「偶尔弹一句英文」，极难复现。标记也必须一起清，
     * 否则下一个请求会被误判成「已经表过态」而跳过桌面端语言。</p>
     */
    public static void clear() {
        CURRENT.remove();
        IN_REQUEST.remove();
    }

    /** 规范化前端传来的语言标识：zh-CN / zh_CN / ZH 都归到 zh */
    public static String normalize(String raw) {
        if (raw == null) {
            return ZH;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (value.startsWith("en")) {
            return EN;
        }
        return ZH;
    }

    /**
     * 取文案并做占位符替换。
     *
     * <p>占位符写成 {@code {0}}、{@code {1}}，与 {@code MessageFormat} 一致但不用它的
     * 单引号转义规则 —— 文案里有 {@code "a,\"b,c\",d"} 这类内容，走 MessageFormat
     * 会被单引号吃掉。</p>
     *
     * @param key   词条键
     * @param args  依次替换 {0}、{1}…
     * @return 当前语言下的文案；键不存在时返回键本身，方便定位漏配
     */
    public static String get(String key, Object... args) {
        Map<String, String> table = EN.equals(current()) ? EN_TEXT : ZH_TEXT;
        // 英文缺条目时回落中文，而不是显示键名 ——
        // 漏翻的地方用户看到中文，至少是可读的
        String text = table.get(key);
        if (text == null && table == EN_TEXT) {
            text = ZH_TEXT.get(key);
        }
        if (text == null) {
            return key;
        }
        return format(text, args);
    }

    /**
     * 全部词条键（中英合并去重，已排序）。
     *
     * <p>给测试用：{@code NoChineseInEnglishModeTest} 靠它整体扫一遍英文词表，
     * 确认没有哪条漏翻成中文。做成公开方法是因为那种「遍历所有键」的检查
     * 只能靠反射去掏私有字段，脆得一改就断。</p>
     */
    public static List<String> allKeys() {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(ZH_TEXT.keySet());
        keys.addAll(EN_TEXT.keySet());
        return List.copyOf(keys);
    }

    private static String format(String template, Object... args) {        if (args == null || args.length == 0 || template.indexOf('{') < 0) {
            return template;
        }
        String result = template;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return result;
    }

    // ==================== 词条 ====================

    static {
        // ---------- 校验与配置 ----------
        zh("error.nameBlank", "数据源名不能为空");
        en("error.nameBlank", "Data Source Name cannot be empty");

        zh("error.nameContainsDot", "数据源名不能包含英文句点：{0}");
        en("error.nameContainsDot", "Data Source Name cannot contain a period: {0}");

        zh("error.nameTaken", "数据源名已存在：{0}");
        en("error.nameTaken", "Data Source Name already exists: {0}");

        zh("error.nameRequired", "数据源名不能为空");
        en("error.nameRequired", "Data Source Name is required");

        zh("error.typeUnsupported", "不支持的数据源类型：{0}");
        en("error.typeUnsupported", "Unsupported data source type: {0}");

        zh("error.supportedTypes", "，当前支持：");
        en("error.supportedTypes", ". Available: ");

        zh("error.typeMismatch", "注册内容与类型不一致：{0}");
        en("error.typeMismatch", "Registered content does not match the type: {0}");

        zh("error.driverMissing", "不支持的驱动：{0}");
        en("error.driverMissing", "Unsupported driver: {0}");

        zh("error.driverUnsupported", "数据源 [{0}] 不支持的驱动：{1}");
        en("error.driverUnsupported", "Unsupported driver for Data Source [{0}]: {1}");

        zh("error.driverOptions", "，可选 {0} / {1}");
        en("error.driverOptions", ". Available: {0} / {1}");

        zh("error.identifierInvalid", "{0}只允许字母、数字和下划线：{1}");
        en("error.identifierInvalid", "{0} may contain only letters, digits and underscores: {1}");

        zh("error.passwordMasked", "数据源 [{0}] 的密码是脱敏占位符，请填写真实密码");
        en("error.passwordMasked", "The password for Data Source [{0}] is a masked placeholder; enter the real password");

        zh("error.loadFailed", "{0} 加载失败：{1}");
        en("error.loadFailed", "Failed to load {0}: {1}");

        zh("error.connectFailed", "连接失败：{0}");
        en("error.connectFailed", "Connection failed: {0}");

        zh("test.connectionOk", "连接成功，表 {0} 可读（探测到 {1} 列）");
        en("test.connectionOk", "Connection succeeded. Table {0} is readable ({1} columns detected)");

        zh("field.driver", "驱动");
        en("field.driver", "Driver");

        zh("field.driverDesc", "要连接的数据库类型。两者协议兼容、默认端口相同，"
                + "但认证方式与连接参数略有差异，按实际数据库选择");
        en("field.driverDesc", "Database type to connect to. The two are protocol-compatible with the same "
                + "default port, but differ slightly in authentication and connection parameters");

        zh("field.hostDesc", "数据库服务器地址，不含协议前缀与端口");
        en("field.hostDesc", "Database server address, without protocol prefix or port");

        zh("field.portDesc", "留空则使用默认端口 {0}");
        en("field.portDesc", "Leave empty to use the default port {0}");

        zh("field.databaseDesc", "目标库名，只允许字母、数字和下划线");
        en("field.databaseDesc", "Target database name; only letters, digits and underscores are allowed");

        zh("field.table", "表名");
        en("field.table", "Table Name");

        zh("field.tableDesc", "加载时执行 SELECT * 全表读取，大表请留意内存占用");
        en("field.tableDesc", "Runs SELECT * to read the whole table on load; watch memory usage for large tables");

        zh("field.pgTableDesc", "加载时执行 SELECT * 全表读取，大表请留意内存占用。"
                + "PG 的标识符不加引号时会折叠成小写，请用小写书写");
        en("field.pgTableDesc", "Runs SELECT * to read the whole table on load; watch memory usage for large tables. "
                + "Unquoted PostgreSQL identifiers fold to lowercase, so write them in lowercase");

        zh("field.usernameDesc", "登录账号");
        en("field.usernameDesc", "Login account");

        zh("field.passwordDesc", "不会出现在数据源列表的返回结果中");
        en("field.passwordDesc", "Never included in Data Source list responses");

        zh("field.schemaDesc", "PostgreSQL 的 schema（模式）。留空则使用默认的 public");
        en("field.schemaDesc", "PostgreSQL schema. Leave empty to use the default public");

        zh("field.schemaLabel", "模式名");
        en("field.schemaLabel", "Schema name");

        zh("field.sqlitePathDesc", "SQLite 数据库文件的绝对路径或相对运行目录的路径");
        en("field.sqlitePathDesc", "Absolute path of the SQLite database file, or a path relative to the working directory");

        zh("field.sqliteTableDesc", "该文件中的某张表。SQLite 一个文件里通常有多张表，每张表注册为一个数据源");
        en("field.sqliteTableDesc", "A table in that file. A single SQLite file usually holds several tables; "
                + "each is registered as one Data Source");

        zh("error.sqliteFileNotFound", "数据库文件不存在：{0}");
        en("error.sqliteFileNotFound", "Database file not found: {0}");

        zh("error.sqliteNoAutoCreate", "（SQLite 不会自动创建，检查路径）");
        en("error.sqliteNoAutoCreate", " (SQLite does not create it automatically; check the path)");

        zh("error.fieldRequired", "缺少必填配置项：{0}");
        en("error.fieldRequired", "Missing required field: {0}");

        zh("error.notAnInteger", "数据源 [{0}] 配置项 {1} 不是合法整数：{2}");
        en("error.notAnInteger", "Configuration field {1} of Data Source [{0}] is not a valid integer: {2}");

        zh("error.notABoolean", "数据源 [{0}] 配置项 {1} 不是合法布尔值：{2}");
        en("error.notABoolean", "Configuration field {1} of Data Source [{0}] is not a valid boolean: {2}");

        zh("error.datasourceNotFound", "数据源不存在：{0}");
        en("error.datasourceNotFound", "Data Source not found: {0}");

        zh("error.datasourceNotReady", "数据源尚未加载完成：{0}");
        en("error.datasourceNotReady", "Data Source is not loaded yet: {0}");

        zh("error.tableNotFound", "表名不存在：{0}");
        en("error.tableNotFound", "Table Name not found: {0}");

        zh("error.delimiterTooLong", "数据源 [{0}] 的分隔符必须是单个字符：{1}");
        en("error.delimiterTooLong", "Data Source [{0}] Delimiter must be a single character: {1}");

        zh("error.encodingUnsupported", "不支持的文件编码：{0}，可选 自动 / UTF-8 / GBK");
        en("error.encodingUnsupported", "Unsupported file encoding: {0}. Available: Auto / UTF-8 / GBK");

        zh("error.fileNotFound", "文件不存在：{0}");
        en("error.fileNotFound", "File not found: {0}");

        zh("error.fileNotReadable", "文件不可读（检查权限）：{0}");
        en("error.fileNotReadable", "File is not readable (check permissions): {0}");

        zh("error.fileEmpty", "文件为空：{0}");
        en("error.fileEmpty", "File is empty: {0}");

        zh("error.fileReadFailed", "文件读取失败：{0}");
        en("error.fileReadFailed", "Failed to read file: {0}");

        zh("error.pathRequired", "未填写文件路径");
        en("error.pathRequired", "File path is required");

        zh("error.notAFile", "该路径不是文件：{0}");
        en("error.notAFile", "The path is not a file: {0}");

        zh("error.headerUnparsable", "未能解析出表头");
        en("error.headerUnparsable", "Could not parse the header row");

        zh("error.headerNotFound", "未找到表头（文件只有空行或注释行）");
        en("error.headerNotFound", "No header row found (the file contains only blank or comment lines)");

        zh("test.fileReadable", "文件可读，共 {0} 列");
        en("test.fileReadable", "File is readable, {0} columns");

        zh("field.encodingAuto", "自动");
        en("field.encodingAuto", "Auto");

        zh("field.encodingDesc", "默认自动探测。若查询结果出现乱码（如「寮犱笁」这类无意义字符），"
                + "可尝试改为 GBK。这是 CSV 文件自身的编码，与数据库无关");
        en("field.encodingDesc", "Auto-detected by default. If the query result shows garbled or "
                + "unreadable characters, try GBK. This is the encoding of the CSV file itself, "
                + "unrelated to the database");

        zh("field.filePathDesc", "CSV 文件的绝对路径或相对运行目录的路径");
        en("field.filePathDesc", "Absolute path of the CSV file, or a path relative to the working directory");

        zh("field.commentPrefixesDesc", "多个前缀用英文逗号分隔，留空则不识别注释");
        en("field.commentPrefixesDesc", "Separate multiple prefixes with commas; leave empty to disable comment detection");

        zh("field.quoteAwareDesc", "引号内的分隔符不切分，例如 a,\"b,c\",d 解析为 3 列。文件中引号不成对时请关闭");
        en("field.quoteAwareDesc", "Delimiters inside quotes are not split, e.g. a,\"b,c\",d parses as 3 columns. "
                + "Disable this if the file has unpaired quotes");

        zh("field.delimiterDesc", "默认英文逗号；填 \\t（反斜杠加字母 t 两个字符）表示制表符");
        en("field.delimiterDesc", "Defaults to a comma; enter \\t (backslash plus the letter t) for a tab");

        zh("error.csvReadFailed", "CSV 文件读取失败：{0}（{1}）");
        en("error.csvReadFailed", "Failed to read CSV file: {0} ({1})");

        zh("error.sqliteNotFound", "数据库文件不存在：{0}");
        en("error.sqliteNotFound", "Database file not found: {0}");

        // 内存预检。写清「要多少、有多少、怎么办」：用户看到这句时
        // 本来会是一个 OOM 崩溃，所以它必须能指导下一步动作
        zh("error.memoryInsufficient",
                "「{0}」太大，无法加载：文件 {1}，预计需要约 {2} 内存，"
                        + "而当前可用上限约 {4}（JVM 堆上限 {3} 的一半）。\n"
                        + "建议改用 SQLite 数据源，或先在外部把文件拆分/筛选成更小的部分。");
        en("error.memoryInsufficient",
                "\"{0}\" is too large to load: the file is {1}, which needs about {2} of memory, "
                        + "but the available budget is about {4} (half of the {3} JVM heap limit).\n"
                        + "Consider using a SQLite datasource instead, or split/filter the file "
                        + "into smaller parts beforehand.");

        zh("error.hostInvalid", "[{0}] 的主机地址不合法：{1}");
        en("error.hostInvalid", "Invalid Host for [{0}]: {1}");

        zh("error.portInvalid", "[{0}] 的端口不合法：{1}");
        en("error.portInvalid", "Invalid Port for [{0}]: {1}");

        zh("error.serverNotReady", "Web 服务器尚未初始化，端口不可用。");
        en("error.serverNotReady", "The web server is not initialized yet; the port is unavailable.");

        // ---------- 数据源字段 ----------
        zh("field.filePath", "文件路径");
        en("field.filePath", "File Path");

        zh("field.encoding", "文件编码");
        en("field.encoding", "Encoding");

        zh("field.commentPrefixes", "注释前缀");
        en("field.commentPrefixes", "Comment Prefix");

        zh("field.quoteAware", "引号感知");
        en("field.quoteAware", "Quote Aware");

        zh("field.delimiter", "分隔符");
        en("field.delimiter", "Delimiter");

        zh("field.host", "主机地址");
        en("field.host", "Host");

        zh("field.port", "端口");
        en("field.port", "Port");

        zh("field.database", "数据库名");
        en("field.database", "Database");

        zh("field.username", "用户名");
        en("field.username", "Username");

        zh("field.password", "密码");
        en("field.password", "Password");

        zh("field.schema", "模式");
        en("field.schema", "Schema");

        zh("field.dbFilePath", "数据库文件路径");
        en("field.dbFilePath", "Database File Path");

        // ---------- Excel ----------
        // 没有编码选项是刻意的：xlsx 内部一律是 UTF-8 的 XML，
        // 不存在 CSV 那种「同一文件可能是 GBK 也可能是 UTF-8」的问题
        zh("field.xlsxPathDesc", "Excel 文件的绝对路径或相对运行目录的路径，需为 .xlsx 格式");
        en("field.xlsxPathDesc", "Absolute path of the Excel file, or a path relative to the working directory. Must be .xlsx");

        zh("field.sheetName", "工作表名");
        en("field.sheetName", "Sheet Name");

        zh("field.sheetNameDesc", "留空则读取第一张工作表。填错时提示里会列出全部可选的工作表名");
        en("field.sheetNameDesc", "Leave empty to read the first sheet. If the name does not match, the error lists every available sheet");

        zh("xlsx.columnFallback", "列{0}");
        en("xlsx.columnFallback", "Column {0}");

        zh("test.xlsxReadable", "文件可读，共 {0} 张工作表；已读取 {1} 列、{2} 行数据");
        en("test.xlsxReadable", "File is readable with {0} sheet(s); read {1} columns and {2} row(s)");

        zh("test.xlsxHeaderOnly", "文件可读，共 {0} 张工作表；已读到表头（{1}），但还没有数据行");
        en("test.xlsxHeaderOnly", "File is readable with {0} sheet(s); the header was found ({1}) but there are no data rows yet");

        zh("error.xlsxUnreadable", "Excel 文件无法解析：{0}（{1}）");
        en("error.xlsxUnreadable", "Cannot parse the Excel file: {0} ({1})");

        zh("error.xlsxReadFailed", "Excel 文件读取失败：{0}（{1}）");
        en("error.xlsxReadFailed", "Failed to read the Excel file: {0} ({1})");

        zh("error.xlsxNoSheet", "Excel 文件里没有工作表：{0}");
        en("error.xlsxNoSheet", "The Excel file contains no sheet: {0}");

        zh("error.xlsxSheetUnreadable", "无法读取工作表「{0}」：{1}");
        en("error.xlsxSheetUnreadable", "Cannot read sheet \"{0}\": {1}");

        zh("error.xlsxSheetNotFound", "工作表「{0}」不存在于 {1} 中；可用的工作表：{2}");
        en("error.xlsxSheetNotFound", "Sheet \"{0}\" does not exist in {1}; available sheets: {2}");

        zh("error.xlsNotSupported", "不支持 .xls 老格式：{0}。请在 Excel 里另存为 .xlsx 后再导入");
        en("error.xlsNotSupported", "The legacy .xls format is not supported: {0}. Save it as .xlsx in Excel and import that file");

        zh("error.notXlsx", "该文件不是有效的 Excel 文件（.xlsx）：{0}。文件可能已损坏，或被改过扩展名");
        en("error.notXlsx", "This is not a valid Excel file (.xlsx): {0}. It may be corrupted, or the extension may have been renamed");

        // ---------- 数据源类型名 ----------
        zh("type.csv", "CSV 文件");
        en("type.csv", "CSV File");

        zh("type.sqlite", "SQLite 文件");
        en("type.sqlite", "SQLite File");

        zh("type.xlsx", "Excel 文件");
        en("type.xlsx", "Excel File");

        // ---------- 查询 ----------
        zh("error.measureRequired", "请选择度量列");
        en("error.measureRequired", "Select a Measure column");

        zh("error.exportNotSupported", "聚合查询不支持导出，请导出明细数据");
        en("error.exportNotSupported", "Aggregate queries cannot be exported; export row data instead");

        // ---------- SQL 解析错误 ----------
        // 这批原来直接写在 SQLParser / QueryStatement / QueryEngine 里，
        // 而且中英混杂（"error:Missing keyword SELECT" 与 "error:WHERE 后面缺少条件表达式" 并存）。
        // 统一收进这里，顺带把混用的全角标点也归位
        zh("parse.unterminatedQuote", "单引号未闭合，检查 SQL 中的引号是否成对");
        en("parse.unterminatedQuote", "Unterminated single quote; check that quotes in the SQL come in pairs");

        zh("parse.missingSelect", "缺少 SELECT 关键字");
        en("parse.missingSelect", "Missing keyword SELECT");

        zh("parse.missingFrom", "缺少 FROM 关键字");
        en("parse.missingFrom", "Missing keyword FROM");

        zh("parse.whereMissingExpr", "WHERE 后面缺少条件表达式");
        en("parse.whereMissingExpr", "Missing condition expression after WHERE");

        zh("parse.noColumn", "SELECT 与 FROM 之间没有指定列");
        en("parse.noColumn", "No column specified between SELECT and FROM");

        zh("parse.emptyColumn", "列名为空，检查是否多写了逗号");
        en("parse.emptyColumn", "Empty column name; check for extra commas");

        zh("parse.missingTable", "FROM 后面缺少表名");
        en("parse.missingTable", "Missing Table Name after FROM");

        zh("parse.numberOutOfRange", "{0} 数值超出范围：{1}");
        en("parse.numberOutOfRange", "{0} value out of range: {1}");

        zh("parse.orderByMissingColumn", "ORDER BY 后面缺少排序列");
        en("parse.orderByMissingColumn", "Missing sort column after ORDER BY");

        zh("parse.missingColumnBeforeOp", "运算符前面缺少列名：{0}");
        en("parse.missingColumnBeforeOp", "Missing column name before operator: {0}");

        zh("parse.missingValueAfterOp", "运算符后面缺少值：{0}");
        en("parse.missingValueAfterOp", "Missing value after operator: {0}");

        zh("parse.valueHasSpace", "值含空格，用单引号包裹：{0}");
        en("parse.valueHasSpace", "Value contains spaces; wrap it in single quotes: {0}");

        // 单独的 offset m 是确实支持的，所以这句必须留着。
        // 不能按「SQL 里 offset 不能脱离 limit 单独用」的标准 SQL 习惯删掉它，
        // 那会让文案与实际行为不符。用引号把三种写法括起来：不用引号时
        // "limit n, limit n offset m, offset m" 这条逗号串读起来像一整个语法，
        // 加了引号才看得出是三个独立选项
        zh("parse.limitOffsetUnsupported", "LIMIT/OFFSET 写法不受支持。只支持三种："
                + "\"limit n\"、\"limit n offset m\"、\"offset m\"");
        en("parse.limitOffsetUnsupported", "Unsupported LIMIT/OFFSET syntax. Supported forms: "
                + "\"limit n\", \"limit n offset m\", \"offset m\"");

        zh("parse.limitCommaUnsupported", "LIMIT 不支持 \"limit 偏移,行数\" 的写法，"
                + "改用 \"limit 行数 offset 偏移\"");
        // 原文 "use limit count offset offset instead" 里连着两个 offset，
        // 头一个是关键字、后一个是行数占位名，不加引号确实像笔误。
        // 加引号把两种写法各自框起来，重复感就没了
        en("parse.limitCommaUnsupported", "LIMIT does not support the \"limit offset,count\" form; "
                + "use \"limit count offset offset\" instead");
        zh("parse.orderByUnsupported", "ORDER BY 写法不受支持，形如 order by 列名 [asc|desc][, 列名 [asc|desc]]...");
        en("parse.orderByUnsupported", "Unsupported ORDER BY syntax, e.g. order by column [asc|desc][, column [asc|desc]]...");

        zh("parse.orderByEmptyColumn", "ORDER BY 存在空的排序列，检查逗号是否写多了");
        en("parse.orderByEmptyColumn", "ORDER BY contains an empty sort column; check for extra commas");

        zh("parse.unsupportedOperator", "WHERE 子句中不支持的运算符：{0}");
        en("parse.unsupportedOperator", "Unsupported operator in WHERE clause: {0}");

        zh("parse.supportedOperators", "。支持的运算符：>=、<=、!=、<>、>、<、=");
        en("parse.supportedOperators", ". Supported operators: >=, <=, !=, <>, >, <, =");

        zh("parse.limitNegative", "LIMIT 不能为负数：{0}");
        en("parse.limitNegative", "LIMIT cannot be negative: {0}");

        zh("parse.offsetNegative", "OFFSET 不能为负数：{0}");
        en("parse.offsetNegative", "OFFSET cannot be negative: {0}");

        zh("parse.aggregateMissingParen", "聚合函数缺少右括号：{0}");
        en("parse.aggregateMissingParen", "Aggregate function is missing a closing parenthesis: {0}");

        zh("parse.invalidNumber", "条件值不是合法数字：{0}");
        en("parse.invalidNumber", "Condition value is not a valid number: {0}");

        // ---------- 提示 ----------
        zh("hint.didYouMean", "您可能想要访问 \"{0}\"");
        en("hint.didYouMean", "Did you mean \"{0}\"?");

        // ---------- 安全过滤器 ----------
        zh("security.tokenRejected", "缺少或无效的访问令牌。请从 LynMill 控制窗口点「打开界面」重新进入");
        en("security.tokenRejected", "Missing or invalid access token. Reopen the interface from the LynMill control window");

        zh("security.hostRejected", "请求的地址不是本机。LynMill 只服务于 127.0.0.1 与 localhost");
        en("security.hostRejected", "The request address is not local. LynMill only serves 127.0.0.1 and localhost");

        zh("error.internal", "服务内部错误，请查看后端日志");
        en("error.internal", "Internal server error; check the backend logs");

        // ---------- API 层通用 ----------
        zh("api.networkFailed", "网络请求失败");
        en("api.networkFailed", "Network request failed");

        zh("api.requestFailed", "请求失败");
        en("api.requestFailed", "Request failed");

        zh("api.operationFailed", "操作失败");
        en("api.operationFailed", "Operation failed");

        zh("chart.seriesCount", "计数");
        en("chart.seriesCount", "Count");

        zh("chart.emptyCategory", "(空)");
        en("chart.emptyCategory", "(Empty)");

        // ---------- 桌面端：托盘菜单 ----------
        // 这几个是 Swing/AWT 原生控件，不走 HTTP，语言来自 desktopLocale
        zh("desktop.tray.open", "打开界面");
        en("desktop.tray.open", "Open Interface");

        zh("desktop.tray.show", "显示窗口");
        en("desktop.tray.show", "Show Window");

        zh("desktop.tray.exit", "退出");
        en("desktop.tray.exit", "Exit");

        // ---------- 桌面端：控制窗口 ----------
        zh("desktop.window.running", "● 运行中");
        en("desktop.window.running", "● Running");

        zh("desktop.window.addressTip", "可选中后复制");
        en("desktop.window.addressTip", "Select to copy");

        zh("desktop.window.open", "打开界面");
        en("desktop.window.open", "Open Interface");

        zh("desktop.window.exit", "退出");
        en("desktop.window.exit", "Exit");

        // ---------- 桌面端：启动失败对话框 ----------
        // 注意：这个对话框在启动失败时弹出，那一刻 Spring 上下文可能都没起来。
        // 它的文案必须有兜底 —— 见 StartupFailureDialog 里的注释
        zh("desktop.failure.title", "LynMill 启动失败");
        en("desktop.failure.title", "LynMill Failed to Start");

        zh("desktop.failure.headline", "LynMill 启动失败，无法继续运行。");
        en("desktop.failure.headline", "LynMill failed to start and cannot continue.");

        zh("desktop.failure.openLogs", "打开日志文件夹");
        en("desktop.failure.openLogs", "Open Log Folder");

        zh("desktop.failure.confirm", "确定");
        en("desktop.failure.confirm", "OK");

        zh("desktop.failure.reason", "原因：");
        en("desktop.failure.reason", "Reason:");

        zh("desktop.failure.rootCause", "根本原因：");
        en("desktop.failure.rootCause", "Root cause:");

        zh("desktop.failure.detailLog", "详细日志：");
        en("desktop.failure.detailLog", "Detailed log:");

        zh("desktop.failure.screenshotHint", "完整错误信息未能写入文件，把本窗口内容截图发给开发者。");
        en("desktop.failure.screenshotHint", "The full error could not be written to a file; "
                + "send a screenshot of this window to the developer.");

        zh("desktop.failure.noInfo", "（没有可用的错误信息）");
        en("desktop.failure.noInfo", "(No error information available)");

        zh("desktop.failure.stderr", "标准错误输出");
        en("desktop.failure.stderr", "standard error output");

        // ---------- 桌面端：第二实例提示 ----------
        zh("desktop.duplicate.title", "LynMill 已在运行");
        en("desktop.duplicate.title", "LynMill Is Already Running");

        zh("desktop.duplicate.body", "LynMill 已经有一个实例在运行，但没能取得它的访问地址。");
        en("desktop.duplicate.body", "Another LynMill instance is already running, "
                + "but its access address could not be obtained.");

        zh("desktop.duplicate.advice", "第二个实例没能从运行地址文件中读到有效地址；"
                + "打开正在运行的 LynMill 窗口，或先退出它再重试");
        en("desktop.duplicate.advice", "The second instance could not read a valid address "
                + "from the running-address file; open the running LynMill window, "
                + "or exit it and try again");

        zh("parse.mixedSelectColumns", "聚合查询中不能混用普通列：{0}。聚合结果只有一行，"
                + "普通列没有行可引用；全部改用 count/sum/avg/max/min 这类聚合函数，"
                + "或去掉聚合函数查明细");
        en("parse.mixedSelectColumns", "Plain columns cannot be mixed into an aggregate query: {0}. "
                + "An aggregate result has only one row, so a plain column has no row to reference; "
                + "either use only aggregate functions such as count/sum/avg/max/min, "
                + "or remove the aggregate functions to query detail rows");

        // 注意：这里没有 chart.other 词条。
        // 「其他」这个类别的显示名由前端负责（前端 locales 里的 chart.other）：
        // 后端只产出 ChartResult.OTHER = "其他" 这个协议值，前端拿它做合并判断、
        // 再换成当前语言的显示名。后端曾经也放了一份 chart.other，但没有任何调用点读过它，已删。
    }

    private static void zh(String key, String text) {
        ZH_TEXT.put(key, text);
    }

    private static void en(String key, String text) {
        EN_TEXT.put(key, text);
    }
}
