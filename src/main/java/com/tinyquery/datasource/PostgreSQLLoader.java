package com.tinyquery.datasource;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PostgreSQL 数据源加载器。
 *
 * <p>与 MySQL 的主要差异：
 * 标识符在不加引号时会被折叠成小写，因此 Table/Column 名请用小写书写；
 * 表引用通常需要 schema 前缀，本加载器提供 schema 配置项，默认 public。</p>
 */
@Component
public class PostgreSQLLoader extends JdbcLoader {

    private static final String DEFAULT_SCHEMA = "public";

    @Override
    public String type() {
        return "postgresql";
    }

    @Override
    public String label() {
        return "PostgreSQL";
    }

    @Override
    protected Integer defaultPort() {
        return 5432;
    }

    @Override
    public List<FieldSpec> fields() {
        return List.of(
                FieldSpec.text("host", "主机地址", true,
                        "数据库服务器地址，不含协议前缀与端口",
                        "localhost"),
                FieldSpec.number("port", "端口", false,
                        "留空则使用默认端口 5432",
                        "5432"),
                FieldSpec.text("database", "数据库名", true,
                        "目标库名，只允许字母、数字和下划线",
                        "postgres"),
                // schema：PG 特有，缺省 public
                FieldSpec.text("schema", "模式", false,
                        "PostgreSQL 的 schema（模式）。留空则使用默认的 public",
                        DEFAULT_SCHEMA),
                FieldSpec.text("table", "表名", true,
                        "加载时执行 SELECT * 全表读取，大表请留意内存占用。"
                                + "PG 的标识符不加引号时会折叠成小写，请用小写书写",
                        "users"),
                FieldSpec.text("username", "用户名", true,
                        "登录账号",
                        "postgres"),
                // 敏感字段：前端掩码显示，服务端响应中脱敏
                FieldSpec.secret("password", "密码", true,
                        "不会出现在数据源列表的返回结果中",
                        "")
        );
    }

    @Override
    protected void validateExtra(DataSourceConfig config) {
        requireIdentifier(resolveSchema(config), "模式名");
    }

    @Override
    protected String resolveTableRef(DataSourceConfig config) {
        // PG 的表引用通常需要 schema 前缀
        return resolveSchema(config) + "." + config.requireString("table");
    }

    @Override
    protected String resolveUrl(DataSourceConfig config) {
        String host = config.requireString("host");
        if (!host.matches(HOST_PATTERN)) {
            throw new DataSourceException(
                    "数据源 [" + config.name() + "] 的主机地址不合法：" + host);
        }

        int port = config.getInt("port", defaultPort());
        String database = config.requireString("database");
        requireIdentifier(database, "数据库名");

        // 不加连接参数：PG 驱动默认 UTF-8，SSL 默认 prefer（能协商则加密，否则明文回退）
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    // 解析 schema 配置，缺失时用 public
    private String resolveSchema(DataSourceConfig config) {
        String schema = config.getString("schema");
        return schema == null ? DEFAULT_SCHEMA : schema;
    }
}