package com.tinyquery.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LinuxCredentialStore} 的逻辑测试。
 *
 * <p><b>全部用假的原生层驱动，因此不需要 Linux、更不需要 D-Bus 会话</b> ——
 * 在 Windows 上复核、在将来的 CI 里都能跑。这正是当初把原生调用抽成
 * {@link LinuxKeychainBackend} 接缝的目的。</p>
 */
@DisplayName("LinuxCredentialStore 凭据存储逻辑")
class LinuxCredentialStoreTest {

    private static final String KEY = "TinyQuery/demo";

    // ==================== 假原生层 ====================

    /** 记录调用、按需返回预设结果的假实现 */
    private static final class FakeBackend implements LinuxKeychainBackend {

        boolean loadable = true;
        Throwable loadableThrows;
        SecretService.ReadOutcome readResult = SecretService.ReadOutcome.missing();
        String storeError;
        String deleteError;

        final List<String> calls = new ArrayList<>();
        String lastStoreKey;
        String lastStoreLabel;
        String lastStorePassword;
        String lastDeleteKey;

        @Override
        public boolean loadable() {
            calls.add("loadable");
            if (loadableThrows != null) {
                throw sneaky(loadableThrows);
            }
            return loadable;
        }

        @Override
        public SecretService.ReadOutcome read(String key) {
            calls.add("read:" + key);
            return readResult;
        }

        @Override
        public String store(String key, String label, String password) {
            calls.add("store:" + key);
            lastStoreKey = key;
            lastStoreLabel = label;
            lastStorePassword = password;
            return storeError;
        }

        @Override
        public String delete(String key) {
            calls.add("delete:" + key);
            lastDeleteKey = key;
            return deleteError;
        }

        /** 让测试能抛 Error（模拟 JNA 的 UnsatisfiedLinkError）而不被编译器拦下 */
        private static RuntimeException sneaky(Throwable t) {
            if (t instanceof RuntimeException re) {
                return re;
            }
            if (t instanceof Error e) {
                throw e;
            }
            return new RuntimeException(t);
        }
    }

    private static LinuxCredentialStore available(FakeBackend backend) {
        return new LinuxCredentialStore(backend, true);
    }

    // ==================== 写入 ====================

    @Test
    @DisplayName("write 把 key、标签与密码交给原生层，成功后返回成功")
    void writePassesThrough() {
        FakeBackend backend = new FakeBackend();
        CredentialResult r = available(backend).write(KEY, "root", "s3cret");

        assertTrue(r.success(), r.message());
        assertEquals(KEY, backend.lastStoreKey);
        assertEquals("s3cret", backend.lastStorePassword);
        assertTrue(backend.calls.contains("store:" + KEY));
    }

    @Test
    @DisplayName("标签里带上用户名（libsecret 没有独立的用户名字段，见类注释）")
    void labelCarriesUsername() {
        FakeBackend backend = new FakeBackend();
        available(backend).write(KEY, "root", "p");
        assertEquals(KEY + "（root）", backend.lastStoreLabel);
    }

    @Test
    @DisplayName("用户名为空时标签就是 key 本身，不会多出一对空括号")
    void labelWithoutUsername() {
        FakeBackend a = new FakeBackend();
        available(a).write(KEY, null, "p");
        assertEquals(KEY, a.lastStoreLabel);

        FakeBackend b = new FakeBackend();
        available(b).write(KEY, "   ", "p");
        assertEquals(KEY, b.lastStoreLabel);
    }

    @Test
    @DisplayName("重复保存走同一个 store 调用 —— 它是 upsert，不需要先查后写")
    void writeIsUpsertNotReadModifyWrite() {
        FakeBackend backend = new FakeBackend();
        LinuxCredentialStore store = available(backend);

        store.write(KEY, "root", "first");
        store.write(KEY, "root", "second");

        assertEquals(2, backend.calls.stream().filter(c -> c.startsWith("store:")).count());
        assertTrue(backend.calls.stream().noneMatch(c -> c.startsWith("read:")),
                "不该为了决定「新建还是更新」而先读一次");
        assertTrue(backend.calls.stream().noneMatch(c -> c.startsWith("delete:")),
                "更不该先删后建 —— 那会在 store 失败时把用户密码弄丢");
    }

    @Test
    @DisplayName("原生层报错时 write 返回失败并带上原因")
    void writeSurfacesBackendError() {
        FakeBackend backend = new FakeBackend();
        backend.storeError = "D-Bus 连不上";

        CredentialResult r = available(backend).write(KEY, "root", "p");
        assertFalse(r.success());
        assertEquals("D-Bus 连不上", r.message());
    }

    @Test
    @DisplayName("空 key 直接拒绝，不碰原生层")
    void blankKeyRejectedOnWrite() {
        FakeBackend backend = new FakeBackend();
        CredentialResult r = available(backend).write("  ", "root", "p");

        assertFalse(r.success());
        assertTrue(backend.calls.isEmpty(), "不该调用原生层");
    }

    // ==================== 读取 ====================

    @Test
    @DisplayName("读到密码返回 ok")
    void readFound() {
        FakeBackend backend = new FakeBackend();
        backend.readResult = SecretService.ReadOutcome.found("p@ss");

        CredentialResult r = available(backend).read(KEY);
        assertTrue(r.success());
        assertEquals("p@ss", r.value());
        assertFalse(r.missing());
    }

    @Test
    @DisplayName("凭据不存在返回「成功但没有值」，而不是失败")
    void readMissingIsNotAFailure() {
        FakeBackend backend = new FakeBackend();
        backend.readResult = SecretService.ReadOutcome.missing();

        CredentialResult r = available(backend).read(KEY);
        assertTrue(r.success(), "「用户没存过」是常态，不是错误");
        assertNull(r.value());
        assertTrue(r.missing());
    }

    @Test
    @DisplayName("原生层出错时返回失败，并带上原因（上层据此让用户手输密码）")
    void readSurfacesBackendError() {
        FakeBackend backend = new FakeBackend();
        backend.readResult = SecretService.ReadOutcome.failed("钥匙串被锁");

        CredentialResult r = available(backend).read(KEY);
        assertFalse(r.success());
        assertEquals("钥匙串被锁", r.message());
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("删除成功")
    void deleteOk() {
        FakeBackend backend = new FakeBackend();
        CredentialResult r = available(backend).delete(KEY);

        assertTrue(r.success());
        assertEquals(KEY, backend.lastDeleteKey);
    }

    @Test
    @DisplayName("删除失败时返回失败")
    void deleteSurfacesBackendError() {
        FakeBackend backend = new FakeBackend();
        backend.deleteError = "权限不足";

        CredentialResult r = available(backend).delete(KEY);
        assertFalse(r.success());
        assertEquals("权限不足", r.message());
    }

    // ==================== 平台不匹配时的退化 ====================

    @Test
    @DisplayName("平台不匹配：不可用、写失败、读当作没有、删算成功")
    void degradesGracefullyOnOtherPlatforms() {
        FakeBackend backend = new FakeBackend();
        LinuxCredentialStore store = new LinuxCredentialStore(backend, false);

        assertFalse(store.isAvailable());
        assertFalse(store.unavailableReason().isBlank(), "要给出人话原因");

        assertFalse(store.write(KEY, "root", "p").success());

        CredentialResult read = store.read(KEY);
        assertTrue(read.success(), "读应当退化成「没有密码」，让上层走手输分支");
        assertTrue(read.missing());

        assertTrue(store.delete(KEY).success(), "没存过，也谈不上删失败");

        assertTrue(backend.calls.isEmpty(), "平台不匹配时不该调用原生层");
    }

    // ==================== 原生库加载失败 ====================

    @Test
    @DisplayName("原生层返回不可用：禁掉保存密码，但程序不崩")
    void unavailableWhenBackendReportsNotLoadable() {
        FakeBackend backend = new FakeBackend();
        backend.loadable = false;

        LinuxCredentialStore store = available(backend);
        assertFalse(store.isAvailable());
        assertFalse(store.unavailableReason().isBlank());
        assertTrue(store.read(KEY).missing());
    }

    @Test
    @DisplayName("原生库加载抛 Error（JNA 的 UnsatisfiedLinkError）时也要被兜住")
    void loadFailureIsCaughtIncludingErrors() {
        FakeBackend backend = new FakeBackend();
        backend.loadableThrows = new UnsatisfiedLinkError("libsecret-1.so.0 not found");

        LinuxCredentialStore store = available(backend);

        // 关键：不能把 Error 抛出去 —— 凭据存不了绝不能让整个应用起不来。
        // 下面几行只要有任意一个把 Error 漏出来，测试就会直接失败
        assertFalse(store.isAvailable());
        assertFalse(store.unavailableReason().isBlank());
        assertTrue(store.read(KEY).missing());
        assertFalse(store.write(KEY, "u", "p").success());
        assertTrue(store.delete(KEY).success());
    }

    @Test
    @DisplayName("可用性只判断一次，之后走缓存（避免每次请求都碰原生层）")
    void availabilityIsCached() {
        FakeBackend backend = new FakeBackend();
        LinuxCredentialStore store = available(backend);

        store.isAvailable();
        store.isAvailable();
        store.isAvailable();

        assertEquals(1, backend.calls.stream().filter("loadable"::equals).count());
    }

    // ==================== ★ 回归：原生库必须懒加载 ====================

    @Test
    @DisplayName("★ 生产构造函数在非 Linux 上也不能抛 —— 原生库是懒加载的")
    void productionConstructorNeverThrowsOnOtherPlatforms() {
        // 这个测试盯住一个真实踩过的坑：SecretService 早期把「加载原生库」
        // 写在静态初始化块里，于是只要碰一下那个类就会 Native.load("secret-1")。
        // 而配置类在用户显式传 tinyquery.credential.store=linux 时会构造本类
        // —— 哪怕当前是 Windows。那次 UnsatisfiedLinkError 让构造失败、
        // 被配置类的兜底 catch 成了 NoOpCredentialStore，于是「显式指定 linux」
        // 在 Windows 上悄悄退化成「不支持保存密码」，而 Linux 上完全正常。
        //
        // 所以这里断言的重点不是「类型对不对」，而是「构造不能抛」——
        // 抛了会被上层吞掉，症状是功能无声消失
        LinuxCredentialStore prod = new LinuxCredentialStore();

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            assertFalse(prod.isAvailable(), "非 Linux 上应当报告不可用");
            assertFalse(prod.unavailableReason().isBlank(), "要给出人话原因");
            // 而且不可用时三个操作都要能安全调用
            assertTrue(prod.read(KEY).missing());
            assertFalse(prod.write(KEY, "u", "p").success());
            assertTrue(prod.delete(KEY).success());
        }
    }
}
