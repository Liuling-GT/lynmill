package com.tinyquery.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Windows 平台的凭据存储实现（Windows 凭据管理器，底层是 DPAPI）。
 *
 * <p>通过 {@link WinCred} 调 advapi32 的 CredWrite/CredRead/CredDelete，
 * 不依赖 jna-platform。</p>
 *
 * <h2>可用性判断为什么是「双重」的</h2>
 *
 * <p>第一次先看操作系统名字，避免在 Linux/macOS 上白白尝试加载 advapi32；
 * 但如果名字判断通过、真正调用时仍然可能失败（系统策略禁用、服务未启动），
 * 因此 {@link #isAvailable()} 还会做一次真实的探测调用 —— 只有真能读写，
 * 才算可用。光看平台名字会让界面显示「可以保存密码」却在实际保存时报错。</p>
 */
@Component
@ConditionalOnProperty(name = "tinyquery.credential.store", havingValue = "windows",
        matchIfMissing = true)
public class WindowsCredentialStore implements CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(WindowsCredentialStore.class);

    /** 探测用的 key。写完立刻删掉，不留痕迹 */
    private static final String PROBE_KEY = "TinyQuery/__availability_probe__";

    /** 平台名字是否匹配。false 时连尝试都不必 */
    private final boolean platformMatches;

    /** 真实探测的缓存结果。null 表示还没探测过 */
    private volatile Boolean available;

    /** 探测失败的原因 */
    private volatile String reason = "";

    public WindowsCredentialStore() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        this.platformMatches = os.contains("windows");
        if (!platformMatches) {
            this.available = Boolean.FALSE;
            this.reason = "当前系统（" + System.getProperty("os.name") + "）暂不支持保存密码";
            log.info("凭据存储不可用：{}", reason);
        }
    }

    @Override
    public boolean isAvailable() {
        if (!platformMatches) {
            return false;
        }
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        return probe();
    }

    @Override
    public String unavailableReason() {
        isAvailable();     // 触发一次探测，保证 reason 有值
        return reason == null ? "" : reason;
    }

    /**
     * 真实探测一次：写一条、读一条、删掉。
     *
     * <p>只写不读不够 —— 有些环境下写入会「成功」但读取为空（策略只允许写不允许读）。
     * 只有三者都通过，才敢告诉用户「可以保存密码」。</p>
     */
    private synchronized boolean probe() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        try {
            String writeErr = WinCred.write(PROBE_KEY, "probe", "probe");
            if (writeErr != null) {
                available = Boolean.FALSE;
                reason = "无法访问系统凭据管理器：" + writeErr;
                log.warn("凭据存储探测失败（写入）：{}", writeErr);
                return false;
            }
            WinCred.ReadOutcome got = WinCred.read(PROBE_KEY);
            WinCred.delete(PROBE_KEY);      // 不管读成没成都清理掉

            if (got.found() && got.error().isEmpty()) {
                available = Boolean.TRUE;
                reason = "";
                log.info("系统凭据管理器可用，已启用「保存密码」");
                return true;
            }

            available = Boolean.FALSE;
            reason = got.error().isEmpty()
                    ? "系统凭据管理器写入后读不回内容，无法安全使用"
                    : "系统凭据管理器不可用：" + got.error();
            log.warn("凭据存储探测失败：{}", reason);
            return false;
        } catch (Throwable t) {
            // Throwable 而非 Exception：JNA 加载原生库失败时可能抛 UnsatisfiedLinkError，
            // 那是 Error 不是 Exception。凭据存不了绝不能让程序起不来
            available = Boolean.FALSE;
            reason = "系统凭据管理器不可用：" + t.getClass().getSimpleName();
            log.warn("凭据存储探测异常，将禁用「保存密码」", t);
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
            String err = WinCred.write(key, username, password);
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
            WinCred.ReadOutcome got = WinCred.read(key);
            if (got.found() && got.error().isEmpty()) {
                return CredentialResult.ok(got.password());
            }
            if (got.error().isEmpty()) {
                return CredentialResult.notFound();
            }
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
            String err = WinCred.delete(key);
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
