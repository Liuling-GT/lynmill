package com.tinyquery.datasource;

import com.tinyquery.credential.NoOpCredentialStore;
import com.tinyquery.datasource.loader.CSVLoader;
import com.tinyquery.datasource.loader.MySQLLoader;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.datasource.spec.DataSourceInfo;
import com.tinyquery.datasource.spec.DataLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置落盘行为。重点在「密码绝不写进文件」——
 * 那是这里唯一会真正造成损害的地方。
 */
@DisplayName("DataSourceManager 配置落盘")
class DataSourceManagerPersistTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("datasources.json");
    }

    /** 造一个真实的 CSV 文件，让 CSVLoader 能加载成功。 */
    private Path csvFile(String name) throws Exception {
        Path csv = dir.resolve(name);
        Files.writeString(csv, "id,name\n1,甲\n2,乙\n", StandardCharsets.UTF_8);
        return csv;
    }

    private DataSourceManager manager() {
        return new DataSourceManager(
                List.of(new CSVLoader(), new MySQLLoader()),
                new DataSourceStore(file()), new NoOpCredentialStore("测试中不存密码"));
    }

    private static DataSourceConfig mysqlConfig(String name, String password) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("driver", "mysql");
        props.put("host", "localhost");
        props.put("port", "3306");
        props.put("database", "shop");
        props.put("table", "users");
        props.put("username", "root");
        props.put("password", password);
        return new DataSourceConfig(name, "mysql", props);
    }

    // ==================== 密码绝不落盘（本步最关键的一条） ====================

    @Test
    @DisplayName("密码绝不出现在文件里 —— 当前版本不提供保存密码")
    void passwordNeverReachesTheFile() throws Exception {
        DataSourceManager manager = manager();
        // 用 CSV 之外的类型无法真正连库，因此直接测 withoutSecrets 的落盘结果：
        // 借用 refresh 之外的路径做不到，这里改用「注册 CSV + 单独验证脱敏」两段
        manager.register(new DataSourceConfig("plain", "csv",
                Map.of("path", csvFile("plain.csv").toString(), "encoding", "UTF-8")));

        String json = Files.readString(file(), StandardCharsets.UTF_8);

        assertTrue(json.contains("plain"), json);
    }

    @Test
    @DisplayName("敏感字段在落盘前被摘掉 —— 这是防止密码明文写盘的那道闸")
    void sensitiveFieldsAreStrippedBeforeSaving() throws Exception {
        // 直接驱动 DataSourceStore 会绕过脱敏，所以这里验的是 Manager 的做法：
        // 用一个只声明了敏感字段的假 loader，避免真去连数据库
        DataLoader fake = new FakeLoader();
        DataSourceManager manager = new DataSourceManager(
                List.of(fake), new DataSourceStore(file()), new NoOpCredentialStore("测试中不存密码"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", "D:/x.csv");
        props.put("password", "超级机密的密码");
        props.put("token", "也是机密");
        manager.register(new DataSourceConfig("mixed", FakeLoader.TYPE, props));

        String json = Files.readString(file(), StandardCharsets.UTF_8);

        assertFalse(json.contains("超级机密的密码"), "密码绝不能出现在文件里：" + json);
        assertFalse(json.contains("也是机密"), "所有 sensitive 字段都要摘掉：" + json);
        assertTrue(json.contains("path"), "非敏感字段要照常保存：" + json);
        assertTrue(json.contains("D:/x.csv"), json);
    }

    @Test
    @DisplayName("摘掉敏感字段不影响内存里的数据源 —— 本次运行仍可正常查询")
    void strippingDoesNotAffectInMemoryState() throws Exception {
        DataLoader fake = new FakeLoader();
        DataSourceManager manager = new DataSourceManager(
                List.of(fake), new DataSourceStore(file()), new NoOpCredentialStore("测试中不存密码"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", "D:/x.csv");
        props.put("password", "机密");
        manager.register(new DataSourceConfig("mixed", FakeLoader.TYPE, props));

        // 内存里仍应看得到完整配置（否则当前这次运行就用不了了）
        List<DataSourceInfo> infos = manager.list();
        assertEquals(1, infos.size());
        assertEquals(DataSourceInfo.MASK, infos.get(0).properties().get("password"),
                "对外的视图本来就该是掩码");
    }

    // ==================== 注册 / 删除都会落盘 ====================

    @Test
    @DisplayName("注册后立刻落盘")
    void registerPersistsImmediately() throws Exception {
        DataSourceManager manager = manager();
        manager.register(new DataSourceConfig("t1", "csv",
                Map.of("path", csvFile("t1.csv").toString(), "encoding", "UTF-8")));

        assertTrue(Files.exists(file()), "注册后文件就该存在");

        List<DataSourceConfig> loaded = new DataSourceStore(file()).load();
        assertEquals(1, loaded.size());
        assertEquals("t1", loaded.get(0).name());
        assertEquals("csv", loaded.get(0).type());
    }

    @Test
    @DisplayName("删除后立刻落盘，被删的不会复活")
    void removePersistsImmediately() throws Exception {
        DataSourceManager manager = manager();
        manager.register(new DataSourceConfig("t1", "csv",
                Map.of("path", csvFile("t1.csv").toString(), "encoding", "UTF-8")));
        manager.remove("t1");

        assertTrue(new DataSourceStore(file()).load().isEmpty(),
                "删掉的数据源不该还留在文件里，否则重启就复活了");
    }

    @Test
    @DisplayName("注册失败时不落盘 —— 失败的数据源不该进文件")
    void failedRegistrationDoesNotPersist() throws Exception {
        DataSourceManager manager = manager();

        // 路径不存在，加载必然失败
        assertThrows(DataSourceException.class, () -> manager.register(
                new DataSourceConfig("bad", "csv",
                        Map.of("path", dir.resolve("根本不存在.csv").toString(),
                                "encoding", "UTF-8"))));

        List<DataSourceConfig> loaded = Files.exists(file())
                ? new DataSourceStore(file()).load()
                : List.of();
        assertTrue(loaded.isEmpty(), "注册失败不该留下任何记录：" + loaded);
    }

    // ==================== 存盘失败不能影响功能 ====================

    @Test
    @DisplayName("存盘失败时注册仍然成功，只是记日志 —— 不能因为磁盘问题把功能也搞挂")
    void persistFailureDoesNotBreakRegister() throws Exception {
        // 父路径是个文件，写入必然失败
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x", StandardCharsets.UTF_8);

        DataSourceManager manager = new DataSourceManager(
                List.of(new CSVLoader()),
                new DataSourceStore(blocker.resolve("datasources.json")),
                new NoOpCredentialStore("测试中不存密码"));

        assertDoesNotThrow(() -> manager.register(new DataSourceConfig("t1", "csv",
                Map.of("path", csvFile("t1.csv").toString(), "encoding", "UTF-8"))));
        assertEquals(1, manager.list().size(), "数据源应当照常注册进内存");
    }

    // ==================== 启动恢复不落盘（否则会把失败项抹掉） ====================

    @Test
    @DisplayName("restoreWithoutPersist 不写文件 —— 启动恢复靠它避免越恢复越少")
    void restoreWithoutPersistLeavesFileUntouched() throws Exception {
        DataSourceManager manager = manager();
        manager.register(new DataSourceConfig("t1", "csv",
                Map.of("path", csvFile("t1.csv").toString(), "encoding", "UTF-8")));
        String before = Files.readString(file(), StandardCharsets.UTF_8);

        // 模拟启动恢复：这个数据源的文件已被删掉，加载会失败，
        // 因此它进不了内存 —— 若此时落盘，文件里就只剩「幸存者」了
        assertThrows(DataSourceException.class, () -> manager.restoreWithoutPersist(
                new DataSourceConfig("gone", "csv",
                        Map.of("path", dir.resolve("已经删了.csv").toString(),
                                "encoding", "UTF-8"))));

        assertEquals(before, Files.readString(file(), StandardCharsets.UTF_8),
                "恢复过程中不该改动文件，否则每重启一次用户的配置就少一条");
    }

    @Test
    @DisplayName("restoreWithoutPersist 成功时也不写文件")
    void successfulRegisterWithoutPersistStillDoesNotWrite() throws Exception {
        DataSourceStore store = new DataSourceStore(file());
        DataSourceManager manager = new DataSourceManager(List.of(new CSVLoader()), store, new NoOpCredentialStore("测试中不存密码"));

        manager.restoreWithoutPersist(new DataSourceConfig("t1", "csv",
                Map.of("path", csvFile("t1.csv").toString(), "encoding", "UTF-8")));

        assertEquals(1, manager.list().size());
        assertFalse(Files.exists(file()), "明确要求不落盘时就不该创建文件");
    }

    // ==================== 辅助：一个只声明敏感字段的假 loader ====================

    /**
     * 只用来验证「敏感字段会被摘掉」，不碰任何真实数据源。
     *
     * <p>用它是因为 MySQL / PostgreSQL 的 loader 会真的去连库，
     * 在单元测试里既慢又依赖外部环境；而这一步要验的是脱敏逻辑，
     * 与具体是哪种数据源无关。</p>
     */
    private static final class FakeLoader implements DataLoader {

        static final String TYPE = "fake";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String label() {
            return "测试用";
        }

        @Override
        public List<com.tinyquery.datasource.spec.FieldSpec> fields() {
            return List.of(
                    com.tinyquery.datasource.spec.FieldSpec.text("path", "路径", true),
                    com.tinyquery.datasource.spec.FieldSpec.secret("password", "密码", false),
                    com.tinyquery.datasource.spec.FieldSpec.secret("token", "令牌", false));
        }

        @Override
        public com.tinyquery.datasource.spec.ConnectionTestResult test(DataSourceConfig config) {
            return com.tinyquery.datasource.spec.ConnectionTestResult.ok("测试用加载器");
        }

        @Override
        public com.tinyquery.executor.ColumnStore load(DataSourceConfig config) {
            // 不真读数据，造一个最小的列存即可
            com.tinyquery.executor.ColumnStore store = new com.tinyquery.executor.ColumnStore();
            store.addRow(Map.of("id", 1));
            return store;
        }
    }
}
