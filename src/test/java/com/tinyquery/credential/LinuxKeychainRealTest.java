package com.tinyquery.credential;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在真实 Linux 上跑 {@link LinuxCredentialStore}，真的写进系统钥匙串。
 *
 * <p>只有在本机确实有可用的 Secret Service 时才执行。以下情况都会跳过而不是失败：
 * 非 Linux（{@code @EnabledOnOs}）；没有 libsecret（未安装桌面组件的最小化系统）；
 * 有 libsecret 但没有 keyring 守护进程 / 没有 D-Bus 会话（WSL、服务器、纯 WM 环境）；
 * keyring 存在但被锁住，且当前会话无法解锁。</p>
 *
 * <p>后三种在 Linux 上是常态而不是异常，所以跳过是正确行为 —— 硬要它失败，
 * 只会让这些环境上的构建永远红。真正该由 {@link LinuxCredentialStoreTest}
 * 保证的是「退化行为正确」，那个测试不依赖任何原生环境。</p>
 *
 * <p>测试数据用固定的 key 前缀，每个用例结束后删除，避免在用户的钥匙串里留垃圾
 * （上一轮 macOS 那边就因为留了 e2etest 条目需要手工清理）。</p>
 */
@EnabledOnOs(OS.LINUX)
@DisplayName("LinuxCredentialStore 真机（需要可用的 Secret Service）")
class LinuxKeychainRealTest {

    /** 所有测试 key 都带这个前缀，便于清理与识别 */
    private static final String PREFIX = "TinyQuery/__selftest__";

    private LinuxCredentialStore store;
    private final List<String> created = new ArrayList<>();

    /** 探测结果只算一次，避免每个用例都去碰钥匙串 */
    private static Boolean keyringUsable;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(realKeyringUsable(),
                "本机没有可用的 Secret Service（没装 libsecret、或没有 keyring 守护进程 / D-Bus 会话）—— 跳过真机测试");
        store = new LinuxCredentialStore();
        Assumptions.assumeTrue(store.isAvailable(), "凭据存储报告不可用：" + store.unavailableReason());
    }

    @AfterEach
    void tearDown() {
        // 清掉本用例留下的条目。即便断言失败也要清，否则用户钥匙串里会攒垃圾
        for (String key : created) {
            try {
                new LinuxCredentialStore().delete(key);
            } catch (Throwable ignored) {
                // 清理失败不影响测试结论
            }
        }
        created.clear();
    }

    /**
     * 真的往钥匙串里写一次再读一次来探测可用性。
     *
     * <p>只看 libsecret 能不能加载是不够的：WSL 与服务器上库在、但守护进程不在，
     * 那种情况要判成「不可用」并跳过，而不是让一堆用例报错。</p>
     */
    private static synchronized boolean realKeyringUsable() {
        if (keyringUsable != null) {
            return keyringUsable;
        }
        try {
            LinuxKeychainBackend backend = SecretService.backend();
            if (!backend.loadable()) {
                keyringUsable = Boolean.FALSE;
                return false;
            }
            String probe = PREFIX + "/probe";
            String err = backend.store(probe, probe, "probe");
            if (err != null) {
                keyringUsable = Boolean.FALSE;
                return false;
            }
            backend.delete(probe);
            keyringUsable = Boolean.TRUE;
            return true;
        } catch (Throwable t) {
            // JNA 加载失败抛的是 Error，一并兜住
            keyringUsable = Boolean.FALSE;
            return false;
        }
    }

    private String key(String suffix) {
        String k = PREFIX + "/" + suffix;
        created.add(k);
        return k;
    }

    // ==================== 基本往返 ====================

    @Test
    @DisplayName("写入后能原样读回")
    void roundTrip() {
        String k = key("roundtrip");

        assertTrue(store.write(k, "root", "s3cret-密码").success());
        CredentialResult read = store.read(k);

        assertTrue(read.success(), read.message());
        assertFalse(read.missing());
        assertEquals("s3cret-密码", read.value());
    }

    @Test
    @DisplayName("读一个从没存过的 key：是「没有密码」而不是失败")
    void missingKey() {
        CredentialResult read = store.read(key("never-stored"));

        assertTrue(read.success(), "不该是失败：" + read.message());
        assertNull(read.value());
        assertTrue(read.missing());
    }

    @Test
    @DisplayName("同一个 key 重复保存是覆盖（upsert），不是报错也不是留下两条")
    void repeatedWriteOverwrites() {
        String k = key("overwrite");

        assertTrue(store.write(k, "root", "first").success());
        assertTrue(store.write(k, "root", "second").success());

        CredentialResult read = store.read(k);
        assertTrue(read.success(), read.message());
        assertEquals("second", read.value(), "后写的应当生效");
    }

    @Test
    @DisplayName("删除后就读不到了")
    void deleteThenRead() {
        String k = key("delete");

        assertTrue(store.write(k, "root", "p").success());
        assertTrue(store.delete(k).success());

        CredentialResult read = store.read(k);
        assertTrue(read.success());
        assertTrue(read.missing(), "删掉之后应当回到「没有密码」");
    }

    @Test
    @DisplayName("删除一个本来就不存在的 key 也算成功（删除是幂等的）")
    void deleteNonExistentIsOk() {
        CredentialResult r = store.delete(key("delete-never-existed"));
        assertTrue(r.success(), "「让它没有密码」这个目标本来就已经达成：" + r.message());
    }

    // ==================== 边界值 ====================

    @Test
    @DisplayName("密码含中文、符号、空格时也能原样往返")
    void handlesChineseAndSymbols() {
        String k = key("charset");
        String password = "密码 with spaces & 符号 !@#$%^&*()_+-=[]{}|;':\",./<>? 与中文";

        assertTrue(store.write(k, "用户", password).success());
        CredentialResult read = store.read(k);

        assertTrue(read.success(), read.message());
        assertEquals(password, read.value());
    }

    @Test
    @DisplayName("空密码也能存 —— 它是合法值，不能和「没存过」混为一谈")
    void emptyPasswordIsStorable() {
        String k = key("empty");

        assertTrue(store.write(k, "root", "").success());
        CredentialResult read = store.read(k);

        assertTrue(read.success(), read.message());
        assertFalse(read.missing(), "存过了就不该报「不存在」");
        assertEquals("", read.value());
    }

    @Test
    @DisplayName("很长的密码不会被截断")
    void longPasswordNotTruncated() {
        String k = key("long");
        String password = "x".repeat(2048);

        assertTrue(store.write(k, "root", password).success());
        CredentialResult read = store.read(k);

        assertTrue(read.success(), read.message());
        assertEquals(password.length(), read.value() == null ? -1 : read.value().length());
    }

    @Test
    @DisplayName("两个不同的 key 互不干扰")
    void keysAreIsolated() {
        String a = key("iso-a");
        String b = key("iso-b");

        assertTrue(store.write(a, "u1", "password-A").success());
        assertTrue(store.write(b, "u2", "password-B").success());

        assertEquals("password-A", store.read(a).value());
        assertEquals("password-B", store.read(b).value());

        assertTrue(store.delete(a).success());
        assertTrue(store.read(a).missing(), "删了 A 之后 A 应当没了");
        assertEquals("password-B", store.read(b).value(), "B 不该受影响");
    }
}
