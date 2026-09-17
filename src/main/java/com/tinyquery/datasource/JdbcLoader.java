package com.tinyquery.datasource;

import com.tinyquery.executor.ColumnStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDBC 类数据源加载器的公共骨架。
 * <p>各源之间的差异只有「连接串」与「表名的组成方式」两处，
 * 建连、探测、遍历结果集、归一化这些流程完全相同。放在基类里之后，
 * 修一处 bug 对所有 JDBC 源同时生效；子类只需给出连接串与配置项清单。</p>
 *
 * <p>子类实现 resolveUrl 即可；有 schema 前缀、无凭据等差异通过覆写相应的钩子表达。</p>
 */
public abstract class JdbcLoader implements DataLoader {

    // 标识符（库名、表名、schema）白名单：JDBC 占位符不能用于表名，只能拼接，故必须严格校验
    protected static final String IDENTIFIER_PATTERN = "[A-Za-z0-9_]+";

    // 主机名白名单：防止通过 host 注入 JDBC URL 参数
    protected static final String HOST_PATTERN = "[A-Za-z0-9_.\\-]+";

    /**
     * 默认端口。
     *
     * <p>返回 null 表示这个源不使用网络端口（如 SQLite）。
     * 无端口的源必须在 fields() 里省略 port 字段，二者要保持一致。</p>
     */
    protected abstract Integer defaultPort();

    /** 拼装 JDBC 连接串。测试与加载共用，避免两处参数不一致 */
    protected abstract String resolveUrl(DataSourceConfig config);

    /**
     * 拼装用于 SQL 的表引用。
     *
     * <p>默认直接取 table 配置项；PostgreSQL 会覆写成 schema.table</p>
     */
    protected String resolveTableRef(DataSourceConfig config) {
        return config.requireString("table");
    }

    /**
     * 子类自己的配置校验。
     *
     * <p>基类已完成表名与端口的基础校验，此钩子只放子类特有的部分
     * （如 schema 校验、SQLite 的文件存在性检查）</p>
     */
    protected void validateExtra(DataSourceConfig config) {
    }

    @Override
    public final ConnectionTestResult test(DataSourceConfig config) {
        // 与 load() 相同的校验顺序，保证「测得过」就等于「注册得了」
        try {
            validateCommon(config);
            validateExtra(config);
        } catch (DataSourceException e) {
            // 校验类失败转成测试结果，不向上抛
            return ConnectionTestResult.fail(e.getMessage());
        }

        String url = resolveUrl(config);
        String tableRef = resolveTableRef(config);
        String username = config.getString("username");
        String password = config.getString("password");

        // SELECT * ... LIMIT 0：LIMIT 0 保证零行返回，因此不拉任何数据，
        // 耗时与表大小无关；同时结果集带完整列定义，列数即表的真实列数。
        // 注意不能写成 SELECT 1，那样结果集只有字面量一列，与表结构无关
        String probeSql = "SELECT * FROM " + tableRef + " LIMIT 0";

        try (Connection conn = open(url, username, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(probeSql)) {

            // 拿到列数即证明表可读
            int columnCount = rs.getMetaData().getColumnCount();
            return ConnectionTestResult.ok(
                    "连接成功，表 " + tableRef + " 可读（探测到 " + columnCount + " 列）");
        } catch (SQLException e) {
            // SQLException 的消息本身已包含原因（Access denied / Unknown database / Table doesn't exist）
            return ConnectionTestResult.fail("连接失败：" + e.getMessage());
        }
    }

    @Override
    public final ColumnStore load(DataSourceConfig config) {
        validateCommon(config);
        validateExtra(config);

        String url = resolveUrl(config);
        String tableRef = resolveTableRef(config);
        String username = config.getString("username");
        String password = config.getString("password");

        // 防止把脱敏后的配置原样回传注册，导致密码被替换成 "******"
        if (DataSourceInfo.MASK.equals(password)) {
            throw new DataSourceException("数据源 [" + config.name() + "] 的密码是脱敏占位符，请填写真实密码");
        }

        ColumnStore store = new ColumnStore();

        // try-with-resources 保证连接、语句、结果集全部关闭
        try (Connection conn = open(url, username, password);
             Statement stmt = conn.createStatement();

             // tableRef 已接受 requireIdentifier 白名单校验，无注入安全风险
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableRef)) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String colName = meta.getColumnLabel(i);
                    // 所有 JDBC 源与 CSV 共用同一条归一化路径
                    row.put(colName, ValueNormalizer.normalize(rs.getObject(i)));
                }
                store.addRow(row);
            }
        } catch (SQLException e) {
            throw new DataSourceException(label() + " 加载失败：" + e.getMessage(), e);
        }

        return store;
    }

    // 建立连接。无凭据的源（如 SQLite）配置里没有 username，走单参数重载
    private Connection open(String url, String username, String password) throws SQLException {
        if (username == null) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password == null ? "" : password);
    }

    /**
     * 所有 JDBC 源共有的校验：表名与端口。
     *
     * <p>读的是原始 table 配置项而不是 resolveTableRef 的结果 ——
     * PostgreSQL 的引用带 schema 前缀，含点号，会通不过标识符白名单。</p>
     */
    protected final void validateCommon(DataSourceConfig config) {
        requireIdentifier(config.requireString("table"), "表名");

        // 无端口的源（SQLite）直接跳过，不依赖「配置里有没有 port 键」来推断
        Integer defaultPort = defaultPort();
        if (defaultPort == null) {
            return;
        }

        int port = config.getInt("port", defaultPort);
        if (port <= 0 || port > 65535) {
            throw new DataSourceException(
                    "数据源 [" + config.name() + "] 的端口不合法：" + port);
        }
    }

    // 校验标识符是否只含字母数字下划线
    protected final void requireIdentifier(String value, String fieldName) {
        if (!value.matches(IDENTIFIER_PATTERN)) {
            throw new DataSourceException(fieldName + "只允许字母、数字和下划线：" + value);
        }
    }
}