package com.tinyquery.credential;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Function;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Linux 的 Secret Service（libsecret）原生层。
 *
 * <h2>为什么是 libsecret 而不是别的方案</h2>
 *
 * <p>Linux 没有「系统级凭据管理器」这个东西。实际提供这个能力的是 <b>Secret Service
 * 协议</b>（由 GNOME Keyring 或 KWallet 实现），走 D-Bus 通信。要调它有三条路：</p>
 *
 * <ol>
 *   <li><b>{@code dbus-java}</b> —— 直接说 D-Bus 协议。被否决：它是 LGPL-2 的额外 Java
 *       依赖，而且一旦全局引入就会被打进 Windows / macOS 的包，那两个平台根本用不到。</li>
 *   <li><b>调 {@code secret-tool} 命令行</b> —— 简单，但那个工具属于 {@code libsecret-tools}
 *       包，桌面发行版默认<b>不装</b>；而 {@code libsecret-1-0}（库）几乎必然存在。</li>
 *   <li><b>用 JNA 调 {@code libsecret-1.so.0}</b> —— <b>本实现选的这条</b>。
 *       JNA 已是本项目的既有依赖（{@link WinCred}、{@link MacKeychain} 都用它），
 *       <b>零新增依赖</b>，且不必依赖某个 CLI 工具是否安装。</li>
 * </ol>
 *
 * <h2>可变参数问题（这是选路线的关键障碍）</h2>
 *
 * <p>libsecret 的主 API 是 C 可变参数函数，例如：</p>
 *
 * <pre>
 * gboolean secret_password_store_sync (const SecretSchema *schema, const gchar *collection,
 *                                      const gchar *label, const gchar *password,
 *                                      GCancellable *cancellable, GError **error, ...);
 * </pre>
 *
 * <p>JNA <b>调不了</b>这类函数。所幸 libsecret 同时导出了<b>固定参数</b>的
 * {@code *v_sync} 版本，属性通过 {@code GHashTable} 传入 —— 那就能用 JNA 调了。
 * 实测确认这些符号都存在：</p>
 *
 * <pre>
 * secret_password_storev_sync   (schema, attributes, collection, label, password, cancellable, error)
 * secret_password_lookupv_sync  (schema, attributes, cancellable, error) -> gchar*
 * secret_password_clearv_sync   (schema, attributes, cancellable, error)
 * </pre>
 *
 * <h2>SecretSchema 的内存布局（手写偏移，实测校准过）</h2>
 *
 * <p>JNA 的 {@code Structure} 对「32 个内联结构体数组」这种布局支持得很别扭，
 * 所以这里直接用 {@link Memory} 按偏移写。偏移不是猜的 —— 是用 C 程序
 * {@code sizeof} / {@code offsetof} 在真实 ABI 上量出来的：</p>
 *
 * <pre>
 * SecretSchemaAttribute:   sizeof=16   name@0   type@8
 * SecretSchema:            sizeof=592  name@0   flags@8   attributes@16
 *                                      attributes[1]@32（跨距 16）  sizeof(attributes)=512（32 槽）
 * </pre>
 *
 * <p>{@link SecretServiceTest} 里有一组断言把这些数字钉住；若将来换了平台/ABI
 * 导致布局不同，那组测试会先失败，而不是等到运行时莫名其妙地崩。</p>
 *
 * <h2>与 macOS 实现的关键差异</h2>
 *
 * <p>{@code secret_password_storev_sync} 本身是<b>按 schema + 属性匹配的 upsert</b>：
 * 匹配到现有条目就原地更新，匹配不到才新建。所以 Linux 侧<b>不需要</b>
 * macOS 那种「先查 exists 再决定 modify 还是 add」的编排，也不存在
 * 「先删后建会重置 ACL」的坑。</p>
 *
 * <h2>已知限制：没有钥匙串时怎么办</h2>
 *
 * <p>服务器版、精简发行版、纯窗口管理器环境往往没装 keyring，甚至装了但守护进程
 * 没起。这时 {@code *v_sync} 会返回 {@code GError}（连不上 D-Bus），
 * 本类把它翻译成普通错误返回，{@link LinuxCredentialStore} 再翻译成
 * 「读不到密码」，上层照既有的 {@code NEEDS_PASSWORD} 路径让用户手输 ——
 * <b>功能不中断，这是 Linux 上的常态路径而不是异常路径</b>。</p>
 */
final class SecretService implements LinuxKeychainBackend {

    private static final Logger log = LoggerFactory.getLogger(SecretService.class);

    /** 本应用在钥匙串里的 schema 名。与 jpackage 的应用标识保持一致 */
    static final String SCHEMA_NAME = "com.liuling.tinyquery";

    /** 用于查找的属性名。只用一个属性，见 {@link LinuxKeychainBackend} 的说明 */
    static final String ATTRIBUTE_KEY = "key";

    // ==================== 内存布局常量（由 C 程序实测校准，勿凭直觉改）====================

    /** {@code sizeof(SecretSchema)} */
    static final int SCHEMA_SIZE = 592;
    static final int SCHEMA_OFF_NAME = 0;
    static final int SCHEMA_OFF_FLAGS = 8;
    static final int SCHEMA_OFF_ATTRIBUTES = 16;
    /** 单个属性的跨距（{@code sizeof(SecretSchemaAttribute)}） */
    static final int ATTRIBUTE_STRIDE = 16;
    /** 属性槽位数量（{@code attributes[32]}） */
    static final int ATTRIBUTE_SLOTS = 32;
    static final int ATTRIBUTE_OFF_NAME = 0;
    static final int ATTRIBUTE_OFF_TYPE = 8;

    /** {@code SECRET_SCHEMA_NONE} */
    static final int SCHEMA_FLAGS_NONE = 0;
    /** {@code SECRET_SCHEMA_ATTRIBUTE_STRING} */
    static final int ATTRIBUTE_TYPE_STRING = 0;

    /** {@code GError} 里 message 字段的偏移（{@code GQuark domain; gint code; gchar *message;}） */
    private static final int GERROR_OFF_MESSAGE = 8;

    // ==================== JNA 绑定 ====================

    private interface LibSecret extends Library {
        LibSecret INSTANCE = Native.load("secret-1", LibSecret.class);

        boolean secret_password_storev_sync(Pointer schema, Pointer attributes,
                                            String collection, String label, String password,
                                            Pointer cancellable, PointerByReference error);

        /** 返回的 gchar* 必须用 {@link #secret_password_free} 释放，不能直接 g_free */
        Pointer secret_password_lookupv_sync(Pointer schema, Pointer attributes,
                                             Pointer cancellable, PointerByReference error);

        boolean secret_password_clearv_sync(Pointer schema, Pointer attributes,
                                            Pointer cancellable, PointerByReference error);

        void secret_password_free(Pointer password);
    }

    private interface GLib extends Library {
        GLib INSTANCE = Native.load("glib-2.0", GLib.class);

        Pointer g_hash_table_new_full(Pointer hashFunc, Pointer keyEqualFunc,
                                      Pointer keyDestroyFunc, Pointer valueDestroyFunc);

        void g_hash_table_insert(Pointer table, Pointer key, Pointer value);

        void g_hash_table_unref(Pointer table);

        Pointer g_strdup(String str);

        void g_error_free(Pointer error);
    }

    /**
     * GLib 里几个现成函数的地址，用作 {@code g_hash_table_new_full} 的函数指针。
     *
     * <p>必须用 {@code g_str_hash} / {@code g_str_equal} 而不是 {@code NULL}：
     * 后者会让哈希表按<b>指针</b>比较 key，而 libsecret 是按<b>字符串内容</b>
     * 去查属性名的，用指针比较就永远查不到，症状是「存进去读不出来」。</p>
     *
     * <p>销毁函数用 {@code g_free}，于是表被 unref 时会自动释放我们 {@code g_strdup}
     * 出来的 key 与 value，不必手工逐个 free。</p>
     *
     * <h2>★ 为什么必须懒加载，不能写成静态初始化</h2>
     *
     * <p>静态初始化块的语义是「一碰这个类就加载原生库」。而
     * {@link CredentialStoreConfig} 在用户显式传
     * {@code tinyquery.credential.store=linux} 时会构造本类 —— <b>哪怕当前是 Windows</b>。
     * 那一刻抛出的 {@code UnsatisfiedLinkError} 会让构造函数失败，
     * 被配置类的兜底 catch 成 {@link NoOpCredentialStore}，于是
     * 「显式指定 linux」在 Windows 上悄悄变成了「不支持保存密码」。</p>
     *
     * <p><b>实测踩过</b>：Windows 上的
     * {@code CredentialStoreConfigTest.explicitOverrideWins} 就是这么红起来的，
     * 而它在 Linux 上完全正常 —— 典型的「只有换平台才暴露」。</p>
     *
     * <p>macOS 那边的 {@code Security.INSTANCE} 之所以没这个问题，正是因为
     * JNA 的 {@code interface X extends Library { X INSTANCE = Native.load(...); }}
     * 写法天然是懒的（接口字段在首次访问时才初始化）。本类把 JNA 绑定写成这个
     * 形状，这几个函数指针同理。</p>
     */
    private static volatile Pointer gStrHash;
    private static volatile Pointer gStrEqual;
    private static volatile Pointer gFree;

    /** 取一次 GLib 函数指针并缓存。失败时抛 Error，由 {@link #loadable()} 兜住 */
    private static synchronized void ensureGlibFunctions() {
        if (gStrHash != null) {
            return;
        }
        NativeLibrary glib = NativeLibrary.getInstance("glib-2.0");
        gStrHash = glib.getFunction("g_str_hash");
        gStrEqual = glib.getFunction("g_str_equal");
        gFree = glib.getFunction("g_free");
    }

    /**
     * 只构造一次的 schema。
     *
     * <p>结构体与它内部引用的两个字符串放在<b>同一块</b> {@link Memory} 里 ——
     * 这样只需持有这一个对象就能保证所有原生指针都不悬空。
     * 若把字符串单独分配，它们可能在 GC 后失效，而原生侧仍指着旧地址，
     * 那种崩法极难排查。</p>
     */
    private static final Memory SCHEMA = buildSchema();

    private static final SecretService INSTANCE = new SecretService();

    private SecretService() {
        // 单例
    }

    /** 生产用：把本类交给 {@link LinuxCredentialStore} */
    static LinuxKeychainBackend backend() {
        return INSTANCE;
    }

    // ==================== 接缝实现 ====================

    @Override
    public boolean loadable() {
        try {
            ensureGlibFunctions();
            // 触碰 LibSecret.INSTANCE 会触发它自己的懒加载；
            // 库不存在时这里抛 UnsatisfiedLinkError，由本方法的 catch 兜住
            return LibSecret.INSTANCE != null
                    && gStrHash != null
                    && gStrEqual != null
                    && gFree != null
                    && SCHEMA != null;
        } catch (Throwable t) {
            // 刻意返回 false 而不是往外抛：调用方（LinuxCredentialStore）
            // 据此把「保存密码」置灰，程序其余部分必须完全正常
            log.debug("libsecret 或 glib 加载失败", t);
            return false;
        }
    }

    @Override
    public ReadOutcome read(String key) {
        Pointer table = newAttributeTable(key);
        try {
            PointerByReference errRef = new PointerByReference();
            Pointer raw = LibSecret.INSTANCE.secret_password_lookupv_sync(
                    SCHEMA, table, null, errRef);
            String error = takeError(errRef);
            if (raw != null) {
                String password = raw.getString(0, StandardCharsets.UTF_8.name());
                LibSecret.INSTANCE.secret_password_free(raw);
                return ReadOutcome.found(password == null ? "" : password);
            }
            if (error != null) {
                return ReadOutcome.failed(error);
            }
            // 没有返回值也没有错误 = 条目不存在。这是最常见的情形
            // （用户从没勾过保存密码），必须当成「成功但没有密码」
            return ReadOutcome.missing();
        } finally {
            GLib.INSTANCE.g_hash_table_unref(table);
        }
    }

    @Override
    public String store(String key, String label, String password) {
        Pointer table = newAttributeTable(key);
        try {
            PointerByReference errRef = new PointerByReference();
            boolean ok = LibSecret.INSTANCE.secret_password_storev_sync(
                    SCHEMA, table, null, label, password == null ? "" : password, null, errRef);
            String error = takeError(errRef);
            if (ok) {
                return null;
            }
            return error != null ? error : "保存到系统钥匙串失败（未知原因）";
        } finally {
            GLib.INSTANCE.g_hash_table_unref(table);
        }
    }

    @Override
    public String delete(String key) {
        Pointer table = newAttributeTable(key);
        try {
            PointerByReference errRef = new PointerByReference();
            boolean ok = LibSecret.INSTANCE.secret_password_clearv_sync(
                    SCHEMA, table, null, errRef);
            String error = takeError(errRef);
            if (ok) {
                return null;
            }
            // libsecret 的 clear 在「条目本来就不存在」时返回 TRUE，
            // 所以走到这里一定是真失败（例如连不上 D-Bus）
            return error != null ? error : "从系统钥匙串删除失败（未知原因）";
        } finally {
            GLib.INSTANCE.g_hash_table_unref(table);
        }
    }

    // ==================== 内部 ====================

    /**
     * 按偏移手写 schema。
     *
     * <p>第二个及之后的属性槽保持全 0 —— libsecret 靠 {@code name == NULL}
     * 判断属性列表结束（等价于 C 里的 {@code G_GNUC_NULL_TERMINATED}）。</p>
     */
    private static Memory buildSchema() {
        byte[] schemaName = SCHEMA_NAME.getBytes(StandardCharsets.UTF_8);
        byte[] attrName = ATTRIBUTE_KEY.getBytes(StandardCharsets.UTF_8);

        long nameOffset = SCHEMA_SIZE;
        long attrOffset = nameOffset + schemaName.length + 1;
        long total = attrOffset + attrName.length + 1;

        Memory mem = new Memory(total);
        mem.clear();

        mem.write(nameOffset, schemaName, 0, schemaName.length);
        mem.setByte(nameOffset + schemaName.length, (byte) 0);
        mem.write(attrOffset, attrName, 0, attrName.length);
        mem.setByte(attrOffset + attrName.length, (byte) 0);

        mem.setPointer(SCHEMA_OFF_NAME, mem.share(nameOffset));
        mem.setInt(SCHEMA_OFF_FLAGS, SCHEMA_FLAGS_NONE);
        mem.setPointer(SCHEMA_OFF_ATTRIBUTES + ATTRIBUTE_OFF_NAME, mem.share(attrOffset));
        mem.setInt(SCHEMA_OFF_ATTRIBUTES + ATTRIBUTE_OFF_TYPE, ATTRIBUTE_TYPE_STRING);
        return mem;
    }

    /** 建一个只含 {@code key -> key值} 的属性表。调用方负责 unref */
    private static Pointer newAttributeTable(String key) {
        ensureGlibFunctions();
        Pointer table = GLib.INSTANCE.g_hash_table_new_full(
                gStrHash, gStrEqual, gFree, gFree);
        // g_strdup 出来的内存由表的销毁函数负责释放（见 gFree 的说明）
        GLib.INSTANCE.g_hash_table_insert(
                table, GLib.INSTANCE.g_strdup(ATTRIBUTE_KEY), GLib.INSTANCE.g_strdup(key));
        return table;
    }

    /**
     * 取出并释放 GError，返回可展示的消息；没有错误时返回 null。
     *
     * <p>必须释放 —— 否则每次失败都漏一小块原生内存。</p>
     */
    private static String takeError(PointerByReference errRef) {
        Pointer err = errRef.getValue();
        if (err == null) {
            return null;
        }
        String message = null;
        try {
            Pointer msg = err.getPointer(GERROR_OFF_MESSAGE);
            if (msg != null) {
                message = msg.getString(0, StandardCharsets.UTF_8.name());
            }
        } catch (RuntimeException e) {
            log.debug("读取 GError 消息失败", e);
        } finally {
            GLib.INSTANCE.g_error_free(err);
        }
        return (message == null || message.isBlank()) ? "系统钥匙串返回了错误" : message;
    }

    /**
     * 读取结果。
     *
     * <p>三种情况必须能区分开：<b>读到了</b>、<b>本来就没有</b>、<b>出错</b>。
     * 把「本来就没有」当成错误，会让从没存过密码的正常用户在日志里看到一片红。</p>
     */
    record ReadOutcome(boolean found, String password, String error) {

        static ReadOutcome found(String password) {
            return new ReadOutcome(true, password, "");
        }

        static ReadOutcome missing() {
            return new ReadOutcome(false, "", "");
        }

        static ReadOutcome failed(String reason) {
            return new ReadOutcome(false, "", reason);
        }
    }
}
