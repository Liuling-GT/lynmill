package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL / MariaDB 连接串的构造。
 *
 * <p>这个测试类的存在理由是一次真实的线上缺陷：连接参数里漏了
 * {@code allowPublicKeyRetrieval=true}，导致 LynMill 连不上任何一台
 * 默认配置的 MySQL 8（认证插件 caching_sha2_password 在 useSSL=false 下
 * 必须允许公钥检索）。报错信息是 "Public Key Retrieval is not allowed"，
 * 看起来像账号密码问题，实际与凭据无关。</p>
 *
 * <p>之所以能长期潜伏：连接参数是 private 常量，没有任何断言盯着它。
 * 所以这里不只测「能拼出 URL」，更要逐条锁住每一个必需的参数 ——
 * 将来谁想删掉某个参数，必须显式改这个测试，而不是悄悄改常量。</p>
 */
@DisplayName("MySQL / MariaDB 连接串")
class MySQLLoaderUrlTest {

    private final MySQLLoader loader = new MySQLLoader();

    private static DataSourceConfig cfg(String driver, String host, Integer port,
                                        String database) {
        Map<String, Object> p = new HashMap<>();
        p.put("host", host);
        if (port != null) {
            p.put("port", String.valueOf(port));
        }
        p.put("database", database);
        if (driver != null) {
            p.put("driver", driver);
        }
        return new DataSourceConfig("t", "mysql", p);
    }

    // ==================== 关键：MySQL 8 认证必需参数 ====================

    @Test
    @DisplayName("MySQL 连接串必须带 allowPublicKeyRetrieval=true —— 否则连不上 MySQL 8")
    void mysqlUrlAllowsPublicKeyRetrieval() {
        String url = loader.resolveUrl(cfg("MySQL", "localhost", 3306, "shop"));

        assertTrue(url.contains("allowPublicKeyRetrieval=true"),
                "缺这个参数会让 MySQL 8 的 caching_sha2_password 认证直接失败，"
                        + "且报错看起来像账号密码错误。实际 URL：" + url);
    }

    @Test
    @DisplayName("MariaDB 驱动连 MySQL 服务端时也要带这个参数")
    void mariadbUrlAlsoAllowsPublicKeyRetrieval() {
        String url = loader.resolveUrl(cfg("MariaDB", "localhost", 3306, "shop"));

        assertTrue(url.contains("allowPublicKeyRetrieval=true"),
                "MariaDB 驱动连 MySQL 8 服务端同样会撞上 caching_sha2_password。"
                        + "实际 URL：" + url);
    }

    // ==================== 逐一锁住其余参数，防止被顺手删掉 ====================

    @Test
    @DisplayName("MySQL 连接串的四个参数一个都不能少")
    void mysqlUrlHasAllRequiredParams() {
        String url = loader.resolveUrl(cfg("MySQL", "localhost", 3306, "shop"));

        assertAll(
                () -> assertTrue(url.contains("useSSL=false"),
                        "本项目的目标基本是 localhost/内网，默认不开 TLS：" + url),
                () -> assertTrue(url.contains("serverTimezone=UTC"),
                        "时区不显式指定会随机器变化，导致时间列读出不同的值：" + url),
                () -> assertTrue(url.contains("characterEncoding=UTF-8"),
                        "不指定会让中文列出现乱码：" + url),
                () -> assertTrue(url.contains("tinyInt1isBit=false"),
                        "TINYINT(1) 要当数值返回；否则会落进 ValueNormalizer 的 Boolean 分支：" + url),
                () -> assertTrue(url.contains("allowPublicKeyRetrieval=true"),
                        "MySQL 8 首次认证需要它：" + url));
    }

    @Test
    @DisplayName("MariaDB 连接串不含 tinyInt1isBit —— MariaDB 驱动默认就当数值返回")
    void mariadbUrlOmitsTinyInt1isBit() {
        String url = loader.resolveUrl(cfg("MariaDB", "localhost", 3306, "shop"));

        assertFalse(url.contains("tinyInt1isBit"),
                "这个参数对 MariaDB 驱动无意义，带上只是误导：" + url);
    }

    // ==================== URL 结构 ====================

    @Test
    @DisplayName("驱动决定 URL 前缀")
    void driverDecidesPrefix() {
        assertTrue(loader.resolveUrl(cfg("MySQL", "localhost", 3306, "shop"))
                .startsWith("jdbc:mysql://"));
        assertTrue(loader.resolveUrl(cfg("MariaDB", "localhost", 3306, "shop"))
                .startsWith("jdbc:mariadb://"));
    }

    @Test
    @DisplayName("driver 缺失时默认走 MySQL —— 保证既有数据源行为不变")
    void missingDriverDefaultsToMysql() {
        String url = loader.resolveUrl(cfg(null, "localhost", 3306, "shop"));

        assertTrue(url.startsWith("jdbc:mysql://"),
                "老配置文件里没有 driver 字段，默认必须是 MySQL：" + url);
    }

    @Test
    @DisplayName("driver 大小写不敏感")
    void driverIsCaseInsensitive() {
        assertTrue(loader.resolveUrl(cfg("mysql", "localhost", 3306, "shop"))
                .startsWith("jdbc:mysql://"));
        assertTrue(loader.resolveUrl(cfg("mariadb", "localhost", 3306, "shop"))
                .startsWith("jdbc:mariadb://"));
    }

    @Test
    @DisplayName("不认识的驱动要报错，不能悄悄退回 MySQL")
    void unknownDriverIsRejected() {
        DataSourceException e = assertThrows(DataSourceException.class,
                () -> loader.resolveUrl(cfg("PostgreSQL", "localhost", 3306, "shop")));
        assertTrue(e.getMessage().contains("PostgreSQL"), e.getMessage());
    }

    @Test
    @DisplayName("URL 里带上主机、端口、库名")
    void urlContainsHostPortDatabase() {
        String url = loader.resolveUrl(cfg("MySQL", "db.internal", 3307, "shop"));

        assertTrue(url.startsWith("jdbc:mysql://db.internal:3307/shop?"), url);
    }

    @Test
    @DisplayName("端口缺省时用 3306")
    void defaultPortIs3306() {
        String url = loader.resolveUrl(cfg("MySQL", "localhost", null, "shop"));

        assertTrue(url.startsWith("jdbc:mysql://localhost:3306/shop?"), url);
    }

    @Test
    @DisplayName("参数段以 ? 开头且用 & 分隔 —— 拼错会让整个 URL 失效")
    void paramsAreWellFormed() {
        String url = loader.resolveUrl(cfg("MySQL", "localhost", 3306, "shop"));
        String params = url.substring(url.indexOf('?'));

        assertTrue(params.startsWith("?"), params);
        assertFalse(params.contains("??"), "不能出现两个问号：" + params);
        assertFalse(params.contains("&&"), "不能出现空参数：" + params);
        assertFalse(params.endsWith("&"), "末尾不能多一个 &：" + params);
    }

    // ==================== 输入校验 ====================

    @Test
    @DisplayName("库名缺失或为空要报错")
    void blankDatabaseIsRejected() {
        assertThrows(DataSourceException.class,
                () -> loader.resolveUrl(cfg("MySQL", "localhost", 3306, "")));
    }

    @Test
    @DisplayName("库名不能带分号等危险字符 —— 防连接串注入")
    void dangerousDatabaseNameIsRejected() {
        assertThrows(DataSourceException.class,
                () -> loader.resolveUrl(cfg("MySQL", "localhost", 3306, "shop;DROP")));
    }

    @Test
    @DisplayName("主机地址不合法要报错")
    void invalidHostIsRejected() {
        assertThrows(DataSourceException.class,
                () -> loader.resolveUrl(cfg("MySQL", "not a host", 3306, "shop")));
    }
}
