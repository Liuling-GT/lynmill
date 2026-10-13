package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.datasource.spec.FieldSpec;
import com.tinyquery.i18n.Messages;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MySQL 系数据源加载器，同时支持 MySQL 与 MariaDB
 * 两者线协议兼容、默认端口同为 3306，差异只在 JDBC URL 的前缀与部分连接参数
 * 因此共用一个加载器，由「驱动」配置项区分。不拆成两个类是因为
 * 拆分后会产生两个 95% 重复的实现，后续修 bug 容易只改一处
 */
@Component
public class MySQLLoader extends JdbcLoader {

    // 驱动标识。与 fields() 里下拉框的选项值一一对应
    private static final String DRIVER_MYSQL = "MySQL";
    private static final String DRIVER_MARIADB = "MariaDB";

    // MySQL 连接参数。
    // tinyInt1isBit=false 让 TINYINT(1) 返回 Integer 而非 Boolean，
    // 避免落入 ValueNormalizer 的 Boolean 降级分支
    //
    // allowPublicKeyRetrieval=true 是必需的，不是可选项：
    // MySQL 8 默认的认证插件是 caching_sha2_password，首次认证时服务器要求客户端
    // 提供 RSA 公钥来加密密码。而在 useSSL=false 的情况下，驱动默认拒绝主动索取公钥，
    // 于是连接直接失败并报 "Public Key Retrieval is not allowed" ——
    // 这个错误与账号密码是否正确无关，换言之：不加这个参数，LynMill 连不上
    // 任何一台默认配置的 MySQL 8。
    //
    // 安全说明：允许公钥检索意味着理论上存在中间人替换公钥的风险。
    // 对本项目可以接受 —— 数据源目标基本是 localhost 或内网；
    // 真要连公网数据库，正确做法是把 useSSL 打开（届时驱动会走 TLS 协商，
    // 不再需要这个参数），而不是靠它兜底。
    private static final String MYSQL_PARAMS =
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=UTF-8&tinyInt1isBit=false";

    // MariaDB 连接参数。
    // 无 tinyInt1isBit —— MariaDB 驱动默认就把 TINYINT(1) 当数值返回；
    // 其余参数名与 MySQL 兼容。
    // 带 allowPublicKeyRetrieval 是为了兼容连到 MySQL 8 服务端的情形
    // （MariaDB 驱动连 MySQL 服务端时同样会撞上 caching_sha2_password）；
    // MariaDB 自己的认证插件不需要它，多写这一个参数没有副作用
    private static final String MARIADB_PARAMS =
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=UTF-8";

    @Override
    public String type() {
        return "mysql";
    }

    @Override
    public String label() {
        return "MySQL / MariaDB";
    }

    @Override
    protected Integer defaultPort() {
        return 3306;
    }

    @Override
    public List<FieldSpec> fields() {
        return List.of(
                // 驱动：决定 JDBC URL 前缀。默认 MySQL，不影响既有数据源
                FieldSpec.select("driver", Messages.get("field.driver"), false,
                        Messages.get("field.driverDesc"),
                        List.of(DRIVER_MYSQL, DRIVER_MARIADB)),
                FieldSpec.text("host", Messages.get("field.host"), true,
                        Messages.get("field.hostDesc"),
                        "localhost"),
                FieldSpec.number("port", Messages.get("field.port"), false,
                        Messages.get("field.portDesc", defaultPort()),
                        "3306"),
                FieldSpec.text("database", Messages.get("field.database"), true,
                        Messages.get("field.databaseDesc"),
                        "tinyquery"),
                FieldSpec.text("table", Messages.get("field.table"), true,
                        Messages.get("field.tableDesc"),
                        "users"),
                FieldSpec.text("username", Messages.get("field.username"), true,
                        Messages.get("field.usernameDesc"),
                        "root"),
                // 敏感字段：前端掩码显示，服务端响应中脱敏
                FieldSpec.secret("password", Messages.get("field.password"), true,
                        Messages.get("field.passwordDesc"),
                        "")
        );
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

        if (DRIVER_MARIADB.equals(resolveDriver(config))) {
            return "jdbc:mariadb://" + host + ":" + port + "/" + database + MARIADB_PARAMS;
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + database + MYSQL_PARAMS;
    }

    /**
     * 解析驱动配置
     * 缺失或为空时返回 MySQL —— 这是刻意的：既有数据源没有 driver 字段
     * 默认走 MySQL 可保证它们的行为完全不变
     */
    private String resolveDriver(DataSourceConfig config) {
        String raw = config.getString("driver");
        if (raw == null) {
            return DRIVER_MYSQL;
        }
        if (DRIVER_MYSQL.equalsIgnoreCase(raw)) {
            return DRIVER_MYSQL;
        }
        if (DRIVER_MARIADB.equalsIgnoreCase(raw)) {
            return DRIVER_MARIADB;
        }
        throw new DataSourceException("error.driverUnsupported",
                new Object[]{config.name(), raw},
                Messages.get("error.driverUnsupported", config.name(), raw)
                        + Messages.get("error.driverOptions", DRIVER_MYSQL, DRIVER_MARIADB));
    }
}