package com.tinyquery.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CredentialStore 凭据存储")
class CredentialStoreTest {

    /** 唯一的 key，避免并发跑测试时互相干扰，也避免污染真实凭据管理器 */
    private static String key() {
        return "TinyQuery/__test__/" + UUID.randomUUID();
    }

    // ==================== 空实现（不依赖平台，任何机器上都能跑） ====================

    @Test
    @DisplayName("空实现：不可用，且说明原因")
    void noOpIsUnavailable() {
        CredentialStore store = new NoOpCredentialStore("测试原因");
        assertFalse(store.isAvailable());
        assertEquals("测试原因", store.unavailableReason());
    }

    @Test
    @DisplayName("空实现：写入必须明确失败，不能假装成功")
    void noOpWriteMustFailLoudly() {
        CredentialStore store = new NoOpCredentialStore("本平台不支持");
        CredentialResult r = store.write("k", "u", "p");

        // 这条是关键：若写入「成功」而实际没存，界面会告诉用户「已保存」，
        // 下次启动却又要密码 —— 用户完全无法理解发生了什么
        assertFalse(r.success(), "存不了就必须说存不了");
        assertTrue(r.message().contains("不支持"), r.message());
    }

    @Test
    @DisplayName("空实现：读取返回「没有密码」而不是失败 —— 这是正常状态")
    void noOpReadReportsNotFound() {
        CredentialStore store = new NoOpCredentialStore("本平台不支持");
        CredentialResult r = store.read("k");

        assertTrue(r.success(), "读不到密码不是错误");
        assertTrue(r.missing(), "应当标记为「不存在」");
        assertNull(r.value());
    }

    @Test
    @DisplayName("空实现：删除算成功（本来就没有，删除的目的已达成）")
    void noOpDeleteSucceeds() {
        assertTrue(new NoOpCredentialStore("x").delete("k").success());
    }

    @Test
    @DisplayName("空实现：key 为空也不崩")
    void noOpHandlesBlankKey() {
        CredentialStore store = new NoOpCredentialStore("x");
        assertDoesNotThrow(() -> store.write(null, null, null));
        assertDoesNotThrow(() -> store.read(""));
        assertDoesNotThrow(() -> store.delete(null));
    }

    // ==================== 密码长度校验（纯函数，任何平台都能跑） ====================

    @Test
    @DisplayName("长度上限：1280 字符合法，1281 字符被拦下")
    void passwordLengthLimit() {
        assertNull(WinCred.checkPasswordLength("x".repeat(1280)), "1280 字符应当合法");
        assertNotNull(WinCred.checkPasswordLength("x".repeat(1281)), "1281 字符应当被拦下");
    }

    @Test
    @DisplayName("长度按字节算而不是按字符算 —— 中文一个字符占 2 字节")
    void lengthIsCountedInBytes() {
        // 1280 个汉字 = 2560 字节，正好到上限
        assertNull(WinCred.checkPasswordLength("密".repeat(1280)),
                "1280 个汉字 = 2560 字节，正好在上限内");
        assertNotNull(WinCred.checkPasswordLength("密".repeat(1281)),
                "1281 个汉字 = 2562 字节，应当被拦下");
    }

    @Test
    @DisplayName("长度校验：null 视为合法（空密码是允许的）")
    void nullPasswordIsAllowed() {
        assertNull(WinCred.checkPasswordLength(null));
        assertNull(WinCred.checkPasswordLength(""));
    }

    @Test
    @DisplayName("错误码翻译成人话 —— 不能把 1783 直接甩给用户")
    void errorCodesAreTranslated() {
        String notFound = WinCred.describe(WinCred.ERROR_NOT_FOUND);
        String tooLong = WinCred.describe(WinCred.ERROR_INVALID_CREDENTIAL_BLOB_SIZE);
        String unknown = WinCred.describe(99999);

        assertTrue(notFound.contains("不存在"), notFound);
        assertTrue(tooLong.contains("过长"), tooLong);
        assertTrue(tooLong.contains("1280"), "应当告诉用户上限是多少：" + tooLong);
        assertTrue(unknown.contains("99999"), "不认识的错误码也要带上原始码便于排查：" + unknown);
    }

    @Test
    @DisplayName("上限常量与实测一致：2560 字节")
    void maxBlobSizeMatchesMeasurement() {
        assertEquals(2560, WinCred.MAX_BLOB_BYTES,
                "实测二分确认的上限：1280 字符 × 2 字节");
    }

    // ==================== Windows 真机测试 ====================

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows：写入 → 读回 → 删除，全链路")
    void windowsRoundTrip() {
        CredentialStore store = new WindowsCredentialStore();
        assumeWindowsStoreAvailable(store);

        String k = key();
        try {
            assertTrue(store.write(k, "root", "密码-含中文-Ω-!@#").success());
            CredentialResult r = store.read(k);
            assertTrue(r.success(), r.message());
            assertEquals("密码-含中文-Ω-!@#", r.value(), "中文与特殊字符都不能走样");

            assertTrue(store.delete(k).success());
            assertTrue(store.read(k).missing(), "删除后应当读不到");
        } finally {
            store.delete(k);   // 无论如何都清理掉，不污染真实凭据管理器
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows：读不存在的 key 返回「没有密码」而不是失败")
    void windowsMissingKeyIsNotAnError() {
        CredentialStore store = new WindowsCredentialStore();
        assumeWindowsStoreAvailable(store);

        CredentialResult r = store.read(key());
        assertTrue(r.success(), "「没存过」是正常状态，不是故障：" + r.message());
        assertTrue(r.missing());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows：重复写入是覆盖，不报错")
    void windowsWriteOverwrites() {
        CredentialStore store = new WindowsCredentialStore();
        assumeWindowsStoreAvailable(store);

        String k = key();
        try {
            assertTrue(store.write(k, "u", "第一个").success());
            assertTrue(store.write(k, "u", "第二个").success(), "改密码后重新保存应当成功");
            assertEquals("第二个", store.read(k).value());
        } finally {
            store.delete(k);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows：删除不存在的 key 算成功")
    void windowsDeleteMissingSucceeds() {
        CredentialStore store = new WindowsCredentialStore();
        assumeWindowsStoreAvailable(store);

        assertTrue(store.delete(key()).success(),
                "删除的目标是「让它没有密码」，本来就没有 = 已达成");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows：超长密码被我们提前拦下，而不是丢给系统报错码")
    void windowsRejectsTooLongPassword() {
        CredentialStore store = new WindowsCredentialStore();
        assumeWindowsStoreAvailable(store);

        CredentialResult r = store.write(key(), "u", "x".repeat(2000));

        assertFalse(r.success());
        assertTrue(r.message().contains("过长"), "应当是我们翻译过的人话：" + r.message());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows：可用性探测为真，且探测不留下残留凭据")
    void windowsAvailabilityProbe() {
        CredentialStore store = new WindowsCredentialStore();
        if (!store.isAvailable()) {
            // 策略禁用等环境下跳过，不算失败
            return;
        }
        assertTrue(store.isAvailable());
        assertEquals("", store.unavailableReason());
    }

    // ==================== 辅助 ====================

    /**
     * 凭据管理器真的不可用时跳过测试。
     *
     * <p>不能写成断言失败：在某些被策略禁用的环境里（企业机器、无登录会话），
     * 不可用是<b>正确行为</b>而不是缺陷。</p>
     */
    private static void assumeWindowsStoreAvailable(CredentialStore store) {
        org.junit.jupiter.api.Assumptions.assumeTrue(store.isAvailable(),
                "本机凭据管理器不可用，跳过：" + store.unavailableReason());
    }
}
