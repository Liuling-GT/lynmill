package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.datasource.spec.FieldSpec;
import com.tinyquery.i18n.Messages;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PostgreSQL 数据源加载器
 * 与 MySQL 的主要差异：
 * 标识符在不加引号时会被折叠成小写，因此 Table/Column 名请用小写书写
 * 表引用通常需要 schema 前缀，本加载器提供 schema 配置项，默认 public
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
                FieldSpec.text("host", Messages.get("field.host"), true,
                        Messages.get("field.hostDesc"),
                        "localhost"),
                FieldSpec.number("port", Messages.get("field.port"), false,
                        Messages.get("field.portDesc", defaultPort()),
                        "5432"),
                FieldSpec.text("database", Messages.get("field.database"), true,
                        Messages.get("field.databaseDesc"),
                        "postgres"),
                // schema：PG 特有，缺省 public
                FieldSpec.text("schema", Messages.get("field.schema"), false,
                        Messages.get("field.schemaDesc"),
                        DEFAULT_SCHEMA),
                FieldSpec.text("table", Messages.get("field.table"), true,
                        Messages.get("field.pgTableDesc"),
                        "users"),
                FieldSpec.text("username", Messages.get("field.username"), true,
                        Messages.get("field.usernameDesc"),
                        "postgres"),
                // 敏感字段：前端掩码显示，服务端响应中脱敏
                FieldSpec.secret("password", Messages.get("field.password"), true,
                        Messages.get("field.passwordDesc"),
                        "")
        );
    }

    @Override
    protected void validateExtra(DataSourceConfig config) {
        requireIdentifier(resolveSchema(config), Messages.get("field.schemaLabel"));
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
            throw new DataSourceException("error.hostInvalid", new Object[]{config.name(), host},
                    Messages.get("error.hostInvalid", config.name(), host));
        }

        int port = config.getInt("port", defaultPort());
        String database = config.requireString("database");
        requireIdentifier(database, Messages.get("field.database"));

        // 不加连接参数：PG 驱动默认 UTF-8，SSL 默认 prefer（能协商则加密，否则明文回退）
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    // 解析 schema 配置，缺失时用 public
    private String resolveSchema(DataSourceConfig config) {
        String schema = config.getString("schema");
        return schema == null ? DEFAULT_SCHEMA : schema;
    }
}