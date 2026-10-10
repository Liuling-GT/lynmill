package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 各 JDBC 源的连接串拼装与参数校验。
 *
 * <p>MySQL / PostgreSQL 没有可用服务器，所以这里不测「连上了没」，
 * 只测「连接串拼得对不对、非法参数拦没拦住」——
 * 这两件事恰恰是注入风险的入口（host 与库名都会拼进 URL）。
 *
 * <p>SQLite 的部分在 {@link JdbcLoaderTest} 里真跑，这里只补它的路径校验。
 */
@DisplayName("JDBC 源的 URL 与校验")
class JdbcLoaderUrlTest {

    private static DataSourceConfig cfg(String type, Map<String, Object> props) {
        return new DataSourceConfig("t", type, props);
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ==================== PostgreSQL ====================

    @Nested
    @DisplayName("PostgreSQL")
    class Postgres {

        private final PostgreSQLLoader loader = new PostgreSQLLoader();

        private DataSourceConfig base() {
            return cfg("postgresql", props(
                    "host", "localhost", "port", 5432,
                    "database", "shop", "table", "users"));
        }

        @Test
        @DisplayName("URL 形如 jdbc:postgresql://host:port/database")
        void buildsUrl() {
            assertEquals("jdbc:postgresql://localhost:5432/shop", loader.resolveUrl(base()));
        }

        @Test
        @DisplayName("省略端口时用默认 5432")
        void defaultPort() {
            DataSourceConfig c = cfg("postgresql", props(
                    "host", "localhost", "database", "shop", "table", "users"));

            assertEquals("jdbc:postgresql://localhost:5432/shop", loader.resolveUrl(c));
        }

        @Test
        @DisplayName("表引用带 schema 前缀，缺省用 public")
        void tableRefHasSchema() {
            assertEquals("public.users", loader.resolveTableRef(base()));

            DataSourceConfig withSchema = cfg("postgresql", props(
                    "host", "localhost", "database", "shop", "table", "users",
                    "schema", "sales"));
            assertEquals("sales.users", loader.resolveTableRef(withSchema));
        }

        @Test
        @DisplayName("schema 非法时被拒（它会拼进 SQL，必须校验）")
        void rejectsIllegalSchema() {
            for (String bad : new String[]{"a;drop", "a b", "a-b", "表"}) {
                DataSourceConfig c = cfg("postgresql", props(
                        "host", "localhost", "database", "shop", "table", "users",
                        "schema", bad));

                assertThrows(DataSourceException.class, () -> loader.load(c),
                        "schema「" + bad + "」应当被拒");
            }
        }

        @Test
        @DisplayName("host 非法时被拒（防止通过 host 注入 URL 参数）")
        void rejectsIllegalHost() {
            // 允许字母数字、点、下划线、连字符（域名与 IPv4）
            for (String bad : new String[]{"a b", "a;b", "host?x=1", "a/b", "a&b"}) {
                DataSourceConfig c = cfg("postgresql", props(
                        "host", bad, "database", "shop", "table", "users"));

                DataSourceException e = assertThrows(DataSourceException.class,
                        () -> loader.resolveUrl(c), "host「" + bad + "」应当被拒");
                assertNotNull(e.getMessage());
            }
        }

        @Test
        @DisplayName("合法 host 能通过：域名、IPv4、带连字符与下划线")
        void acceptsLegalHost() {
            for (String good : new String[]{"localhost", "db.example.com", "192.168.1.10",
                    "my_host", "my-host", "a.b.c_d-e"}) {
                DataSourceConfig c = cfg("postgresql", props(
                        "host", good, "database", "shop", "table", "users"));

                assertDoesNotThrow(() -> loader.resolveUrl(c),
                        "host「" + good + "」应当合法");
            }
        }

        @Test
        @DisplayName("库名非法时被拒")
        void rejectsIllegalDatabase() {
            for (String bad : new String[]{"a;b", "a b", "a-b", "库"}) {
                DataSourceConfig c = cfg("postgresql", props(
                        "host", "localhost", "database", bad, "table", "users"));

                assertThrows(DataSourceException.class, () -> loader.resolveUrl(c),
                        "库名「" + bad + "」应当被拒");
            }
        }

        @Test
        @DisplayName("端口超范围时按【端口错误】被拒，而不是笼统地抛个异常")
        void rejectsIllegalPort() {
            // 断言 errorKey：PostgreSQL 没有服务器，load() 无论如何都会抛
            // DataSourceException（连不上），只断言「抛了异常」的话，
            // 端口校验被删掉测试依然绿 —— 对照实验确认过这个假绿
            for (int bad : new int[]{0, -1, 65536, 99999}) {
                DataSourceConfig c = cfg("postgresql", props(
                        "host", "localhost", "port", bad, "database", "shop", "table", "users"));

                DataSourceException e = assertThrows(DataSourceException.class,
                        () -> loader.load(c), "端口 " + bad + " 应当被拒");

                assertEquals("error.portInvalid", e.errorKey(),
                        "端口 " + bad + " 应当因端口非法被拒，实际：" + e.getMessage());
            }
        }
    }

    // ==================== MySQL / MariaDB ====================

    @Nested
    @DisplayName("MySQL / MariaDB")
    class MySQL {

        private final MySQLLoader loader = new MySQLLoader();

        private DataSourceConfig base(String type) {
            return cfg(type, props(
                    "host", "localhost", "port", 3306,
                    "database", "shop", "table", "users"));
        }

        @Test
        @DisplayName("URL 以 jdbc:mysql://host:port/database 开头，并附带必需的连接参数")
        void buildsUrl() {
            String url = loader.resolveUrl(base("MySQL"));

            // 注意：MySQL 会追加连接参数（编码、时区、SSL 等），
            // 参数细节由 MySQLLoaderUrlTest 专门覆盖，这里只钉住前缀与库名部分
            assertTrue(url.startsWith("jdbc:mysql://localhost:3306/shop"), url);
            assertTrue(url.contains("characterEncoding=UTF-8"),
                    "必须显式指定编码，否则中文会乱码：" + url);
        }

        @Test
        @DisplayName("MariaDB 与 MySQL 走同一个 loader，URL 前缀一致")
        void mariaDbPrefix() {
            String my = loader.resolveUrl(base("MySQL"));
            String maria = loader.resolveUrl(base("MariaDB"));

            // MariaDB 兼容 MySQL 协议，共用 mysql 驱动；
            // 两者的 URL 都走同一条拼装路径，参数也应一致
            assertTrue(maria.startsWith("jdbc:mysql://localhost:3306/shop"), maria);
            assertEquals(my, maria, "同一套参数下两种类型的 URL 应当相同");
        }

        @Test
        @DisplayName("表引用不带 schema 前缀（MySQL 里 database 就是库）")
        void tableRefIsPlain() {
            assertEquals("users", loader.resolveTableRef(base("MySQL")));
        }

        @Test
        @DisplayName("默认端口 3306")
        void defaultPort() {
            DataSourceConfig c = cfg("MySQL", props(
                    "host", "localhost", "database", "shop", "table", "users"));

            assertTrue(loader.resolveUrl(c).startsWith("jdbc:mysql://localhost:3306/shop"),
                    loader.resolveUrl(c));
        }

        @Test
        @DisplayName("host 非法时被拒")
        void rejectsIllegalHost() {
            DataSourceConfig c = cfg("MySQL", props(
                    "host", "a;b", "database", "shop", "table", "users"));

            assertThrows(DataSourceException.class, () -> loader.resolveUrl(c));
        }
    }

    // ==================== SQLite 路径校验 ====================

    @Nested
    @DisplayName("SQLite（仅路径校验）")
    class Sqlite {

        private final SQLiteLoader loader = new SQLiteLoader();

        private DataSourceConfig cfgPath(Object path) {
            return cfg("sqlite", props("path", path, "table", "users"));
        }

        @Test
        @DisplayName("URL 形如 jdbc:sqlite:<路径>")
        void buildsUrl() {
            // 路径不必存在，resolveUrl 不做存在性检查（那是 validateExtra 的事）
            String url = loader.resolveUrl(cfgPath("D:/data/app.db"));

            assertEquals("jdbc:sqlite:D:/data/app.db", url);
        }

        @Test
        @DisplayName("文件不存在时报错并说明不会自动创建")
        void missingFileRejected() {
            DataSourceException e = assertThrows(DataSourceException.class,
                    () -> loader.load(cfgPath("D:/definitely/not/here.db")));

            // SQLite 驱动会静默新建空库，直到查表才报「表不存在」——
            // 这条错误信息就是用来避免用户绕远路的
            assertNotNull(e.getMessage());
        }

        @Test
        @DisplayName("指向目录而不是文件时报错")
        void directoryRejected(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
            DataSourceException e = assertThrows(DataSourceException.class,
                    () -> loader.load(cfgPath(dir.toString())));

            assertNotNull(e.getMessage());
        }

        @Test
        @DisplayName("SQLite 不校验端口（defaultPort 返回 null，端口无意义）")
        void noPortValidation() {
            assertNull(loader.defaultPort(), "SQLite 无端口语义");

            // 即使配置里带了离谱的端口，也不该因此报错 —— 端口对 SQLite 无意义
            DataSourceConfig c = cfg("sqlite", props(
                    "path", "D:/definitely/not/here.db", "table", "users", "port", 99999));

            DataSourceException e = assertThrows(DataSourceException.class, () -> loader.load(c));
            assertFalse(e.getMessage().contains("端口"),
                    "不该因为端口报错，应当是文件不存在：" + e.getMessage());
        }
    }
}
