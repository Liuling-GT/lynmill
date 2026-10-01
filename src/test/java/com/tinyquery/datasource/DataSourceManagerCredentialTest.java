package com.tinyquery.datasource;

import com.tinyquery.credential.CredentialResult;
import com.tinyquery.credential.CredentialStore;
import com.tinyquery.credential.NoOpCredentialStore;
import com.tinyquery.datasource.spec.ConnectionTestResult;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataLoader;
import com.tinyquery.datasource.spec.FieldSpec;
import com.tinyquery.executor.ColumnStore;
import com.tinyquery.datasource.spec.DataSourceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「保存密码」在 DataSourceManager 这一层的行为。
 *
 * <p>用一个内存里的假凭据存储，因此不碰真实系统凭据，
 * 任何机器上都能跑，也能精确制造「不可用」「写入失败」等场景。</p>
 */
@DisplayName("DataSourceManager 保存密码")
class DataSourceManagerCredentialTest {

    @TempDir
    Path dir;

    private DataSourceStore store() {
        return new DataSourceStore(dir.resolve("datasources.json"));
    }

    /** 内存假凭据存储。非 final 是为了让个别用例覆写某个方法制造特定失败 */
    static class FakeCredentialStore implements CredentialStore {
        final Map<String, String> saved = new HashMap<>();
        boolean available = true;
        String reason = "";
        boolean failWrites = false;
        int writeCount = 0;
        int deleteCount = 0;

        @Override public boolean isAvailable() { return available; }
        @Override public String unavailableReason() { return reason; }

        @Override
        public CredentialResult write(String key, String username, String password) {
            writeCount++;
            if (!available) return CredentialResult.fail(reason);
            if (failWrites) return CredentialResult.fail("磁盘满了");
            saved.put(key, password);
            return CredentialResult.done();
        }

        @Override
        public CredentialResult read(String key) {
            if (!available) return CredentialResult.notFound();
            String v = saved.get(key);
            return v == null ? CredentialResult.notFound() : CredentialResult.ok(v);
        }

        @Override
        public CredentialResult delete(String key) {
            deleteCount++;
            saved.remove(key);
            return CredentialResult.done();
        }
    }

    /** 需要密码的假加载器：缺 password 就抛，与真实 loader 行为一致 */
    static final class NeedsPasswordLoader implements DataLoader {
        static final String TYPE = "needs-pwd";
        @Override public String type() { return TYPE; }
        @Override public String label() { return "需要密码"; }
        @Override public List<FieldSpec> fields() {
            return List.of(
                    FieldSpec.text("host", "主机", true),
                    FieldSpec.text("username", "用户名", false),
                    FieldSpec.secret("password", "密码", true));
        }
        @Override public ConnectionTestResult test(DataSourceConfig c) {
            return ConnectionTestResult.ok("ok");
        }
        @Override public ColumnStore load(DataSourceConfig c) {
            c.requireString("password");     // 缺密码必抛
            ColumnStore s = new ColumnStore();
            s.addRow(Map.of("id", 1));
            return s;
        }
    }

    private static DataSourceConfig cfg(String name, String password) {
        Map<String, Object> p = new HashMap<>();
        p.put("host", "localhost");
        p.put("username", "root");
        if (password != null) {
            p.put("password", password);
        }
        return new DataSourceConfig(name, NeedsPasswordLoader.TYPE, p);
    }

    private DataSourceManager manager(FakeCredentialStore creds) {
        return new DataSourceManager(List.of(new NeedsPasswordLoader()), store(), creds);
    }

    // ==================== 勾选保存 ====================

    @Test
    @DisplayName("勾了「保存密码」→ 密码存进凭据存储，且不写进 JSON 文件")
    void savePasswordStoresCredentialNotJson() throws Exception {
        FakeCredentialStore creds = new FakeCredentialStore();
        DataSourceManager manager = manager(creds);

        manager.register(cfg("db", "机密密码"), true);

        assertEquals("机密密码", creds.saved.get("TinyQuery/db"), "应当存进凭据存储");
        String json = java.nio.file.Files.readString(
                dir.resolve("datasources.json"), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(json.contains("机密密码"), "密码绝不能出现在 JSON 文件里：" + json);
    }

    @Test
    @DisplayName("不勾「保存密码」→ 凭据存储里什么都没有")
    void withoutSavePasswordNothingIsStored() {
        FakeCredentialStore creds = new FakeCredentialStore();
        DataSourceManager manager = manager(creds);

        manager.register(cfg("db", "机密密码"), false);

        assertTrue(creds.saved.isEmpty(), "没勾就不该存：" + creds.saved);
    }

    @Test
    @DisplayName("取消勾选会清掉之前保存的密码 —— 否则「不保存」这个选择毫无效果")
    void uncheckingClearsPreviouslySavedPassword() {
        FakeCredentialStore creds = new FakeCredentialStore();
        DataSourceManager manager = manager(creds);

        manager.register(cfg("db", "第一次"), true);
        assertEquals("第一次", creds.saved.get("TinyQuery/db"));

        manager.remove("db");
        manager.register(cfg("db", "第二次"), false);   // 这次不勾

        assertTrue(creds.saved.isEmpty(),
                "重新注册时没勾保存，旧密码必须被清掉：" + creds.saved);
    }

    // ==================== 凭据存储不可用时必须优雅退化 ====================

    @Test
    @DisplayName("凭据存储不可用 + 勾了保存 → 注册仍然成功，只是没存密码")
    void unavailableStoreDoesNotBlockRegistration() {
        FakeCredentialStore creds = new FakeCredentialStore();
        creds.available = false;
        creds.reason = "本平台不支持";
        DataSourceManager manager = manager(creds);

        assertDoesNotThrow(() -> manager.register(cfg("db", "pwd"), true),
                "存不了密码绝不能导致注册失败");
        assertEquals(1, manager.list().size(), "数据源应当照常可用");
        assertTrue(creds.saved.isEmpty());
    }

    @Test
    @DisplayName("凭据存储写入失败 → 注册仍然成功，数据源照常可用")
    void failedWriteDoesNotBlockRegistration() {
        FakeCredentialStore creds = new FakeCredentialStore();
        creds.failWrites = true;
        DataSourceManager manager = manager(creds);

        assertDoesNotThrow(() -> manager.register(cfg("db", "pwd"), true));
        assertEquals(1, manager.list().size());
    }

    @Test
    @DisplayName("凭据存储不可用时，状态接口如实报告原因")
    void unavailableReasonIsExposed() {
        FakeCredentialStore creds = new FakeCredentialStore();
        creds.available = false;
        creds.reason = "GNOME Keyring 未运行";

        DataSourceManager manager = manager(creds);

        assertFalse(manager.isCredentialStorageAvailable());
        assertEquals("GNOME Keyring 未运行", manager.credentialStorageUnavailableReason());
    }

    @Test
    @DisplayName("用空实现时一切照常 —— 主流程不依赖凭据能力")
    void noOpStoreKeepsEverythingWorking() {
        DataSourceManager manager = new DataSourceManager(
                List.of(new NeedsPasswordLoader()), store(),
                new NoOpCredentialStore("测试"));

        assertFalse(manager.isCredentialStorageAvailable());
        assertDoesNotThrow(() -> manager.register(cfg("db", "pwd"), true));
        assertEquals(1, manager.list().size());
    }

    // ==================== 启动恢复时把密码补回来 ====================

    @Test
    @DisplayName("启动恢复：配置里没密码，但凭据存储里有 → 能加载成功")
    void restoreFillsPasswordFromStore() {
        FakeCredentialStore creds = new FakeCredentialStore();
        creds.saved.put("TinyQuery/db", "之前存过的密码");
        DataSourceManager manager = manager(creds);

        // 模拟从文件恢复：配置里没有 password 字段（落盘时被摘掉了）
        boolean ok = manager.restore(cfg("db", null));

        assertTrue(ok, "应当能从凭据存储补回密码并加载成功");
        assertEquals(com.tinyquery.datasource.spec.LoadStatus.OK,
                manager.list().get(0).status());
    }

    @Test
    @DisplayName("启动恢复：凭据存储里也没有 → 标记为「需要密码」，这是正常状态")
    void restoreWithoutStoredPasswordNeedsPassword() {
        FakeCredentialStore creds = new FakeCredentialStore();
        DataSourceManager manager = manager(creds);

        boolean ok = manager.restore(cfg("db", null));

        assertFalse(ok);
        assertEquals(com.tinyquery.datasource.spec.LoadStatus.NEEDS_PASSWORD,
                manager.list().get(0).status(),
                "缺密码不是错误，是等用户补上");
    }

    @Test
    @DisplayName("启动恢复：配置里已经有密码时，不用凭据存储里的值覆盖")
    void restoreKeepsExplicitPassword() {
        FakeCredentialStore creds = new FakeCredentialStore();
        creds.saved.put("TinyQuery/db", "存储里的旧密码");
        DataSourceManager manager = manager(creds);

        boolean ok = manager.restore(cfg("db", "配置里的新密码"));

        assertTrue(ok);
        // 直接用配置里那个就能加载成功；此处验证的是没有因为覆盖而出错
        assertEquals(com.tinyquery.datasource.spec.LoadStatus.OK,
                manager.list().get(0).status());
    }

    @Test
    @DisplayName("凭据存储不可用时恢复：走「需要密码」而不是崩溃")
    void restoreWithUnavailableStoreDegrades() {
        FakeCredentialStore creds = new FakeCredentialStore();
        creds.available = false;
        creds.reason = "不可用";
        DataSourceManager manager = manager(creds);

        assertDoesNotThrow(() -> manager.restore(cfg("db", null)));
        assertEquals(com.tinyquery.datasource.spec.LoadStatus.NEEDS_PASSWORD,
                manager.list().get(0).status());
    }

    // ==================== 启动恢复绝不能删凭据（真机踩到的严重 bug） ====================

    @Test
    @DisplayName("启动恢复不能删掉已保存的凭据 —— 否则每次启动都丢密码")
    void restoreMustNotDeleteCredential() {
        FakeCredentialStore creds = new FakeCredentialStore();
        creds.saved.put("TinyQuery/db", "用户的密码");
        DataSourceManager manager = manager(creds);

        manager.restore(cfg("db", null));

        assertEquals("用户的密码", creds.saved.get("TinyQuery/db"),
                "恢复只是读凭据，绝不能删。删了的后果是：用户勾了保存密码，"
                        + "第一次重启还能连上（读取发生在删除之前），第二次就报缺密码 —— "
                        + "这种「用一次就坏」的表现极难排查");
        assertEquals(0, creds.deleteCount, "恢复流程里不该出现任何删除动作");
    }

    @Test
    @DisplayName("反复重启，凭据必须一直在（用一次就坏的 bug 就是这样漏掉的）")
    void credentialSurvivesRepeatedRestarts() {
        FakeCredentialStore creds = new FakeCredentialStore();
        DataSourceManager first = manager(creds);
        first.register(cfg("db", "持久密码"), true);
        assertEquals("持久密码", creds.saved.get("TinyQuery/db"));

        // 连续恢复五次，模拟用户反复开关程序
        for (int i = 1; i <= 5; i++) {
            DataSourceManager restarted = manager(creds);
            assertTrue(restarted.restore(cfg("db", null)), "第 " + i + " 次恢复应当成功");
            assertEquals("持久密码", creds.saved.get("TinyQuery/db"),
                    "第 " + i + " 次重启后凭据就不见了");
        }
        assertEquals(0, creds.deleteCount, "整个过程中不该发生任何删除");
    }

    @Test
    @DisplayName("恢复后再次真正注册（用户点了注册且没勾保存）→ 才允许删凭据")
    void explicitRegisterWithoutSaveStillDeletes() {
        FakeCredentialStore creds = new FakeCredentialStore();
        creds.saved.put("TinyQuery/db", "旧密码");
        DataSourceManager manager = manager(creds);

        // 用户主动注册、明确不勾保存 —— 这才是删除的正当理由
        manager.register(cfg("db", "新的密码"), false);

        assertTrue(creds.saved.isEmpty(), "用户明确不保存时，旧凭据应当被清掉");
    }

    // ==================== 删除数据源时必须连凭据一起删 ====================

    @Test
    @DisplayName("删除数据源会同时删掉保存的密码 —— 否则新建同名数据源会读到旧密码")
    void removeDeletesStoredCredential() {
        FakeCredentialStore creds = new FakeCredentialStore();
        DataSourceManager manager = manager(creds);

        manager.register(cfg("db", "会被删掉的密码"), true);
        assertEquals("会被删掉的密码", creds.saved.get("TinyQuery/db"));

        manager.remove("db");

        assertTrue(creds.saved.isEmpty(),
                "用户以为删了，密码就不能还留在系统里");
    }

    @Test
    @DisplayName("删除失败的数据源时，凭据也要删掉")
    void removeFailedSourceAlsoDeletesCredential() {
        FakeCredentialStore creds = new FakeCredentialStore();
        DataSourceManager manager = manager(creds);

        // 先让它进 failed：配置缺密码，且凭据存储里也没有
        manager.restore(cfg("db", null));
        assertTrue(manager.isUnavailable("db"), "缺密码应当进入「暂不可用」");

        // 之后用户又勾了保存（比如通过 retry 补上了密码），凭据里有了值
        creds.saved.put("TinyQuery/db", "后来存进去的密码");

        manager.remove("db");

        assertTrue(creds.saved.isEmpty(), "删掉一个坏数据源也该清掉它的密码");
    }

    @Test
    @DisplayName("删不掉凭据时删除数据源仍然成功 —— 不能因为凭据问题卡住主流程")
    void credentialDeleteFailureDoesNotBlockRemoval() {
        FakeCredentialStore creds = new FakeCredentialStore() {
            @Override
            public CredentialResult delete(String key) {
                return CredentialResult.fail("系统拒绝");
            }
        };
        DataSourceManager manager = manager(creds);
        manager.register(cfg("db", "pwd"), true);

        assertDoesNotThrow(() -> manager.remove("db"));
        assertTrue(manager.list().isEmpty());
    }

    // ==================== 凭据 key 的格式 ====================

    @Test
    @DisplayName("凭据 key 带 TinyQuery/ 前缀，便于用户在凭据管理器里辨认")
    void credentialKeyHasPrefix() {
        assertEquals("TinyQuery/my-db", DataSourceManager.credentialKey("my-db"));
    }
}
