package com.tinyquery.datasource;

import com.tinyquery.credential.NoOpCredentialStore;
import com.tinyquery.datasource.loader.CSVLoader;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceInfo;
import com.tinyquery.datasource.spec.LoadStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动恢复行为。
 *
 * <p>这组测试的重心只有一句话：<b>坏数据源不能让程序起不来。</b>
 * 因此每个用例都在「故意搞坏一个东西」，然后要求程序照常工作。</p>
 */
@DisplayName("DataSourceBootstrap 启动恢复")
class DataSourceBootstrapTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("datasources.json");
    }

    private Path csv(String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private DataSourceManager manager() {
        return new DataSourceManager(List.of(new CSVLoader()), new DataSourceStore(file()), new NoOpCredentialStore("测试中不存密码"));
    }

    private static DataSourceConfig csvConfig(String name, Path path) {
        return new DataSourceConfig(name, "csv",
                Map.of("path", path.toString(), "encoding", "UTF-8"));
    }

    private void bootstrap(DataSourceManager manager) {
        new DataSourceBootstrap(manager, new DataSourceStore(file()))
                .run(null);
    }

    /** 先把配置存进文件（模拟「上次运行注册过」）。 */
    private void saveToFile(DataSourceConfig... configs) throws Exception {
        new DataSourceStore(file()).save(List.of(configs));
    }

    // ==================== 正常恢复 ====================

    @Test
    @DisplayName("已保存的数据源会在启动时恢复，且可查询")
    void savedDataSourcesAreRestored() throws Exception {
        Path a = csv("a.csv", "id\n1\n");
        Path b = csv("b.csv", "id\n2\n");
        saveToFile(csvConfig("a", a), csvConfig("b", b));

        DataSourceManager manager = manager();
        bootstrap(manager);

        assertEquals(2, manager.list().size());
        assertTrue(manager.list().stream().allMatch(i -> i.status() == LoadStatus.OK));
        assertEquals(1, manager.get("a").getColumns("id").size(), "恢复后应当能直接查询");
    }

    @Test
    @DisplayName("没有保存过任何数据源时，启动不报错")
    void emptyFileIsFine() {
        DataSourceManager manager = manager();
        assertDoesNotThrow(() -> bootstrap(manager));
        assertTrue(manager.list().isEmpty());
    }

    // ==================== 核心：坏数据源不能拖垮启动 ====================

    @Test
    @DisplayName("文件已被删除 → 该条标记 FAILED，其余照常恢复，启动不抛异常")
    void missingFileIsSkippedNotFatal() throws Exception {
        Path good = csv("good.csv", "id\n1\n");
        saveToFile(csvConfig("good", good), csvConfig("gone", dir.resolve("根本没有.csv")));

        DataSourceManager manager = manager();
        assertDoesNotThrow(() -> bootstrap(manager), "一个坏数据源绝不能让启动失败");

        List<DataSourceInfo> list = manager.list();
        assertEquals(2, list.size(), "坏的那个也要列出来，否则用户以为配置丢了");

        DataSourceInfo gone = byName(list, "gone");
        assertEquals(LoadStatus.FAILED, gone.status());
        assertFalse(gone.failureReason().isBlank(), "必须给出原因，只回一句「失败」用户无从下手");

        assertEquals(LoadStatus.OK, byName(list, "good").status(), "好的那条不受影响");
        assertEquals(1, manager.get("good").getColumns("id").size());
    }

    @Test
    @DisplayName("配置整体损坏 → 启动照常，只是没有任何数据源")
    void corruptFileDoesNotBreakStartup() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(file(), "{ 坏掉的 JSON", StandardCharsets.UTF_8);

        DataSourceManager manager = manager();
        assertDoesNotThrow(() -> bootstrap(manager));
        assertTrue(manager.list().isEmpty());
    }

    @Test
    @DisplayName("全部数据源都坏掉 → 仍然正常启动（界面进得去，用户才能修）")
    void allBrokenStillStarts() throws Exception {
        saveToFile(csvConfig("x", dir.resolve("没有1.csv")),
                csvConfig("y", dir.resolve("没有2.csv")));

        DataSourceManager manager = manager();
        assertDoesNotThrow(() -> bootstrap(manager));

        assertEquals(2, manager.list().size());
        assertTrue(manager.list().stream()
                .allMatch(i -> i.status() == LoadStatus.FAILED));
    }

    // ==================== 失败的数据源不能凭空消失 ====================

    @Test
    @DisplayName("恢复失败的数据源不能被从文件里抹掉 —— 否则每重启一次配置就少一条")
    void failedSourceIsNotErasedFromFile() throws Exception {
        Path good = csv("good.csv", "id\n1\n");
        DataSourceConfig broken = csvConfig("gone", dir.resolve("没有.csv"));
        saveToFile(csvConfig("good", good), broken);

        String before = Files.readString(file(), StandardCharsets.UTF_8);

        DataSourceManager manager = manager();
        bootstrap(manager);

        String after = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(after.contains("gone"),
                "加载失败的那条必须还在文件里，用户修好文件后重启就能用：" + after);
        assertEquals(before, after, "恢复过程本来就不该改动文件");
    }

    @Test
    @DisplayName("连续启动两次，配置不会越恢复越少")
    void repeatedBootstrapDoesNotLoseConfig() throws Exception {
        Path good = csv("good.csv", "id\n1\n");
        saveToFile(csvConfig("good", good), csvConfig("gone", dir.resolve("没有.csv")));

        for (int i = 0; i < 3; i++) {
            DataSourceManager manager = manager();
            bootstrap(manager);
            assertEquals(2, manager.list().size(), "第 " + (i + 1) + " 次启动后仍应有两条");
        }

        assertEquals(2, new DataSourceStore(file()).load().size(), "文件里也仍应是两条");
    }

    // ==================== 删掉一个坏数据源 ====================

    @Test
    @DisplayName("恢复失败的数据源可以删除，删完不再出现")
    void failedSourceCanBeRemoved() throws Exception {
        saveToFile(csvConfig("gone", dir.resolve("没有.csv")));

        DataSourceManager manager = manager();
        bootstrap(manager);
        assertEquals(1, manager.list().size());

        assertDoesNotThrow(() -> manager.remove("gone"), "用户必须删得掉一个坏数据源");
        assertTrue(manager.list().isEmpty());
    }

    @Test
    @DisplayName("删掉坏数据源后重启，它不会复活")
    void removedFailedSourceStaysGone() throws Exception {
        Path good = csv("good.csv", "id\n1\n");
        saveToFile(csvConfig("good", good), csvConfig("gone", dir.resolve("没有.csv")));

        DataSourceManager first = manager();
        bootstrap(first);
        first.remove("gone");

        DataSourceManager second = manager();
        bootstrap(second);

        assertEquals(1, second.list().size());
        assertEquals("good", second.list().get(0).name());
    }

    @Test
    @DisplayName("删掉一个坏数据源，不能顺手把另一个坏数据源也抹掉")
    void removingOneFailedSourceKeepsTheOthers() throws Exception {
        Path good = csv("good.csv", "id\n1\n");
        saveToFile(csvConfig("good", good),
                csvConfig("goneA", dir.resolve("没有A.csv")),
                csvConfig("goneB", dir.resolve("没有B.csv")));

        DataSourceManager manager = manager();
        bootstrap(manager);
        assertEquals(3, manager.list().size());

        manager.remove("goneA");

        // goneB 从没被成功加载过，它不在 entries 里 ——
        // 若落盘只写 entries，它就会在这次删除中被悄悄抹掉
        assertEquals(2, manager.list().size());
        assertTrue(manager.list().stream().anyMatch(i -> i.name().equals("goneB")),
                "另一个坏数据源不该被连坐删除");

        DataSourceManager restarted = manager();
        bootstrap(restarted);
        assertTrue(restarted.list().stream().anyMatch(i -> i.name().equals("goneB")),
                "重启后 goneB 仍应在文件里");
        assertFalse(restarted.list().stream().anyMatch(i -> i.name().equals("goneA")),
                "被删掉的不该复活");
    }

    // ==================== 补上缺失的信息后重试 ====================

    @Test
    @DisplayName("坏数据源修好后，retry 能把它拉起来")
    void retryBringsSourceBack() throws Exception {
        Path target = dir.resolve("later.csv");
        saveToFile(csvConfig("later", target));

        DataSourceManager manager = manager();
        bootstrap(manager);
        assertEquals(LoadStatus.FAILED, manager.list().get(0).status());

        // 用户把文件补上了
        Files.writeString(target, "id\n7\n", StandardCharsets.UTF_8);

        manager.retry("later", Map.of());

        assertEquals(LoadStatus.OK, manager.list().get(0).status());
        assertEquals(1, manager.get("later").getColumns("id").size());
    }

    @Test
    @DisplayName("retry 时用户的补充字段会覆盖原配置（例如补上密码）")
    void retryAcceptsExtraProperties() throws Exception {
        Path target = dir.resolve("p.csv");
        saveToFile(new DataSourceConfig("p", "csv",
                Map.of("path", target.toString(), "encoding", "UTF-8", "password", "")));

        DataSourceManager manager = manager();
        bootstrap(manager);

        Files.writeString(target, "id\n9\n", StandardCharsets.UTF_8);
        manager.retry("p", Map.of("encoding", "UTF-8"));

        assertEquals(LoadStatus.OK, manager.list().get(0).status());
    }

    @Test
    @DisplayName("retry 一个本来就好的数据源 → 明确报错，不静默")
    void retryOnHealthySourceFails() throws Exception {
        Path good = csv("good.csv", "id\n1\n");
        saveToFile(csvConfig("good", good));

        DataSourceManager manager = manager();
        bootstrap(manager);

        var e = assertThrows(RuntimeException.class, () -> manager.retry("good", Map.of()));
        assertTrue(e.getMessage().contains("good"), e.getMessage());
    }

    @Test
    @DisplayName("retry 不存在的名字 → 报错")
    void retryOnUnknownNameFails() {
        DataSourceManager manager = manager();
        assertThrows(RuntimeException.class, () -> manager.retry("谁", Map.of()));
    }

    // ==================== 缺密码被单独区分出来 ====================

    @Test
    @DisplayName("缺必填敏感字段 → NEEDS_PASSWORD 而不是 FAILED（提示语完全不同）")
    void missingRequiredSecretIsClassifiedAsNeedsPassword() throws Exception {
        // 用假的 loader，避免真去连数据库
        var fake = new MissingPasswordLoader();
        DataSourceManager manager = new DataSourceManager(
                List.of(fake), new DataSourceStore(file()), new NoOpCredentialStore("测试中不存密码"));

        // 写一份「没有密码」的配置进文件
        new DataSourceStore(file()).save(List.of(
                new DataSourceConfig("db", MissingPasswordLoader.TYPE,
                        Map.of("path", "D:/x.csv"))));

        bootstrap(manager);

        DataSourceInfo info = manager.list().get(0);
        assertEquals(LoadStatus.NEEDS_PASSWORD, info.status(),
                "缺密码不是错误，是等用户补上 —— 混成 FAILED 会让用户去查文件路径");
        assertTrue(info.failureReason().contains("密码"), info.failureReason());
    }

    @Test
    @DisplayName("缺密码的数据源补上密码后即可使用")
    void needsPasswordSourceWorksAfterSupplyingPassword() throws Exception {
        var fake = new MissingPasswordLoader();
        DataSourceManager manager = new DataSourceManager(
                List.of(fake), new DataSourceStore(file()), new NoOpCredentialStore("测试中不存密码"));

        new DataSourceStore(file()).save(List.of(
                new DataSourceConfig("db", MissingPasswordLoader.TYPE, Map.of("path", "D:/x.csv"))));
        bootstrap(manager);

        manager.retry("db", Map.of("password", "补上的密码"));

        assertEquals(LoadStatus.OK, manager.list().get(0).status());
        assertEquals(1, manager.get("db").getColumns("id").size());
    }

    @Test
    @DisplayName("密码在列表里始终是掩码 —— 即使刚从失败恢复过来")
    void passwordStaysMaskedAfterRetry() throws Exception {
        var fake = new MissingPasswordLoader();
        DataSourceManager manager = new DataSourceManager(
                List.of(fake), new DataSourceStore(file()), new NoOpCredentialStore("测试中不存密码"));

        new DataSourceStore(file()).save(List.of(
                new DataSourceConfig("db", MissingPasswordLoader.TYPE, Map.of("path", "D:/x.csv"))));
        bootstrap(manager);
        manager.retry("db", Map.of("password", "不该被看到"));

        DataSourceInfo info = manager.list().get(0);
        assertEquals(DataSourceInfo.MASK, info.properties().get("password"));
        assertNotEquals("不该被看到", info.properties().get("password"));
    }

    // ==================== 辅助 ====================

    private static DataSourceInfo byName(List<DataSourceInfo> list, String name) {
        return list.stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    /** 声明了一个必填敏感字段的加载器：缺它就抛，用来制造 NEEDS_PASSWORD。 */
    private static final class MissingPasswordLoader
            implements com.tinyquery.datasource.spec.DataLoader {

        static final String TYPE = "needs-password";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String label() {
            return "需要密码的测试源";
        }

        @Override
        public List<com.tinyquery.datasource.spec.FieldSpec> fields() {
            return List.of(
                    com.tinyquery.datasource.spec.FieldSpec.text("path", "路径", true),
                    com.tinyquery.datasource.spec.FieldSpec.secret("password", "密码", true));
        }

        @Override
        public com.tinyquery.datasource.spec.ConnectionTestResult test(DataSourceConfig config) {
            return com.tinyquery.datasource.spec.ConnectionTestResult.ok("测试用");
        }

        @Override
        public com.tinyquery.executor.ColumnStore load(DataSourceConfig config) {
            // 与真实 loader 一致：缺必填项必须抛，而不是返回空结果
            config.requireString("password");
            var store = new com.tinyquery.executor.ColumnStore();
            store.addRow(Map.of("id", 1));
            return store;
        }
    }
}
