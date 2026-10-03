package com.tinyquery.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * macOS 平台的凭据存储实现（系统钥匙串，Keychain Services）。
 *
 * <p>通过 {@link MacKeychain} 调 Security.framework，不依赖 jna-platform。
 * 与 {@link WindowsCredentialStore} 是同一个接口的两个实现，上层完全无感。</p>
 *
 * <h2>和 Windows 实现的三处关键差异</h2>
 *
 * <ol>
 *   <li><b>重复保存必须原地改，绝不能先删后建。</b>见 {@link #write}。
 *       这是 macOS 独有的坑：钥匙串条目带一张 ACL 分区列表，删掉重建会把
 *       用户点过的「始终允许」一起丢掉，于是每次都弹窗。Windows 那边
 *       {@code CredWriteW} 本身就是同名原地替换语义，所以没这个问题。</li>
 *   <li><b>可用性判断是「懒」的，而且不碰钥匙串。</b>见 {@link #isAvailable}。
 *       Windows 会真写一条、读一条、删一条来探测，因为 DPAPI 不弹 UI；
 *       但 macOS 上读钥匙串可能弹系统授权框，启动时探测就变成「每次启动弹一个框」。</li>
 *   <li><b>密码按 UTF-8 存</b>，Windows 是 UTF-16LE。这是底层 API 决定的，
 *       对上层不可见。</li>
 * </ol>
 *
 * <h2>ACL 与代码签名（已知限制，不是缺陷）</h2>
 *
 * <p>钥匙串条目的访问控制绑在「写入它的那个程序」的代码签名身份上。应用未签名、
 * 或每次重新打包后身份变化时，系统会认为「不是同一个程序」从而重新弹窗。
 * 因此升级到新版本后可能要重新授权一次。</p>
 *
 * <p>这不会让功能中断：读不到密码时本实现返回「没有密码」/失败，
 * {@code DataSourceManager} 会照既有的 {@code NEEDS_PASSWORD} 路径让用户手输 ——
 * 也就是说「钥匙串读不到」复用了一条已经存在的分支，<b>不会变成新的一类故障</b>。
 * 正式解决要靠代码签名与公证，而本项目当前没有做（见 README 的「首次打开提示」）。</p>
 */
public class MacCredentialStore implements CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(MacCredentialStore.class);

    /** 平台名字是否匹配。false 时连尝试都不必 */
    private final boolean platformMatches;

    /** 原生调用。抽成接口是为了让「原地改而不是先删后建」这条规则可被测试盯住 */
    private final MacKeychainBackend keychain;

    /** 可用性缓存。null 表示还没判断过 */
    private volatile Boolean available;

    /** 不可用的原因（人话） */
    private volatile String reason = "";

    /** 生产用：接真实的 Security.framework */
    public MacCredentialStore() {
        this(MacKeychain.backend(), detectMac());
    }

    /** 测试用：接一个假的原生层，平台按真实系统判断 */
    MacCredentialStore(MacKeychainBackend keychain) {
        this(keychain, detectMac());
    }

    /**
     * 测试用：再接一个显式的平台标志。
     *
     * <p>为什么值得多开这个构造函数：本类最关键的逻辑是 {@link #write} 里
     * 「已有条目必须原地改」那条，而它的测试必须能在<b>没有 Mac 的机器上</b>跑
     * ——Windows 端复核、将来的 CI 都没有 Mac。平台判断如果写死在构造函数里，
     * 这些测试在 Windows 上就只能整类跳过，等于没测。</p>
     */
    MacCredentialStore(MacKeychainBackend keychain, boolean platformMatches) {
        this.keychain = keychain;
        this.platformMatches = platformMatches;
        if (!platformMatches) {
            this.available = Boolean.FALSE;
            this.reason = "当前系统（" + System.getProperty("os.name") + "）暂不支持保存密码";
            log.info("凭据存储不可用：{}", reason);
        }
    }

    /**
     * 本机是不是 macOS。
     *
     * <p>实测 {@code os.name} 为 {@code "Mac OS X"}。
     * <b>不能写成 {@code contains("win")}</b> —— {@code "darwin"} 里就含
     * {@code "win"}，Mac 会被误判成 Windows。</p>
     */
    private static boolean detectMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    // ==================== 可用性 ====================

    /**
     * 当前是否可用。
     *
     * <p><b>只判断「原生库能否加载」，不访问钥匙串。</b>见类注释差异 ②。</p>
     *
     * <p>还有一点很重要：这里必须<b>乐观地</b>返回 true（只要平台对、框架能加载），
     * 不能做严格的读写探测。因为 {@code DataSourceManager.fillSecretsFromStore}
     * 在 {@code isAvailable()} 为 false 时会<b>直接跳过读取</b> —— 那样用户明明
     * 存过密码也永远补不回来。</p>
     */
    @Override
    public boolean isAvailable() {
        if (!platformMatches) {
            return false;
        }
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        return checkNativeLoadable();
    }

    @Override
    public String unavailableReason() {
        isAvailable();     // 触发一次判断，保证 reason 有值
        return reason == null ? "" : reason;
    }

    private synchronized boolean checkNativeLoadable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        try {
            if (keychain.loadable()) {
                available = Boolean.TRUE;
                reason = "";
                log.info("macOS 钥匙串可用，已启用「保存密码」");
                return true;
            }
            available = Boolean.FALSE;
            reason = "系统钥匙串不可用";
            log.warn("钥匙串原生层报告不可用，将禁用「保存密码」");
            return false;
        } catch (Throwable t) {
            // Throwable 而非 Exception：JNA 加载原生库失败时抛的是
            // UnsatisfiedLinkError，那是 Error 不是 Exception。
            // 凭据存不了绝不能让程序起不来
            available = Boolean.FALSE;
            reason = "系统钥匙串不可用：" + t.getClass().getSimpleName();
            log.warn("钥匙串原生库加载失败，将禁用「保存密码」", t);
            return false;
        }
    }

    // ==================== 三个操作 ====================

    @Override
    public CredentialResult write(String key, String username, String password) {
        if (key == null || key.isBlank()) {
            return CredentialResult.fail("凭据标识不能为空");
        }
        if (!isAvailable()) {
            return CredentialResult.fail(unavailableReason());
        }
        try {
            // ★ 核心：已经存在就【原地改】，不存在才新建。
            //   绝不能写成 delete() 再 add()：那会把条目的 ACL 分区列表重置，
            //   用户点过的「始终允许」立刻失效 —— 表现就是「点了也没用，
            //   下次还弹」。详见 MacKeychain 的类注释。
            String err = keychain.exists(key)
                    ? keychain.modify(key, password)
                    : keychain.add(key, username, password);
            if (err != null) {
                log.warn("保存凭据失败：key={} 原因={}", key, err);
                return CredentialResult.fail(err);
            }
            log.debug("已保存凭据：{}", key);
            return CredentialResult.done();
        } catch (Throwable t) {
            log.warn("保存凭据异常：key=" + key, t);
            return CredentialResult.fail("保存密码失败：" + t.getClass().getSimpleName());
        }
    }

    @Override
    public CredentialResult read(String key) {
        if (key == null || key.isBlank()) {
            return CredentialResult.fail("凭据标识不能为空");
        }
        if (!isAvailable()) {
            // 不可用时返回「没有密码」而不是失败：上层据此走「请用户输入密码」的正常分支
            return CredentialResult.notFound();
        }
        try {
            MacKeychain.ReadOutcome got = keychain.read(key);
            if (got.found() && got.error().isEmpty()) {
                return CredentialResult.ok(got.password());
            }
            if (got.error().isEmpty()) {
                return CredentialResult.notFound();
            }
            // 读失败（例如授权被拒、钥匙串上锁）同样会让上层走 NEEDS_PASSWORD
            // 让用户手输，功能不中断 —— 这是既有的兜底路径，不是新加的分支
            return CredentialResult.fail(got.error());
        } catch (Throwable t) {
            log.warn("读取凭据异常：key=" + key, t);
            return CredentialResult.fail("读取密码失败：" + t.getClass().getSimpleName());
        }
    }

    @Override
    public CredentialResult delete(String key) {
        if (key == null || key.isBlank()) {
            return CredentialResult.fail("凭据标识不能为空");
        }
        if (!isAvailable()) {
            return CredentialResult.done();     // 没存过，也谈不上删
        }
        try {
            String err = keychain.delete(key);
            if (err != null) {
                log.warn("删除凭据失败：key={} 原因={}", key, err);
                return CredentialResult.fail(err);
            }
            log.debug("已删除凭据：{}", key);
            return CredentialResult.done();
        } catch (Throwable t) {
            log.warn("删除凭据异常：key=" + key, t);
            return CredentialResult.fail("删除密码失败：" + t.getClass().getSimpleName());
        }
    }
}
