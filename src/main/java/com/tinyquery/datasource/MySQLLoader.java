package com.tinyquery.datasource;

import com.tinyquery.executor.ColumnStore;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MySQLLoader implements DataLoader {

    //标识符（库名、表名）白名单：JDBC 占位符不能用于表名，只能拼接，故必须严格校验
    private static final String IDENTIFIER_PATTERN = "[A-Za-z0-9_]+";

    //主机名白名单：防止通过 host 注入 JDBC URL 参数
    private static final String HOST_PATTERN = "[A-Za-z0-9_.\\-]+";

    @Override
    public String type() {
        return "mysql";
    }

    @Override
    public String label() {
        return "MySQL";
    }

    @Override
    public List<FieldSpec> fields() {
        return List.of(
                FieldSpec.text("host", "主机地址", true),
                // 端口非必填，缺省 3306 由 load() 兜底
                FieldSpec.number("port", "端口", false),
                FieldSpec.text("database", "数据库名", true),
                FieldSpec.text("table", "表名", true),
                FieldSpec.text("username", "用户名", true),
                // 敏感字段：前端掩码显示，服务端响应中脱敏
                FieldSpec.secret("password", "密码", true)
        );
    }

    @Override
    public ColumnStore load(DataSourceConfig config) {
        String host = config.requireString("host");
        int port = config.getInt("port", 3306);
        String database = config.requireString("database");
        String table = config.requireString("table");
        String username = config.requireString("username");
        String password = config.getString("password");
        if (password == null) {
            password = "";
        }

        // 防止把脱敏后的配置原样回传注册，导致密码被替换成 "******"
        if (DataSourceInfo.MASK.equals(password)) {
            throw new DataSourceException("数据源 [" + config.name() + "] 的密码是脱敏占位符，请填写真实密码");
        }

        requireIdentifier(database, "数据库名");
        requireIdentifier(table, "表名");
        if (!host.matches(HOST_PATTERN)) {
            throw new DataSourceException("数据源 [" + config.name() + "] 的主机地址不合法：" + host);
        }
        if (port <= 0 || port > 65535) {
            throw new DataSourceException("数据源 [" + config.name() + "] 的端口不合法：" + port);
        }

        // tinyInt1isBit=false 让 TINYINT(1) 返回 Integer 而非 Boolean，
        // 避免落入 ValueNormalizer 的 Boolean 降级分支
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8&tinyInt1isBit=false";

        ColumnStore store = new ColumnStore();

        // try-with-resources 保证连接、语句、结果集全部关闭
        try (Connection conn = DriverManager.getConnection(url, username, password);
             Statement stmt = conn.createStatement();

             //table已接受requireIdentifier方法白名单校验，无注入安全风险
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + table)) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String colName = meta.getColumnLabel(i);
                    // 与 CSV 共用同一条归一化路径
                    row.put(colName, ValueNormalizer.normalize(rs.getObject(i)));
                }
                store.addRow(row);
            }
        } catch (SQLException e) {
            throw new DataSourceException("MySQL 数据源加载失败：" + e.getMessage(), e);
        }

        return store;
    }

    // 校验库名/表名是否只含字母数字下划线
    private void requireIdentifier(String value, String fieldName) {
        if (!value.matches(IDENTIFIER_PATTERN)) {
            throw new DataSourceException(fieldName + "只允许字母、数字和下划线：" + value);
        }
    }
}