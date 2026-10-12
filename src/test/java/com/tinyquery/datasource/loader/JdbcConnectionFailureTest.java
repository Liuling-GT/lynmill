package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.ConnectionTestResult;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.i18n.Messages;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL / PostgreSQL 连不上时的行为 —— 用真驱动、真失败覆盖。
 *
 * <p>编号 25 补齐 JDBC 层时留了四个缺口，它们都需要一台真的 MySQL/PostgreSQL
 * 服务器才能验：真实驱动的类型映射、列标签与列名是否真的不同、schema 前缀在真 PG
 * 上是否可用。本类不碰这四个，只补第五个缺口：连接失败时的报错是否真的可读。
 * 这一条不需要服务器 —— 只要指向一个必定关闭的端口，失败路径就会真实走到，
 * 而且驱动给出的是它自己的、真实的报错文本，不是我们编的。在此之前，
 * 测试只验到「异常被包装成 DataSourceException」，
 * 没验过文案里到底有没有能帮用户定位问题的信息。</p>
 *
 * <p>夹具用 {@code 127.0.0.1:1}：环回地址上的 1 号端口实际上不会有人监听，
 * 连接会被立刻拒绝（实测 MySQL 约 195 ms、PostgreSQL 约 52 ms），
 * 不会像连不通的外网地址那样卡到超时。</p>
 *
 * <p>两个驱动的报错质量不一样：PostgreSQL 会明确说是哪个地址被拒、并给出排查
 * 方向；MySQL 只说 {@code Communications link failure}，不包含主机与端口。
 * 也就是说用户把 MySQL 端口填错时，光看报错定位不到问题。这属于产品行为，
 * 本类只记录这个差异，不做断言要求两边一样 —— 写死成「MySQL 也应当报出端口」
 * 会让测试变红，但那是产品改动，不该由测试来逼。</p>
 *
 * <p>显式设置语言：{@code Messages} 是进程级静态状态，语言相关的用例会改它。
 * JUnit 不保证执行顺序，所以这里按本项目惯例（同 {@code XlsxLoaderTest}）
 * 在每条用例前后显式复位成中文，让断言文案的用例不受顺序影响。</p>
 */
@DisplayName("JDBC 源连接失败时的行为（真驱动）")
class JdbcConnectionFailureTest {

    /** 环回地址上的 1 号端口，实际上不会有服务监听 */
    private static final String DEAD_HOST = "127.0.0.1";
    private static final int DEAD_PORT = 1;

    private String savedLocale;

    @BeforeEach
    void pinLocale() {
        savedLocale = Messages.desktopLocale();
        Messages.setDesktopLocale(Messages.ZH);
    }

    @AfterEach
    void restoreLocale() {
        Messages.setDesktopLocale(savedLocale);
    }

    private static DataSourceConfig config(String type) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("host", DEAD_HOST);
        props.put("port", DEAD_PORT);
        props.put("database", "nope");
        props.put("table", "t");
        props.put("username", "u");
        props.put("password", "p");
        return new DataSourceConfig("t", type, props);
    }

    // ==================== PostgreSQL ====================

    @Nested
    @DisplayName("PostgreSQL")
    class Postgres {

        private final PostgreSQLLoader loader = new PostgreSQLLoader();

        @Test
        @DisplayName("test() 连不上时返回失败结果，而不是抛异常")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void testFailsGracefully() {
            ConnectionTestResult r = loader.test(config("postgresql"));

            // 探测失败是「结果」不是「异常」—— 前端要拿它显示红字
            assertFalse(r.success(), "连不上时 test() 应当返回失败");
            assertNotNull(r.message(), "失败必须带说明，否则界面只能显示一个空红框");
            assertFalse(r.message().isBlank(), "失败说明不能是空串");
        }

        @Test
        @DisplayName("test() 的文案带 error.connectFailed 包装，且保留了驱动给出的原因")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void testMessageIsReadable() {
            String msg = loader.test(config("postgresql")).message();

            assertTrue(msg.contains("连接失败"),
                    "应当带 error.connectFailed 的包装：" + msg);

            // 驱动自己给的文本才是真正帮用户定位问题的部分。
            // 这段是驱动产出的，与界面语言无关，可以稳定断言
            assertTrue(msg.contains(DEAD_HOST + ":" + DEAD_PORT),
                    "应当报出连的是哪个地址 —— 否则用户不知道是主机还是端口错了：" + msg);
            assertTrue(msg.contains("refused"),
                    "应当说明是「连接被拒绝」而不是笼统的失败：" + msg);
        }

        @Test
        @DisplayName("load() 连不上时抛 DataSourceException，errorKey 为 error.loadFailed")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void loadThrowsWithKey() {
            DataSourceException e = assertThrows(DataSourceException.class,
                    () -> loader.load(config("postgresql")));

            assertEquals("error.loadFailed", e.errorKey(),
                    "连不上应当报加载失败：" + e.getMessage());
            assertTrue(e.getMessage().contains("PostgreSQL"),
                    "报错应当说清是哪个数据源类型：" + e.getMessage());
        }

        @Test
        @DisplayName("load() 的异常链里保留原始 SQLException，便于排查真实原因")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void loadKeepsSqlException() {
            DataSourceException e = assertThrows(DataSourceException.class,
                    () -> loader.load(config("postgresql")));

            assertTrue(hasSqlExceptionInChain(e),
                    "异常链里应当保留原始 SQLException，否则排查时只剩一句包装过的话");
        }
    }

    // ==================== MySQL / MariaDB ====================

    @Nested
    @DisplayName("MySQL / MariaDB")
    class MySQL {

        private final MySQLLoader loader = new MySQLLoader();

        @Test
        @DisplayName("test() 连不上时返回失败结果，而不是抛异常")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void testFailsGracefully() {
            ConnectionTestResult r = loader.test(config("MySQL"));

            assertFalse(r.success(), "连不上时 test() 应当返回失败");
            assertNotNull(r.message());
            assertFalse(r.message().isBlank());
        }

        @Test
        @DisplayName("test() 的文案带 error.connectFailed 包装，且保留了驱动给出的原因")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void testMessageIsReadable() {
            String msg = loader.test(config("MySQL")).message();

            assertTrue(msg.contains("连接失败"),
                    "应当带 error.connectFailed 的包装：" + msg);
            assertTrue(msg.contains("Communications link failure"),
                    "应当保留驱动给出的原因：" + msg);
        }

        /**
         * 记录一条已知的产品短板，而不是把它写成断言要求。
         *
         * <p>MySQL 驱动的报错不含主机与端口，用户填错端口时定位不到问题；
         * PostgreSQL 那条则明确报出地址。这个差异是真实存在的，写下来是为了让
         * 后来者知道「MySQL 报错信息弱」是已知情况，而不是自己没测到。</p>
         *
         * <p>如果哪天有人改善了 MySQL 侧的文案（比如在包装时补上地址），
         * 这条会红 —— 那时把它改成断言「应当报出地址」即可，是好事。</p>
         */
        @Test
        @DisplayName("（已知短板）MySQL 的报错不含主机与端口，与 PostgreSQL 不同")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void mysqlMessageLacksHostAndPort() {
            String msg = loader.test(config("MySQL")).message();

            assertFalse(msg.contains(DEAD_HOST + ":" + DEAD_PORT),
                    "若 MySQL 侧也开始报出地址了，请把这条改成正面断言 —— "
                            + "说明文案被改善了：" + msg);
        }

        @Test
        @DisplayName("load() 连不上时抛 DataSourceException，errorKey 为 error.loadFailed")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void loadThrowsWithKey() {
            DataSourceException e = assertThrows(DataSourceException.class,
                    () -> loader.load(config("MySQL")));

            assertEquals("error.loadFailed", e.errorKey(),
                    "连不上应当报加载失败：" + e.getMessage());
            assertTrue(e.getMessage().contains("MySQL"),
                    "报错应当说清是哪个数据源类型：" + e.getMessage());
        }

        @Test
        @DisplayName("load() 的异常链里保留原始 SQLException")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void loadKeepsSqlException() {
            DataSourceException e = assertThrows(DataSourceException.class,
                    () -> loader.load(config("MySQL")));

            assertTrue(hasSqlExceptionInChain(e),
                    "异常链里应当保留原始 SQLException");
        }

        @Test
        @DisplayName("MariaDB 与 MySQL 走同一条失败路径，行为一致")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void mariaDbBehavesTheSame() {
            DataSourceException e = assertThrows(DataSourceException.class,
                    () -> loader.load(config("MariaDB")));

            assertEquals("error.loadFailed", e.errorKey());
        }
    }

    // ==================== 失败要快 ====================

    /**
     * 防的是将来某次改动把连不上变成挂起。
     *
     * <p>上界给到 5 秒是刻意宽松的：实测最慢的 MySQL 也只有约 195 ms，
     * 这个断言不该因为机器慢而误报。它拦的是数量级的变化（比如真的等到
     * TCP 超时的几十秒），不是抖动。</p>
     */
    @Test
    @DisplayName("连不上的失败要快：不得挂起（宽松上界，防数量级变化）")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void failsFast() {
        assertFalse(new MySQLLoader().test(config("MySQL")).success());
        assertFalse(new PostgreSQLLoader().test(config("postgresql")).success());
    }

    private static boolean hasSqlExceptionInChain(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException) {
                return true;
            }
        }
        return false;
    }
}
