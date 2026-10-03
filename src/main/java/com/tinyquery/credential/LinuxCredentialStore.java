package com.tinyquery.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Linux 平台的凭据存储实现（Secret Service，由 GNOME Keyring / KWallet 提供）。
 *
 * <p>通过 {@link SecretService} 调 libsecret，不引入任何新的 Java 依赖。
 * 与 {@link WindowsCredentialStore}、{@link MacCredentialStore} 是同一个接口的
 * 三个实现，上层完全无感。</p>
 *
 * <h2>和另外两个实现的关键差异</h2>
 *
 * <ol>
 *   <li><b>「不可用」在 Linux 上是常态，而不是异常。</b>服务器版、精简发行版、
 *       纯窗口管理器环境（i3 之类）通常没装 keyring；甚至装了 keyring 但守护进程
 *       没启动；再或者有 keyring 但它上了锁。这些情况都表现为「读写失败」。
 *       所以本实现在任何一步失败时都退回「没有密码」，让上层走既有的
 *       {@code NEEDS_PASSWORD} 路径让用户手输 —— <b>功能绝不中断</b>。</li>
 *   <li><b>可用性判断同样是「乐观」的，也不碰钥匙串。</b>理由和 macOS 一样：
 *       {@code DataSourceManager.fillSecretsFromStore} 在 {@code isAvailable()}
 *       为 false 时会<b>直接跳过读取</b>，那样用户存过的密码永远补不回来。
 *       所以这里只确认「库能加载」，不做读写探测。而且探测本身会弹解锁框。</li>
 *   <li><b>密码按 UTF-8 存</b>（Windows 是 UTF-16LE，由底层 API 决定，对上层不可见）。</li>
 * </ol>
 *
 * <h2>用户名存在哪里</h2>
 *
 * <p>libsecret 的条目只有「属性」（参与查找匹配）与「标签」（给人看的）两类元数据，
 * 没有独立的用户名字段。本实现把用户名拼进<b>标签</b>，而不是新增一个属性 ——
 * 原因是属性会参与匹配：{@link CredentialStore#read} 只拿到 key、拿不到用户名，
 * 若把用户名做成属性，用户一改数据库用户名就会查找落空（与 macOS 那边
 * 「只按 service 查」是同一个考虑）。</p>
 */
public class LinuxCredentialStore implements CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(LinuxCredentialStore.class);

    /** 平台名字是否匹配。false 时连尝试都不必 */
    private final boolean platformMatches;

    /** 原生调用。抽成接口是为了让本类的逻辑能在没有 D-Bus 的机器上被测到 */
    private final LinuxKeychainBackend keychain;

    /** 可用性缓存。null 表示还没判断过 */
    private volatile Boolean available;

    /** 不可用的原因（人话） */
    private volatile String reason = "";

    /** 生产用：接真实的 libsecret */
    public LinuxCredentialStore() {
        this(SecretService.backend(), detectLinux());
    }

    /** 测试用：接一个假的原生层，平台按真实系统判断 */
    LinuxCredentialStore(LinuxKeychainBackend keychain) {
        this(keychain, detectLinux());
    }

    /**
     * 测试用：再接一个显式的平台标志。
     *
     * <p>与 {@link MacCredentialStore} 同一个理由：本类的全部逻辑（包括不可用时的
     * 退化行为）都应当能在<b>没有 Linux 的机器上</b>被测试盯住，否则 Windows 端
     * 复核与将来的 CI 只能整类跳过，等于没测。</p>
     */
    LinuxCredentialStore(LinuxKeychainBackend keychain, boolean platformMatches) {
        this.keychain = keychain;
        this.platformMatches = platformMatches;
        if (!platformMatches) {
            this.available = Boolean.FALSE;
            this.reason = "当前系统（" + System.getProperty("os.name") + "）暂不支持保存密码";
            log.info("凭据存储不可用：{}", reason);
        }
    }

    /**
     * 本机是不是 Linux。
     *
     * <p>{@code os.name} 在 Linux 上实测为 {@code "Linux"}，所以判据写
     * {@code contains("linux")} 即可。<b>不要</b>为了「兼容各种 Unix」而放宽成
     * {@code contains("nix")} —— 那会把未来某个真实系统误判成能存密码，
     * 而实际上 libsecret 并不存在。宁可判否（退回不保存密码），也不要判错。</p>
     */
    private static boolean detectLinux() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux");
    }

    // ==================== 可用性 ====================

    /**
     * 当前是否可用。
     *
     * <p><b>只判断「原生库能否加载」，不访问钥匙串</b>（见类注释 ②）。
     * 而且要<b>乐观地</b>返回 true —— 因为上层在 false 时会跳过读取，
     * 那样明明存过密码的用户也永远补不回来。</p>
     *
     * <p>真正连不上 keyring 时，{@link #write} / {@link #read} 会各自失败，
     * 上层照常退化到「请用户输入密码」。</p>
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
                log.info("Linux Secret Service 可用，已启用「保存密码」");
                return true;
            }
            available = Boolean.FALSE;
            reason = "系统缺少 libsecret（或无法加载）";
            log.warn("libsecret 原生层报告不可用，将禁用「保存密码」");
            return false;
        } catch (Throwable t) {
            // Throwable 而非 Exception：JNA 加载原生库失败时抛的是
            // UnsatisfiedLinkError，那是 Error 不是 Exception。
            // 凭据存不了绝不能让程序起不来
            available = Boolean.FALSE;
            reason = "系统缺少 libsecret（或无法加载）：" + t.getClass().getSimpleName();
            log.warn("libsecret 原生库加载失败，将禁用「保存密码」", t);
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
            // libsecret 的 storev 本身就是 upsert（按 schema + 属性匹配），
            // 所以这里不需要 macOS 那种「先查 exists 再决定 modify 还是 add」。
            // 千万不要画蛇添足地改成「先 delete 再 store」—— 那会多一次往返，
            // 还会在 delete 成功、store 失败时把用户的密码弄丢
            String err = keychain.store(key, buildLabel(key, username), password);
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
            // 不可用时返回「没有密码」而不是失败：
            // 上层据此走「请用户输入密码」的正常分支
            return CredentialResult.notFound();
        }
        try {
            SecretService.ReadOutcome got = keychain.read(key);
            if (got.found() && got.error().isEmpty()) {
                return CredentialResult.ok(got.password());
            }
            if (got.error().isEmpty()) {
                return CredentialResult.notFound();
            }
            // 读失败（keyring 没装、守护进程没起、被锁、D-Bus 连不上）同样让上层
            // 走 NEEDS_PASSWORD 让用户手输。这在 Linux 上是常态路径，不是新故障
            log.debug("读取凭据失败（将退回到请用户输入密码）：key={} 原因={}", key, got.error());
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

    /**
     * 拼出在钥匙串界面里给人看的标签。
     *
     * <p>用户名放在标签里而不是属性里，理由见类注释「用户名存在哪里」。</p>
     */
    static String buildLabel(String key, String username) {
        if (username == null || username.isBlank()) {
            return key;
        }
        return key + "（" + username + "）";
    }
}
