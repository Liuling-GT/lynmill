package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.datasource.spec.FieldSpec;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MySQL 系数据源加载器，同时支持 MySQL 与 MariaDB。
 *
 * <p>两者线协议兼容、默认端口同为 3306，差异只在 JDBC URL 的前缀与部分连接参数，
 * 因此共用一个加载器，由「驱动」配置项区分。不拆成两个类是因为
 * 拆分后会产生两个 95% 重复的实现，后续修 bug 容易只改一处。</p>
 */
@Component
public class MySQLLoader extends JdbcLoader {

    // 驱动标识。与 fields() 里下拉框的选项值一一对应
    private static final String DRIVER_MYSQL = "MySQL";
    private static final String DRIVER_MARIADB = "MariaDB";

    // MySQL 连接参数。
    // tinyInt1isBit=false 让 TINYINT(1) 返回 Integer 而非 Boolean，
    // 避免落入 ValueNormalizer 的 Boolean 降级分支
    private static final String MYSQL_PARAMS =
            "?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8&tinyInt1isBit=false";

    // MariaDB 连接参数。
    // 无 tinyInt1isBit —— MariaDB 驱动默认就把 TINYINT(1) 当数值返回；
    // 其余参数名与 MySQL 兼容
    private static final String MARIADB_PARAMS =
            "?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8";

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
                FieldSpec.select("driver", "驱动", false,
                        "要连接的数据库类型。两者协议兼容、默认端口相同，"
                                + "但认证方式与连接参数略有差异，请按实际数据库选择",
                        List.of(DRIVER_MYSQL, DRIVER_MARIADB)),
                FieldSpec.text("host", "主机地址", true,
                        "数据库服务器地址，不含协议前缀与端口",
                        "localhost"),
                FieldSpec.number("port", "端口", false,
                        "留空则使用默认端口 3306",
                        "3306"),
                FieldSpec.text("database", "数据库名", true,
                        "目标库名，只允许字母、数字和下划线",
                        "tinyquery"),
                FieldSpec.text("table", "表名", true,
                        "加载时执行 SELECT * 全表读取，大表请留意内存占用",
                        "users"),
                FieldSpec.text("username", "用户名", true,
                        "登录账号",
                        "root"),
                // 敏感字段：前端掩码显示，服务端响应中脱敏
                FieldSpec.secret("password", "密码", true,
                        "不会出现在数据源列表的返回结果中",
                        "")
        );
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

        if (DRIVER_MARIADB.equals(resolveDriver(config))) {
            return "jdbc:mariadb://" + host + ":" + port + "/" + database + MARIADB_PARAMS;
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + database + MYSQL_PARAMS;
    }

    /**
     * 解析驱动配置。
     *
     * <p>缺失或为空时返回 MySQL —— 这是刻意的：既有数据源没有 driver 字段，
     * 默认走 MySQL 可保证它们的行为完全不变。</p>
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
        throw new DataSourceException(
                "数据源 [" + config.name() + "] 不支持的驱动：" + raw
                        + "，可选 " + DRIVER_MYSQL + " / " + DRIVER_MARIADB);
    }
}