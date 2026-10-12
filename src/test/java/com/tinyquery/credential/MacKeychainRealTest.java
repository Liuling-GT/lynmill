package com.tinyquery.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * macOS 钥匙串的真机测试：真的会往当前用户的登录钥匙串里写条目。
 *
 * <p>所有 key 都带随机后缀，且每个测试都在 {@code finally} 里删掉自己写的东西 ——
 * 不能污染用户真实的钥匙串。</p>
 *
 * <p>之所以单独一个类而不是混在 {@link MacCredentialStoreTest} 里：
 * 那一类是纯逻辑（任何平台都跑），这一类只有 macOS 上才有意义。
 * 混在一起会让「跳过了」看起来像「通过了」。</p>
 */
@EnabledOnOs(OS.MAC)
@DisplayName("MacKeychain · macOS 真机（会写真实钥匙串，用完即删）")
class MacKeychainRealTest {

    /** 随机后缀：避免并发跑测试互相干扰，也避免和用户真实数据撞名 */
    private static String key() {
        return "TinyQuery/__test__/" + UUID.randomUUID();
    }

    /** 平台不支持时跳过，而不是判失败 —— 不可用是正确行为，不是缺陷 */
    private static void assumeAvailable(CredentialStore store) {
        org.junit.jupiter.api.Assumptions.assumeTrue(store.isAvailable(),
                "本机钥匙串不可用，跳过：" + store.unavailableReason());
    }

    // ==================== A2 / A3：写入 → 读回一致 ====================

    @Test
    @DisplayName("A2/A3 写入 → 读回一致：中文与特殊字符必须逐字节无损")
    void roundTripPreservesChineseAndSymbols() {
        MacCredentialStore store = new MacCredentialStore();
        assumeAvailable(store);

        String k = key();
        // 这段特意混了中文、希腊字母、符号、空格、换行：
        // Windows 端实测过「中文与特殊字符无损」，macOS 走的是完全不同的 API 和编码
        // （UTF-8 vs UTF-16LE），必须重新验一遍
        String secret = "密码-含中文-Ω-!@#$-空格 和\t制表符\n换行";
        try {
            assertTrue(store.write(k, "root", secret).success());

            CredentialResult r = store.read(k);
            assertTrue(r.success(), r.message());
            assertEquals(secret, r.value(), "中文与特殊字符都不能走样");
        } finally {
            store.delete(k);
        }
    }

    @Test
    @DisplayName("A2 空密码是合法值，能存能读回，不会被当成「不存在」")
    void emptyPasswordRoundTrip() {
        MacCredentialStore store = new MacCredentialStore();
        assumeAvailable(store);

        String k = key();
        try {
            assertTrue(store.write(k, "root", "").success());

            CredentialResult r = store.read(k);
            assertTrue(r.success(), r.message());
            assertFalse(r.missing(), "空密码是「存在且为空」，不是「没有密码」");
            assertEquals("", r.value());
        } finally {
            store.delete(k);
        }
    }

    // ==================== A4：重复写入是覆盖（不弹窗需人工观察） ====================

    @Test
    @DisplayName("A4 连写 3 次是覆盖不报错 —— 且必须全程不触发授权弹窗（人工观察）")
    void repeatedWritesOverwrite() {
        MacCredentialStore store = new MacCredentialStore();
        assumeAvailable(store);

        String k = key();
        try {
            assertTrue(store.write(k, "root", "第1次").success());
            // 这两次是本项的关键：如果实现是「先删后建」，
            //   第 2、3 次会重置条目的 ACL，真实使用中就会弹授权框
            assertTrue(store.write(k, "root", "第2次").success(), "重复保存必须覆盖而不是报错");
            assertTrue(store.write(k, "root", "第3次").success());

            assertEquals("第3次", store.read(k).value());
        } finally {
            store.delete(k);
        }
    }

    @Test
    @DisplayName("A4 写完条目仍在（原地改而不是删了没建回来）")
    void entryStillExistsAfterOverwrite() {
        MacCredentialStore store = new MacCredentialStore();
        assumeAvailable(store);

        String k = key();
        try {
            store.write(k, "root", "a");
            store.write(k, "root", "b");

            assertTrue(MacKeychain.exists(k), "重复写入后条目必须还在");
            assertEquals("b", MacKeychain.read(k).password());
        } finally {
            store.delete(k);
        }
    }

    // ==================== A5：读不存在 ====================

    @Test
    @DisplayName("A5 读不存在的条目 → notFound，不报错")
    void missingKeyIsNotAnError() {
        MacCredentialStore store = new MacCredentialStore();
        assumeAvailable(store);

        CredentialResult r = store.read(key());

        assertTrue(r.success(), "「没存过」是正常状态，不是故障：" + r.message());
        assertTrue(r.missing());
        assertNull(r.value());
    }

    @Test
    @DisplayName("A5 底层也把不存在的条目翻译成 missing 而不是 failed")
    void lowLevelMissingIsMissing() {
        MacKeychain.ReadOutcome got = MacKeychain.read(key());

        assertTrue(got.error().isEmpty(), "不该有错误信息：" + got.error());
        assertFalse(got.found(), "不该报告找到了");
    }

    // ==================== A6：删除 ====================

    @Test
    @DisplayName("A6 删除成功；删除不存在的也算成功")
    void deleteSemantics() {
        MacCredentialStore store = new MacCredentialStore();
        assumeAvailable(store);

        String k = key();
        store.write(k, "root", "p");

        assertTrue(store.delete(k).success());
        assertTrue(store.read(k).missing(), "删除后应当读不到");

        assertTrue(store.delete(k).success(),
                "删除的目标是「让它没有密码」，本来就没有 = 已达成");
    }

    // ==================== 语义细节 ====================

    @Test
    @DisplayName("条目只按 service 查找：用户名变了也还能读到、还能删掉")
    void lookupIsByServiceOnly() {
        MacCredentialStore store = new MacCredentialStore();
        assumeAvailable(store);

        String k = key();
        try {
            store.write(k, "userA", "pwd1");
            // 用户名换成另一个，应当原地改而不是新建一条
            store.write(k, "userB", "pwd2");

            assertEquals("pwd2", store.read(k).value(),
                    "换用户名后必须还是同一条，否则会出现两条同 service 的记录");

            assertTrue(store.delete(k).success(), "换用户名后仍要删得掉");
            assertTrue(store.read(k).missing());
        } finally {
            store.delete(k);
        }
    }

    @Test
    @DisplayName("A9 可用性判断不访问钥匙串：只加载框架")
    void availabilityDoesNotTouchKeychain() {
        MacCredentialStore store = new MacCredentialStore();

        // 真机上这一条应当为 true（框架一定加载得起来）
        assertTrue(store.isAvailable(), store.unavailableReason());
        assertEquals("", store.unavailableReason());
    }

    @Test
    @DisplayName("A12 更新用的函数确实存在且可用（ModifyAttributesAndData）")
    void modifyFunctionWorks() {
        String k = key();
        try {
            assertNull(MacKeychain.add(k, "u", "旧"), "add 应当成功");
            assertNull(MacKeychain.modify(k, "新"), "原地改应当成功");
            assertEquals("新", MacKeychain.read(k).password());
        } finally {
            MacKeychain.delete(k);
        }
    }

    @Test
    @DisplayName("超长密码（接上 Windows 的 1280 字符上限问题）—— 记录 macOS 是否也有上限")
    void longPasswordBehaviourIsRecorded() {
        MacCredentialStore store = new MacCredentialStore();
        assumeAvailable(store);

        String k = key();
        try {
            // Windows 的实测上限是 1280 个字符（UTF-16LE 2560 字节）。
            // macOS 钥匙串理论上没这么严，这里探一下到底行不行 ——
            // 所以下面不是断言「一定行」，而是「能存就必须能原样读回」
            String longPwd = "密".repeat(2000);
            CredentialResult w = store.write(k, "u", longPwd);
            if (w.success()) {
                assertEquals(longPwd, store.read(k).value(), "2000 个汉字应当能原样读回");
            }
            // 失败也不判失败：这只说明 macOS 也有上限，是需要记录的事实
        } finally {
            store.delete(k);
        }
    }
}
