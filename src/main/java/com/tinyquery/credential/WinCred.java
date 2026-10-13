package com.tinyquery.credential;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Windows 凭据管理器的原生接口声明（只依赖 JNA，不需要 jna-platform）。
 *
 * <p>刻意做成「只有静态方法的纯工具类」，且所有方法都接收或返回普通 Java 类型，
 * 这样调用方（{@link WindowsCredentialStore}）只需要关心业务语义，
 * 而这里的指针、结构体、错误码全部封在内部。</p>
 *
 * <p>实现时踩过的三个坑（都是实测出来的）：</p>
 *
 * <ol>
 *   <li>GetLastError 不在 advapi32.dll 里，在 kernel32.dll 里。把它声明在
 *       {@link Advapi32} 接口上会抛
 *       {@code UnsatisfiedLinkError: Error looking up function 'GetLastError'}，
 *       而且只在第一次真正出错、最需要错误码的时候才炸，正常路径完全看不出来。
 *       正确做法是用 JNA 自带的 {@link Native#getLastError()}，
 *       它从本机线程状态读取，不绑定任何具体 DLL。</li>
 *   <li>{@code Structure(Pointer)} 构造函数是 protected，从 CredRead 拿回来的
 *       裸指针不能直接 {@code new}，必须走 {@link Structure#newInstance}。</li>
 *   <li>密码有长度上限：2560 字节（UTF-16LE，即 1280 个字符）。实测二分确认：
 *       1280 字符可写，1281 字符返回错误码 1783。这与微软文档的
 *       {@code CRED_MAX_CREDENTIAL_BLOB_SIZE = 5 * 512} 吻合。</li>
 * </ol>
 */
final class WinCred {

    private WinCred() {
    }

    // ==================== 常量 ====================

    /** 通用凭据类型。LynMill 存的是任意密码，不属于域/证书等专有类型 */
    static final int CRED_TYPE_GENERIC = 1;

    /** 持久化到本机。随用户账户保存，重启后仍在 */
    static final int CRED_PERSIST_LOCAL_MACHINE = 2;

    /** 密码（CredentialBlob）的最大字节数。见类注释坑 3 */
    static final int MAX_BLOB_BYTES = 5 * 512;   // 2560

    /** ERROR_NOT_FOUND：凭据不存在。这是正常状态，不是错误 */
    static final int ERROR_NOT_FOUND = 1168;

    /** ERROR_INVALID_CREDENTIAL_BLOB_SIZE：密码超长 */
    static final int ERROR_INVALID_CREDENTIAL_BLOB_SIZE = 1783;

    /** ERROR_NO_SUCH_LOGON_SESSION / ERROR_NOT_FOUND 之外，常见的「凭据服务不可用」 */
    static final int ERROR_NO_SUCH_LOGON_SESSION = 1312;

    // ==================== 结构体 ====================

    /**
     * CREDENTIALW 结构体。字段顺序必须与 Windows SDK 完全一致，
     * 顺序错了会读到垃圾数据甚至崩溃，而且不会有任何编译期提示。
     */
    public static class CREDENTIAL extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public long LastWritten;            // FILETIME
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("Flags", "Type", "TargetName", "Comment", "LastWritten",
                    "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount",
                    "Attributes", "TargetAlias", "UserName");
        }
    }

    /** advapi32.dll 中我们需要的三个函数 */
    public interface Advapi32 extends StdCallLibrary {
        Advapi32 INSTANCE = Native.load("Advapi32", Advapi32.class,
                W32APIOptions.DEFAULT_OPTIONS);

        boolean CredWriteW(CREDENTIAL credential, int flags);

        boolean CredReadW(WString target, int type, int flags, PointerByReference credential);

        boolean CredDeleteW(WString target, int type, int flags);

        /** 释放 CredRead 分配的缓冲区。不调用会泄漏原生内存 */
        void CredFree(Pointer buffer);
    }

    // ==================== 错误码工具 ====================

    /** 最近一次 Win32 调用的错误码。见类注释坑 1 */
    static int lastError() {
        return Native.getLastError();
    }

    /**
     * 把错误码翻译成人话。
     *
     * <p>必须翻译：把「1783」直接甩给用户，他不可能知道那是什么意思。
     * 而这几条恰恰是最可能发生的。</p>
     */
    static String describe(int code) {
        return switch (code) {
            case ERROR_NOT_FOUND -> "凭据不存在";
            case ERROR_INVALID_CREDENTIAL_BLOB_SIZE ->
                    "密码过长，超过系统凭据存储的上限（" + (MAX_BLOB_BYTES / 2) + " 个字符）";
            case ERROR_NO_SUCH_LOGON_SESSION ->
                    "系统凭据服务当前不可用（未登录会话或被策略禁用）";
            case 5 -> "没有权限访问系统凭据存储";
            default -> "系统凭据操作失败（错误码 " + code + "）";
        };
    }

    // ==================== 密码长度校验 ====================

    /**
     * 校验密码长度是否在系统上限之内。
     *
     * <p>提前自己校验，而不是等系统拒绝，原因是：系统只回一个错误码，
     * 而我们能给出「你的密码超过了 1280 个字符」这种用户看得懂的说明。
     * 另外也避免把一个必然失败的操作真的提交给系统。</p>
     *
     * @return 合法返回 null；不合法返回给用户看的原因
     */
    static String checkPasswordLength(String password) {
        if (password == null) {
            return null;
        }
        int bytes = password.getBytes(StandardCharsets.UTF_16LE).length;
        if (bytes > MAX_BLOB_BYTES) {
            return "密码过长（" + password.length() + " 个字符），"
                    + "系统凭据存储最多支持 " + (MAX_BLOB_BYTES / 2) + " 个字符";
        }
        return null;
    }

    // ==================== 三个操作 ====================

    /**
     * 写入或覆盖一条凭据。
     *
     * @return 成功返回 null；失败返回给用户看的原因
     */
    static String write(String key, String username, String password) {
        String tooLong = checkPasswordLength(password);
        if (tooLong != null) {
            return tooLong;
        }

        String secret = password == null ? "" : password;
        byte[] blob = secret.getBytes(StandardCharsets.UTF_16LE);
        // 空密码也要分配 1 字节：CredentialBlobSize=0 配合 null 指针在部分系统上会被拒
        Memory blobMem = new Memory(Math.max(blob.length, 1));
        if (blob.length > 0) {
            blobMem.write(0, blob, 0, blob.length);
        }

        CREDENTIAL cred = new CREDENTIAL();
        cred.Flags = 0;
        cred.Type = CRED_TYPE_GENERIC;
        cred.TargetName = new WString(key);
        cred.Comment = new WString("LynMill 数据源密码");
        cred.CredentialBlobSize = blob.length;
        cred.CredentialBlob = blobMem;
        cred.Persist = CRED_PERSIST_LOCAL_MACHINE;
        cred.AttributeCount = 0;
        cred.Attributes = null;
        cred.TargetAlias = null;
        cred.UserName = new WString(username == null ? "" : username);

        if (!Advapi32.INSTANCE.CredWriteW(cred, 0)) {
            return describe(lastError());
        }
        return null;
    }

    /**
     * 读取一条凭据的结果。
     *
     * <p>这里刻意用具名类型而不是 {@code String[]}：最初用「长度 1 = 成功、长度 2 =
     * 失败」的数组约定，调用方一眼看不出哪个是哪个，结果真的把成功当成了失败。
     * 后果是「保存密码」在所有 Windows 机器上都是灰的，看起来像功能没做，极难发现。</p>
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

    /**
     * 读取一条凭据。
     *
     * @return 凭据不存在返回 {@link ReadOutcome#missing()}（注意这不是失败）；
     *         存在时返回 {@link ReadOutcome#of}；真出错时返回 {@link ReadOutcome#failed}
     */
    static ReadOutcome read(String key) {
        PointerByReference ref = new PointerByReference();
        if (!Advapi32.INSTANCE.CredReadW(new WString(key), CRED_TYPE_GENERIC, 0, ref)) {
            int err = lastError();
            if (err == ERROR_NOT_FOUND) {
                return ReadOutcome.missing();   // 明确区分「不存在」与「失败」
            }
            return ReadOutcome.failed(describe(err));
        }

        Pointer p = ref.getValue();
        try {
            CREDENTIAL cred = Structure.newInstance(CREDENTIAL.class, p);   // 见类注释坑 2
            cred.read();
            byte[] blob = cred.CredentialBlob.getByteArray(0, cred.CredentialBlobSize);
            return ReadOutcome.of(new String(blob, StandardCharsets.UTF_16LE));
        } finally {
            // CredRead 用 CredFree 分配，必须由我们释放，否则每次读取都泄漏原生内存
            Advapi32.INSTANCE.CredFree(p);
        }
    }

    /**
     * 删除一条凭据。
     *
     * @return 成功或本就不存在返回 null；其它失败返回原因
     */
    static String delete(String key) {
        if (Advapi32.INSTANCE.CredDeleteW(new WString(key), CRED_TYPE_GENERIC, 0)) {
            return null;
        }
        int err = lastError();
        if (err == ERROR_NOT_FOUND) {
            return null;   // 本来就没有 = 已经达到目的
        }
        return describe(err);
    }

    /** 能否加载 advapi32（非 Windows 平台、或原生库加载失败时为 false） */
    static boolean isSupported() {
        return Native.POINTER_SIZE > 0 && System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("windows");
    }
}
