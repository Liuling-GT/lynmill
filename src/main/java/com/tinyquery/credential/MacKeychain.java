package com.tinyquery.credential;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * macOS 钥匙串（Keychain Services）的原生接口声明。
 *
 * <p>和 {@link WinCred} 一样刻意做成「只有静态方法的纯工具类」：指针、引用计数、
 * 错误码全部封在内部，调用方（{@link MacCredentialStore}）只面对普通 Java 类型。</p>
 *
 * <p>所有查找（读、判断存在、改、删）都只按 service 匹配，account 传 NULL。
 * 理由有两条：{@link CredentialStore#read} 只收 key、拿不到用户名，所以读这条
 * 路径只能是「按 service 找」；如果写路径按 (service, account) 找，那么用户改了
 * 数据库用户名之后查找会落空，进而走到新建，钥匙串里出现两条同 service 的条目，
 * 此后 read 随机读到哪条都说不清。只用 service 找就永远不会重复。代价是用户名
 * 改动后条目的 account 字段仍是旧值，它只是给人看的元数据
 * （{@link CredentialStore#read} 从不读回用户名），不影响功能。</p>
 *
 * <p>用的是旧的 {@code SecKeychain*} 而不是新的 {@code SecItem*}。前者自 10.10 起
 * 被标记 deprecated，但新的 {@code SecItemAdd} / {@code SecItemCopyMatching} 要求
 * 自己用 CoreFoundation 构造 CFDictionary（{@code CFStringCreateWithCString} /
 * {@code CFDictionaryCreate} / {@code CFRelease}…），JNA 代码量要翻好几倍，而且
 * 每一步都要管引用计数，写错就是内存泄漏或野指针；旧的这套直接收 C 字符串和长度。
 * 代价只是编译期 deprecation 警告。</p>
 *
 * <p>2026-10-03 在 macOS 14.4 / arm64 / JDK 17 上实测确认：
 * {@code Native.load("Security", …)} 按框架名加载可用，不需要绝对路径；
 * 只看 service、account 传 NULL 查找能命中（这一步专门验过，因为整个读路径都
 * 建立在它上面）；只取 itemRef、不读密码（{@code passwordLength}/{@code passwordData}
 * 传 NULL）可行，写路径因此不必解密旧密码；实测条目不存在的错误码 = {@code -25300}，
 * 与下面常量一致。{@code SecKeychainItemModifyContent} 与
 * {@code SecKeychainItemModifyAttributesAndData} 两个符号都存在且签名完全相同，
 * 但 Apple 头文件写着 {@code ModifyContent}「应当只与
 * {@code SecKeychainItemCopyContent()} 配合使用」，而我们的 itemRef 来自
 * {@code FindGenericPassword}，按文档语义用 {@code ModifyAttributesAndData}。</p>
 *
 * <p>密码按 UTF-8 字节存，不是 UTF-16LE，这是和 Windows 实现最大的区别
 * （Windows 的 {@code CredentialBlob} 是 UTF-16LE）。长度参数因此也必须传
 * 字节数而不是字符数。</p>
 */
final class MacKeychain {

    private MacKeychain() {
    }

    // ==================== 错误码（OSStatus，int32） ====================

    static final int ERR_SEC_SUCCESS = 0;
    /** 条目不存在。这是正常状态，不是故障。本机实测确认其值为 -25300 */
    static final int ERR_SEC_ITEM_NOT_FOUND = -25300;
    /** 条目已存在。write 时出现说明「先找再改」的逻辑有漏洞 */
    static final int ERR_SEC_DUPLICATE_ITEM = -25299;
    static final int ERR_SEC_AUTH_FAILED = -25293;
    /** 不允许交互，例如没有图形登录会话 */
    static final int ERR_SEC_INTERACTION_NOT_ALLOWED = -25308;
    /** 用户点了「拒绝」 */
    static final int ERR_SEC_USER_CANCELED = -128;
    static final int ERR_SEC_PARAM = -50;

    // ==================== 原生声明 ====================

    /**
     * Security.framework。
     *
     * <p>{@code INSTANCE} 是接口的静态字段，只有第一次真正用到这个接口时
     * 才会触发 {@code Native.load}。这一点是刻意的：非 macOS 平台上只要不调用
     * 本类的方法，就不会加载 Security，也就不会抛 {@code UnsatisfiedLinkError}。</p>
     */
    interface Security extends Library {

        Security INSTANCE = Native.load("Security", Security.class);

        /**
         * {@code passwordLength} / {@code passwordData} 允许传 null ——
         * 头文件明确写着「若只想要 itemRef 而不需要密码，可传 NULL」。
         * 写路径只需要 itemRef，传 NULL 可以避免解密已有密码，少一次可能触发授权的访问。
         */
        int SecKeychainFindGenericPassword(Pointer keychainOrArray,
                                          int serviceNameLength, byte[] serviceName,
                                          int accountNameLength, byte[] accountName,
                                          IntByReference passwordLength,
                                          PointerByReference passwordData,
                                          PointerByReference itemRef);

        /** {@code itemRef} 是 {@code CF_RETURNS_RETAINED}，拿到后必须 CFRelease */
        int SecKeychainAddGenericPassword(Pointer keychain,
                                         int serviceNameLength, byte[] serviceName,
                                         int accountNameLength, byte[] accountName,
                                         int passwordLength, byte[] passwordData,
                                         PointerByReference itemRef);

        /**
         * 原地修改已有条目的密码，这是保住 ACL 的关键。
         *
         * <p>{@code attrList} 传 null：service 不变，我们只换密码。</p>
         *
         * <p>注意 {@code data} 传 null 表示「不修改数据」而不是「设成空」，
         * 所以空密码必须传一个非 null 的缓冲（见 {@link #blob}）。</p>
         */
        int SecKeychainItemModifyAttributesAndData(Pointer itemRef, Pointer attrList,
                                                  int length, byte[] data);

        /** 释放 Find 返回的密码缓冲。不调用就是每次读取漏一块原生内存 */
        int SecKeychainItemFreeContent(Pointer attrList, Pointer data);

        int SecKeychainItemDelete(Pointer itemRef);
    }

    /** CoreFoundation：只为 CFRelease。SecKeychainItemRef 是 CFTypeRef */
    interface CoreFoundation extends Library {

        CoreFoundation INSTANCE = Native.load("CoreFoundation", CoreFoundation.class);

        void CFRelease(Pointer cf);
    }

    // ==================== 读取结果 ====================

    /**
     * 读取一条密码的结果。
     *
     * <p>沿用了 {@link WinCred.ReadOutcome} 的具名类型做法。不用「长度为 1 的数组
     * = 成功」这类约定，是因为调用方一眼看不出哪个是哪个，Windows 端就因此把成功
     * 当成了失败，后果是「保存密码」在所有机器上都是灰的，看起来像功能没做。</p>
     */
    record ReadOutcome(boolean found, String password, String error) {

        static ReadOutcome missing() {
            return new ReadOutcome(false, null, "");
        }

        static ReadOutcome of(String password) {
            return new ReadOutcome(true, password, "");
        }

        static ReadOutcome failed(String error) {
            return new ReadOutcome(false, null, error);
        }
    }

    // ==================== 错误码翻译 ====================

    /**
     * 把 OSStatus 翻译成人话。
     *
     * <p>必须翻译：把「-25308」直接甩给用户，他不可能知道那是什么意思。</p>
     */
    static String describe(int code) {
        return switch (code) {
            case ERR_SEC_ITEM_NOT_FOUND -> "钥匙串中没有这条密码";
            case ERR_SEC_DUPLICATE_ITEM -> "钥匙串中已存在同名条目";
            case ERR_SEC_AUTH_FAILED -> "钥匙串拒绝了本次访问（密码或权限不足）";
            case ERR_SEC_INTERACTION_NOT_ALLOWED ->
                    "系统不允许弹出钥匙串授权窗口（需要以图形界面登录的会话）";
            case ERR_SEC_USER_CANCELED -> "你取消了钥匙串授权";
            case ERR_SEC_PARAM -> "钥匙串调用参数不合法";
            default -> "钥匙串操作失败（错误码 " + code + "）";
        };
    }

    // ==================== 平台与加载 ====================

    /**
     * 是否是 macOS。
     *
     * <p>不能写成 {@code os.contains("win")} —— 字符串 {@code "darwin"}
     * 里就含 {@code "win"}，Mac 会被误判成 Windows。</p>
     */
    static boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return Native.POINTER_SIZE > 0 && (os.contains("mac") || os.contains("darwin"));
    }

    /**
     * 只确认原生库能被加载，不碰钥匙串。
     *
     * <p>这是 macOS 上「可用性」的判定依据。刻意不做「写一条、读一条、删一条」的
     * 真实探测：在 macOS 上访问钥匙串条目可能弹系统授权框，尤其应用未签名时，
     * 那就变成「每次启动弹一个对话框」，比把勾选框置灰还糟。
     * 代价是勾选框一开始显示为可用，用户点下去才知道能不能用。</p>
     *
     * @throws Throwable 原生库加载失败时抛出（可能是不满足链接的 Error）
     */
    static void verifyLoadable() {
        if (Security.INSTANCE == null || CoreFoundation.INSTANCE == null) {
            throw new IllegalStateException("Security.framework 加载返回 null");
        }
    }

    // ==================== 五个原始操作 ====================

    /**
     * 读一条密码（只按 service 找）。
     *
     * @return 不存在返回 {@link ReadOutcome#missing()}（注意这不是失败）
     */
    static ReadOutcome read(String service) {
        byte[] svc = utf8(service);
        IntByReference len = new IntByReference();
        PointerByReference data = new PointerByReference();
        PointerByReference item = new PointerByReference();

        int rc = Security.INSTANCE.SecKeychainFindGenericPassword(
                null, svc.length, svc, 0, null, len, data, item);
        if (rc != ERR_SEC_SUCCESS) {
            return rc == ERR_SEC_ITEM_NOT_FOUND
                    ? ReadOutcome.missing()       // 区分「不存在」与「失败」
                    : ReadOutcome.failed(describe(rc));
        }

        try {
            Pointer buf = data.getValue();
            int n = len.getValue();
            if (buf == null || n <= 0) {
                return ReadOutcome.of("");     // 空密码是合法值
            }
            return ReadOutcome.of(new String(buf.getByteArray(0, n), StandardCharsets.UTF_8));
        } finally {
            // 两块内存都要还：密码缓冲用 ItemFreeContent，itemRef 用 CFRelease
            Security.INSTANCE.SecKeychainItemFreeContent(null, data.getValue());
            release(item.getValue());
        }
    }

    /**
     * 条目是否已存在（只按 service 找）。
     *
     * <p>刻意传 null 给 passwordLength/passwordData：只需要 itemRef，
     * 不必解密已有密码。</p>
     *
     * <p>任何非成功状态都当作「不存在」。这样做是安全的：最坏情况是接下来去
     * {@link #add} 然后拿到「条目已存在」，用户看到一个可以理解的错误；
     * 因为这条路径上永远不会调用 delete，所以不会造成 ACL 丢失。</p>
     */
    static boolean exists(String service) {
        byte[] svc = utf8(service);
        PointerByReference item = new PointerByReference();

        int rc = Security.INSTANCE.SecKeychainFindGenericPassword(
                null, svc.length, svc, 0, null, null, null, item);
        if (rc != ERR_SEC_SUCCESS) {
            return false;
        }
        release(item.getValue());
        return true;
    }

    /**
     * 原地修改已存在条目的密码。
     *
     * <p>macOS 钥匙串条目带一张 ACL 分区列表，记录「哪些程序读它时不用弹窗」。
     * 如果写成「先删后建」，分区列表会被重置，用户点过的「始终允许」立刻失效，
     * 于是每次都要重新授权。</p>
     *
     * @return 成功返回 null；失败返回给用户看的原因（含条目不存在）
     */
    static String modify(String service, String password) {
        byte[] svc = utf8(service);
        PointerByReference item = new PointerByReference();

        int rc = Security.INSTANCE.SecKeychainFindGenericPassword(
                null, svc.length, svc, 0, null, null, null, item);
        if (rc != ERR_SEC_SUCCESS) {
            return describe(rc);
        }

        try {
            int m = Security.INSTANCE.SecKeychainItemModifyAttributesAndData(
                    item.getValue(), null, byteLen(password), blob(password));
            return m == ERR_SEC_SUCCESS ? null : describe(m);
        } finally {
            release(item.getValue());
        }
    }

    /**
     * 新建一条条目。
     *
     * <p>只有在这里 account 才有意义：它是写进条目、给人看的用户名。</p>
     *
     * @return 成功返回 null；失败返回原因
     */
    static String add(String service, String account, String password) {
        byte[] svc = utf8(service);
        byte[] acc = utf8(account);
        PointerByReference created = new PointerByReference();

        int rc = Security.INSTANCE.SecKeychainAddGenericPassword(
                null, svc.length, svc, acc.length, acc, byteLen(password), blob(password), created);
        if (rc != ERR_SEC_SUCCESS) {
            return describe(rc);
        }
        release(created.getValue());
        return null;
    }

    /**
     * 删除条目（只按 service 找）。
     *
     * @return 成功或本来就不存在返回 null；其它失败返回原因
     */
    static String delete(String service) {
        byte[] svc = utf8(service);
        PointerByReference item = new PointerByReference();

        int rc = Security.INSTANCE.SecKeychainFindGenericPassword(
                null, svc.length, svc, 0, null, null, null, item);
        if (rc == ERR_SEC_ITEM_NOT_FOUND) {
            return null;      // 本来就没有 = 目的已达成
        }
        if (rc != ERR_SEC_SUCCESS) {
            return describe(rc);
        }

        try {
            int d = Security.INSTANCE.SecKeychainItemDelete(item.getValue());
            return (d == ERR_SEC_SUCCESS || d == ERR_SEC_ITEM_NOT_FOUND) ? null : describe(d);
        } finally {
            // 头文件写明：Delete 不会释放 itemRef 本身，仍要 CFRelease
            release(item.getValue());
        }
    }

    // ==================== 工具 ====================

    /**
     * 密码的 UTF-8 字节。
     *
     * <p>空密码也必须给一个非 null 的缓冲：{@code ModifyAttributesAndData}
     * 把 data=null 解释成「不修改数据」，那样旧密码会被原样留下 —— 用户点「保存」
     * 反而改不掉密码。传一个 1 字节的缓冲、长度为 0，才是真正的「设成空」。</p>
     */
    private static byte[] blob(String password) {
        byte[] b = utf8(password);
        return b.length == 0 ? new byte[1] : b;
    }

    private static int byteLen(String password) {
        return utf8(password).length;
    }

    private static byte[] utf8(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    }

    /** CFRelease 一个可能为 null 的引用 */
    private static void release(Pointer p) {
        if (p != null) {
            CoreFoundation.INSTANCE.CFRelease(p);
        }
    }

    // ==================== 接缝 ====================

    /**
     * 供 {@link MacCredentialStore} 使用的真实实现。
     *
     * <p>返回一个只做转发的对象，这样测试可以用假实现替换掉全部原生调用
     * （见 {@link MacKeychainBackend}）。</p>
     */
    static MacKeychainBackend backend() {
        return new MacKeychainBackend() {
            @Override
            public boolean loadable() {
                verifyLoadable();
                return true;
            }

            @Override
            public ReadOutcome read(String service) {
                return MacKeychain.read(service);
            }

            @Override
            public boolean exists(String service) {
                return MacKeychain.exists(service);
            }

            @Override
            public String modify(String service, String password) {
                return MacKeychain.modify(service, password);
            }

            @Override
            public String add(String service, String account, String password) {
                return MacKeychain.add(service, account, password);
            }

            @Override
            public String delete(String service) {
                return MacKeychain.delete(service);
            }
        };
    }
}
