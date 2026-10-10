package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.ConnectionTestResult;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.executor.ColumnStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcLoader} 基类的真实覆盖。
 *
 * <p><b>为什么用真的 SQLite 而不是 mock 掉 JDBC</b>：
 * 这个基类的坑几乎全在「真实驱动的实际行为」上 —— 类型映射、NULL 怎么返回、
 * 列标签取的是别名还是原名、资源有没有关掉。把 Connection 换成 mock，
 * 这些全部测不到，测的只是「我们以为驱动会怎么表现」。
 *
 * <p>SQLite 驱动已在依赖里（含多平台原生库），建个临时库即可真跑，
 * 比 mock 更省事也更有价值。
 *
 * <p>MySQL / PostgreSQL 没有可用服务器，那两边的 URL 拼装与参数校验
 * 在各自的分包测试里覆盖。
 */
@DisplayName("JdbcLoader 基类（真跑 SQLite）")
class JdbcLoaderTest {

    private Path db;

    @BeforeEach
    void createDatabase() throws Exception {
        db = java.nio.file.Files.createTempDirectory("tq-jdbc").resolve("test.db");
        // 用驱动自己建库建表，不手工拼 SQL 文件 —— 保证夹具与驱动版本一致
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE people (id INTEGER, name TEXT, score REAL)");
            stmt.execute("INSERT INTO people VALUES (1, '张三', 91.5)");
            stmt.execute("INSERT INTO people VALUES (2, '李四', 78.0)");
            stmt.execute("INSERT INTO people VALUES (3, NULL, NULL)");
        }
    }

    @AfterEach
    void deleteDatabase() throws Exception {
        if (db != null && db.getParent() != null) {
            try (var files = java.nio.file.Files.walk(db.getParent())) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 临时目录清理失败不影响测试结论
                    }
                });
            }
        }
    }

    private SQLiteLoader loader() {
        return new SQLiteLoader();
    }

    private DataSourceConfig config(String path, String table) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", path);
        props.put("table", table);
        return new DataSourceConfig("t", "sqlite", props);
    }

    private DataSourceConfig goodConfig() {
        return config(db.toString(), "people");
    }

    // ==================== load：真实取数 ====================

    @Test
    @DisplayName("load 真的把行读出来了，且列名来自表结构")
    void loadReadsRows() {
        ColumnStore store = loader().load(goodConfig());

        assertEquals(3, store.getColumnIndex(), "应当读到 3 行");
        assertEquals(List.of("id", "name", "score"), store.getColumnNames());
    }

    @Test
    @DisplayName("load 保留列顺序，与 SELECT * 的顺序一致")
    void loadKeepsColumnOrder() {
        ColumnStore store = loader().load(goodConfig());

        // 列顺序错了会让界面列错位，且不会报错 —— 属于静默错误
        assertEquals(List.of("id", "name", "score"), store.getColumnNames());
    }

    @Test
    @DisplayName("load 的值可读且类型正确，NULL 仍是 null")
    void loadValuesAndNull() {
        ColumnStore store = loader().load(goodConfig());

        List<Object> ids = store.getColumns("id");
        List<Object> names = store.getColumns("name");
        List<Object> scores = store.getColumns("score");

        assertEquals(3, ids.size());
        // SQLite 的 INTEGER 经 JDBC 可能是 Integer 或 Long，两种都算对
        assertEquals(1, ((Number) ids.get(0)).intValue());

        assertEquals("张三", names.get(0));
        assertEquals("李四", names.get(1));
        assertNull(names.get(2), "SQL NULL 经归一化后应当仍是 null");

        assertEquals(91.5, ((Number) scores.get(0)).doubleValue(), 1e-9);
        assertNull(scores.get(2), "NULL 数值列不该被写成 0 —— 那会让统计失真");
    }

    @Test
    @DisplayName("load 后表为空时返回空 store 而不是报错")
    void loadEmptyTable() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE empty_t (a INTEGER)");
        }

        ColumnStore store = loader().load(config(db.toString(), "empty_t"));

        assertEquals(0, store.getColumnIndex());
    }

    // ==================== test：探测连接 ====================

    @Test
    @DisplayName("test 成功时报告表名与列数")
    void testReportsColumnCount() {
        ConnectionTestResult r = loader().test(goodConfig());

        assertTrue(r.success(), "应当探测成功：" + r.message());
        assertTrue(r.message().contains("people"), r.message());
        assertTrue(r.message().contains("3"), "应当报出 3 列：" + r.message());
    }

    @Test
    @DisplayName("test 表不存在时失败，且不抛异常")
    void testMissingTableFailsGracefully() {
        ConnectionTestResult r = loader().test(config(db.toString(), "nope"));

        // 探测失败是「结果」不是「异常」—— 前端要拿它显示红字
        assertFalse(r.success());
        assertNotNull(r.message());
    }

    @Test
    @DisplayName("test 与 load 的校验顺序一致：校验失败时 test 返回失败而不是抛异常")
    void testValidationFailureBecomesResult() {
        // 待办里记的这条一致性很重要：否则会出现「测得过但注册不了」
        DataSourceConfig bad = config(db.toString(), "bad-name!");

        ConnectionTestResult r = loader().test(bad);
        assertFalse(r.success(), "标识符非法时 test 应当失败");

        assertThrows(DataSourceException.class, () -> loader().load(bad),
                "同一配置 load 必须抛异常 —— 两者判定要一致");
    }

    // ==================== 校验 ====================

    @Test
    @DisplayName("表名非法时按【标识符错误】被拒，而不是笼统地抛个异常")
    void rejectsIllegalTableName() {
        // 断言 errorKey 而不是「抛没抛异常」：后者太弱 ——
        // 表名非法时即使校验被删掉，load() 仍会因为「表不存在」抛
        // DataSourceException，测试照样绿。那种绿是假的（对照实验实测确认过）
        //
        // 不含空串：空串会先被 requireString 拦下报 fieldRequired（必填项缺失），
        // 那是另一条校验，单独在下面断言
        for (String bad : new String[]{"a;drop table x", "a b", "a-b", "a.b", "表", "a'"}) {
            DataSourceException e = assertThrows(DataSourceException.class,
                    () -> loader().load(config(db.toString(), bad)),
                    "表名「" + bad + "」应当被拒");

            assertEquals("error.identifierInvalid", e.errorKey(),
                    "表名「" + bad + "」应当因标识符非法被拒，实际：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("表名为空 → 按【必填项缺失】被拒（与标识符非法是两条不同的校验）")
    void emptyTableNameIsRequiredError() {
        DataSourceException e = assertThrows(DataSourceException.class,
                () -> loader().load(config(db.toString(), "")));

        assertEquals("error.fieldRequired", e.errorKey(),
                "空表名应当报必填项缺失：" + e.getMessage());
    }

    @Test
    @DisplayName("合法表名能通过白名单校验（不过度收紧）")
    void acceptsLegalTableName() throws Exception {
        // 不能直接用 load() 断言「不抛异常」—— 表不存在时它照样会抛，
        // 那样测的是「表存不存在」而不是「白名单松不松」。
        // 这里建一张真表，并覆盖几种合法写法
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE people_2 (a INTEGER)");
        }

        assertDoesNotThrow(() -> loader().load(config(db.toString(), "people_2")),
                "下划线数字应当合法");
        assertDoesNotThrow(() -> loader().load(config(db.toString(), "people")),
                "纯字母应当合法");
    }

    // ==================== 密码脱敏保护 ====================

    @Test
    @DisplayName("密码是脱敏值 ****** 时拒绝加载，防止把掩码当真密码存下来")
    void rejectsMaskedPassword() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", db.toString());
        props.put("table", "people");
        props.put("password", "******");

        DataSourceException e = assertThrows(DataSourceException.class,
                () -> loader().load(new DataSourceConfig("t", "sqlite", props)));

        // 这条防的是「把脱敏后的配置原样回传注册」——
        // 真发生的话密码会被写成字面量 ******，且没有任何提示
        assertTrue(e.getMessage().contains("******") || e.getMessage().contains("密码")
                        || e.getMessage().contains("掩码"),
                "报错应当说明是密码掩码问题：" + e.getMessage());
    }

    // ==================== 连接失败的错误包装 ====================

    @Test
    @DisplayName("查询失败被包装成 DataSourceException，且保留 SQLException 作为 cause")
    void sqlExceptionIsWrapped() {
        // 注意夹具：这里必须用一个**存在**的库 + **不存在**的表。
        // 库文件不存在的话，SQLiteLoader.validateExtra 会先把请求拦下，
        // 根本走不到 JDBC 这一层 —— 那样就测不到错误包装了。
        DataSourceConfig c = config(db.toString(), "no_such_table");

        DataSourceException e = assertThrows(DataSourceException.class, () -> loader().load(c));

        assertNotNull(e.getMessage());
        // 从异常链里找 SQLException：中间可能隔着一层驱动自己的异常类型
        Throwable cause = e.getCause();
        boolean found = false;
        while (cause != null) {
            if (cause instanceof SQLException) {
                found = true;
                break;
            }
            cause = cause.getCause();
        }
        assertTrue(found, "异常链里应当保留原始 SQLException，便于排查真实原因");
    }

    @Test
    @DisplayName("库文件不存在时在参数校验阶段就被拦下，给出「不会自动建库」的说明")
    void missingDatabaseFileRejectedEarly() {
        // SQLite 路径写错时驱动会静默新建空库，直到查表才报错。
        // SQLiteLoader.validateExtra 提前拦下，这条断言守住那个行为
        DataSourceConfig c = config(db.getParent().resolve("definitely-missing.db").toString(), "people");

        DataSourceException e = assertThrows(DataSourceException.class, () -> loader().load(c));

        assertTrue(e.getMessage().contains("不存在") || e.getMessage().contains("自动"),
                "应当说清是文件不存在、且不会自动创建：" + e.getMessage());
    }

    // ==================== 列标签 ====================

    @Test
    @DisplayName("列名取的是列标签（getColumnLabel），SQL 别名能生效")
    void usesColumnLabel() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE VIEW v AS SELECT id AS 主键, name AS 姓名 FROM people");
        }

        ColumnStore store = loader().load(config(db.toString(), "v"));

        assertEquals(List.of("主键", "姓名"), store.getColumnNames(),
                "SQL 别名应当成为列名");
    }

    /**
     * 诚实记录一条测试的边界。
     *
     * <p>{@code load()} 里用的是 {@code getColumnLabel}，而这条在 SQLite 上
     * <b>测不出区别</b>：实测（写探针直接问驱动）SQLite 的
     * {@code getColumnName} 对别名也返回别名（{@code SELECT id AS 主键} 时
     * 两个 API 都返回「主键」）。也就是说，把它改成 {@code getColumnName}，
     * 上面那条用例照样绿 —— 对照实验已经确认了这一点。
     *
     * <p>用 {@code getColumnLabel} 仍然是对的：那是 JDBC 规范里语义正确的那个
     * （MySQL/PostgreSQL 上两者确实会不同）。只是这一条在 SQLite 夹具下没有
     * 区分力，所以在这里写明，避免后来者误以为它守住了这个行为。
     */
    @Test
    @DisplayName("（边界说明）getColumnLabel 与 getColumnName 在 SQLite 上无区别，本夹具测不出")
    void columnLabelVsNameCannotBeDistinguishedOnSqlite() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE VIEW v3 AS SELECT id AS 别名 FROM people");
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT * FROM v3")) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();

                // 两个 API 返回同一个值 —— 这就是测不出区别的原因
                assertEquals(meta.getColumnName(1), meta.getColumnLabel(1),
                        "SQLite 上两者相同，故本夹具无法验证 load() 用的是哪个");
            }
        }
    }
}
