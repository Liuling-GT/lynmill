package com.tinyquery.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * macOS 实现的逻辑测试。
 *
 * <p>本类全部用假的原生层，一行原生调用都不发生，因此在 Windows / Linux 上
 * 也会照常运行 —— 这正是 {@link MacKeychainBackend} 这层接缝的目的。
 * 尤其是「更新已有条目必须原地改」那条：它一旦破功，症状是「用户点了『始终允许』
 * 还是每次都弹窗」，在没有 Mac 的 CI 上根本发现不了。</p>
 *
 * <p>真机读写请看 {@link MacKeychainRealTest}。</p>
 */
@DisplayName("MacCredentialStore · 逻辑（假原生层，任何平台都能跑）")
class MacCredentialStoreTest {

    /**
     * 假的原生层：记录「按什么顺序调了哪些动作」，并维护一张内存表。
     *
     * <p>记的是动作序列而不是最终状态 —— 因为坑 ① 的本质就是动作序列错了：
     * 先删后建和原地改的最终状态一模一样，只有调用序列能把它们区分开。</p>
     */
    static class FakeBackend implements MacKeychainBackend {

        final List<String> calls = new ArrayList<>();
        final Map<String, String> passwords = new LinkedHashMap<>();

        boolean loadable = true;
        boolean loadableThrows = false;
        String modifyError = null;
        String addError = null;
        String readError = null;
        String lastAccount;

        @Override
        public boolean loadable() {
            if (loadableThrows) {
                // 模拟 JNA 加载原生库失败：抛的是 Error 而不是 Exception
                throw new UnsatisfiedLinkError("模拟：找不到 Security 框架");
            }
            calls.add("loadable");
            return loadable;
        }

        @Override
        public MacKeychain.ReadOutcome read(String service) {
            calls.add("read");
            if (readError != null) {
                return MacKeychain.ReadOutcome.failed(readError);
            }
            String v = passwords.get(service);
            return v == null ? MacKeychain.ReadOutcome.missing() : MacKeychain.ReadOutcome.of(v);
        }

        @Override
        public boolean exists(String service) {
            calls.add("exists");
            return passwords.containsKey(service);
        }

        @Override
        public String modify(String service, String password) {
            calls.add("modify");
            if (modifyError != null) {
                return modifyError;
            }
            passwords.put(service, password);
            return null;
        }

        @Override
        public String add(String service, String account, String password) {
            calls.add("add");
            lastAccount = account;
            if (addError != null) {
                return addError;
            }
            passwords.put(service, password);
            return null;
        }

        @Override
        public String delete(String service) {
            calls.add("delete");
            passwords.remove(service);
            return null;
        }

        int count(String action) {
            return (int) calls.stream().filter(action::equals).count();
        }
    }

    private static final String KEY = "LynMill/e2etest";

    private static MacCredentialStore store(FakeBackend backend) {
        // platformMatches = true：让这些纯逻辑测试在任何平台上都真的跑起来
        return new MacCredentialStore(backend, true);
    }

    // ==================== 坑 ①：必须原地改 ====================

    @Test
    @DisplayName("★ 更新已有条目必须走原地修改，绝不能先删后建")
    void updateMustModifyInPlaceNeverDelete() {
        FakeBackend native_ = new FakeBackend();
        native_.passwords.put(KEY, "旧密码");
        MacCredentialStore store = store(native_);

        CredentialResult r = store.write(KEY, "root", "新密码");

        assertTrue(r.success(), r.message());
        assertEquals("新密码", native_.passwords.get(KEY));

        // 这两条断言是本次 macOS 工作的核心：
        // delete 一旦出现，钥匙串条目的 ACL 分区列表就被重置，
        // 用户点过的「始终允许」立刻失效 —— 表现就是「点了也没用，下次还弹」
        assertEquals(0, native_.count("delete"), "绝不能删除条目重建（会重置 ACL）：" + native_.calls);
        assertEquals(1, native_.count("modify"), "应当原地改： " + native_.calls);
        assertEquals(0, native_.count("add"), "已有条目不该再新建： " + native_.calls);
    }

    @Test
    @DisplayName("★ 连写 3 次：只新建一次，之后都是原地改，全程没有 delete")
    void repeatedWritesNeverDelete() {
        FakeBackend native_ = new FakeBackend();
        MacCredentialStore store = store(native_);

        assertTrue(store.write(KEY, "root", "第1次").success());
        assertTrue(store.write(KEY, "root", "第2次").success());
        assertTrue(store.write(KEY, "root", "第3次").success());

        assertEquals(0, native_.count("delete"), "重复保存绝不能删旧条目：" + native_.calls);
        assertEquals(1, native_.count("add"), "只该在第一次新建：" + native_.calls);
        assertEquals(2, native_.count("modify"), "后两次都该是原地改：" + native_.calls);
        assertEquals("第3次", native_.passwords.get(KEY), "最终值应当是最后一次写入的");
    }

    @Test
    @DisplayName("新条目走新建，且不经过删除")
    void newEntryGoesToAdd() {
        FakeBackend native_ = new FakeBackend();
        MacCredentialStore store = store(native_);

        assertTrue(store.write(KEY, "root", "pwd").success());

        assertEquals(1, native_.count("add"));
        assertEquals(0, native_.count("delete"));
        assertEquals(0, native_.count("modify"));
        assertEquals("root", native_.lastAccount, "用户名应当一并写入");
    }

    @Test
    @DisplayName("用户名只在新条目时写入 —— 改密码不会因为它变了而重建条目")
    void accountIsNotPartOfLookup() {
        FakeBackend native_ = new FakeBackend();
        native_.passwords.put(KEY, "旧");
        MacCredentialStore store = store(native_);

        // 用户把数据库用户名也改了，此时仍必须是原地改而不是新建
        store.write(KEY, "另一个用户名", "新");

        assertEquals(0, native_.count("add"),
                "用户名不是查找键，改了它也不能新建条目（否则会出现两条同 service 的记录）："
                        + native_.calls);
        assertEquals(1, native_.count("modify"));
    }

    // ==================== 契约：不抛异常、notFound ≠ 失败 ====================

    @Test
    @DisplayName("读不存在的 key 返回「没有密码」而不是失败 —— 这是最常见的情形")
    void readMissingIsNotAnError() {
        CredentialResult r = store(new FakeBackend()).read(KEY);

        assertTrue(r.success(), "「没存过」是正常状态，不是故障：" + r.message());
        assertTrue(r.missing());
        assertNull(r.value());
    }

    @Test
    @DisplayName("写入 → 读回一致")
    void roundTrip() {
        MacCredentialStore store = store(new FakeBackend());

        assertTrue(store.write(KEY, "root", "密码-含中文-Ω-!@#").success());
        CredentialResult r = store.read(KEY);

        assertTrue(r.success(), r.message());
        assertEquals("密码-含中文-Ω-!@#", r.value());
    }

    @Test
    @DisplayName("删除不存在的 key 算成功（目的已达成）")
    void deleteMissingSucceeds() {
        assertTrue(store(new FakeBackend()).delete(KEY).success());
    }

    @Test
    @DisplayName("删除后读不到")
    void deleteThenRead() {
        MacCredentialStore store = store(new FakeBackend());
        store.write(KEY, "root", "p");

        assertTrue(store.delete(KEY).success());
        assertTrue(store.read(KEY).missing());
    }

    @Test
    @DisplayName("key 为空时明确失败，不崩")
    void blankKeyFails() {
        MacCredentialStore store = store(new FakeBackend());

        assertFalse(store.write(null, "u", "p").success());
        assertFalse(store.read("  ").success());
        assertFalse(store.delete("").success());
    }

    @Test
    @DisplayName("底层报错时翻译成结果返回，不抛异常")
    void nativeErrorsBecomeResults() {
        FakeBackend native_ = new FakeBackend();
        native_.passwords.put(KEY, "旧");
        native_.modifyError = "钥匙串拒绝了本次访问（密码或权限不足）";
        MacCredentialStore store = store(native_);

        CredentialResult w = store.write(KEY, "u", "新");
        assertFalse(w.success());
        assertTrue(w.message().contains("拒绝"), w.message());

        native_.readError = "钥匙串操作失败（错误码 -25308）";
        CredentialResult r = store.read(KEY);
        assertFalse(r.success(), "读失败应当是失败，但由上层走 NEEDS_PASSWORD 兜底");
        assertTrue(r.message().contains("-25308"), r.message());
    }

    // ==================== 不可用时的优雅退化 ====================

    @Test
    @DisplayName("平台不匹配：不可用，但三个操作都不抛异常")
    void wrongPlatformDegradesGracefully() {
        MacCredentialStore store = new MacCredentialStore(new FakeBackend(), false);

        assertFalse(store.isAvailable());
        assertFalse(store.unavailableReason().isEmpty(), "必须给出人话原因，界面要显示它");

        CredentialResult w = store.write(KEY, "u", "p");
        assertFalse(w.success(), "存不了必须说存不了，不能假装成功");

        CredentialResult r = store.read(KEY);
        assertTrue(r.success(), "读应当报「没有密码」，让上层走「请用户输入密码」");
        assertTrue(r.missing());

        assertTrue(store.delete(KEY).success(), "没存过，删除算成功");
    }

    @Test
    @DisplayName("原生层加载失败（Error 而非 Exception）也不会让程序起不来")
    void nativeLoadFailureIsContained() {
        FakeBackend native_ = new FakeBackend();
        native_.loadableThrows = true;
        MacCredentialStore store = store(native_);

        assertFalse(store.isAvailable(), "加载失败应当判为不可用");
        assertTrue(store.unavailableReason().contains("UnsatisfiedLinkError"),
                store.unavailableReason());
        assertDoesNotThrow(() -> store.write(KEY, "u", "p"));
    }

    @Test
    @DisplayName("可用性判断不得访问钥匙串 —— 否则 macOS 上每次启动都会弹授权框")
    void availabilityCheckMustNotTouchKeychain() {
        FakeBackend native_ = new FakeBackend();
        MacCredentialStore store = store(native_);

        assertTrue(store.isAvailable());
        assertEquals(List.of("loadable"), native_.calls,
                "判断可用性只应触发原生库加载检查，不能有 read/exists/modify/add/delete：" + native_.calls);
    }

    // ==================== 错误码翻译 ====================

    @Test
    @DisplayName("OSStatus 翻译成人话 —— 不能把 -25300 直接甩给用户")
    void errorCodesAreTranslated() {
        assertTrue(MacKeychain.describe(MacKeychain.ERR_SEC_ITEM_NOT_FOUND).contains("没有"));
        assertTrue(MacKeychain.describe(MacKeychain.ERR_SEC_USER_CANCELED).contains("取消"));
        assertTrue(MacKeychain.describe(MacKeychain.ERR_SEC_INTERACTION_NOT_ALLOWED)
                .contains("图形界面"), "无图形会话这条要说明白");
        assertTrue(MacKeychain.describe(99999).contains("99999"),
                "不认识的错误码也要带上原始码便于排查");
    }

    @Test
    @DisplayName("实测确认的错误码常量：条目不存在 = -25300")
    void errorCodeMatchesMeasurement() {
        assertEquals(-25300, MacKeychain.ERR_SEC_ITEM_NOT_FOUND,
                "2026-10-03 在 macOS 14.4 上实测确认");
    }
}
