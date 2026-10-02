package com.tinyquery.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「不用系统凭据存储」时的兜底实现：什么都不存。
 *
 * <p>存在的意义是保证 {@link CredentialStore} 这个 Bean <b>永远存在</b>。</p>
 *
 * <h2>为什么必须有它</h2>
 *
 * <p>如果不可用时干脆不注册 Bean，那么注入 {@code CredentialStore} 的
 * {@code DataSourceManager} 会因为找不到依赖而<b>启动失败</b> ——
 * 于是「Linux 上没装 keyring」这种完全正常的情况会让整个程序起不来。
 * 这与第 11 步的教训是同一条：<b>可选能力的缺失不能拖垮主流程。</b></p>
 *
 * <p>在这里，「不支持」的表现是：{@link #isAvailable()} 返回 false，
 * 界面把「保存密码」勾选框置灰并说明原因，其余功能一切照常。</p>
 *
 * <p>现在<b>所有</b>平台判断都收在 {@link CredentialStoreConfig} 里，
 * 本类只在下面几种情况下被选中：</p>
 * <ul>
 *   <li>{@code tinyquery.credential.store=none}（显式关闭）；</li>
 *   <li>平台既不是 Windows 也不是 macOS（例如 Linux）；</li>
 *   <li>配置值无法识别，或凭据存储初始化时抛了异常（兜底）。</li>
 * </ul>
 *
 * <p>本类不再自带 {@code @Component}：原因见
 * {@link WindowsCredentialStore} 类注释里「为什么不再自带 @Component」一节。</p>
 */
public class NoOpCredentialStore implements CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(NoOpCredentialStore.class);

    private final String reason;

    public NoOpCredentialStore() {
        this("本平台暂不支持保存密码");
        log.info("凭据存储已禁用（tinyquery.credential.store=none），「保存密码」将不可用");
    }

    /**
     * 指定不可用原因。
     *
     * <p>public 是为了让其它包的测试能构造它 —— 测试需要断言「存储不可用时
     * 程序仍然正常工作」，而默认原因在这个场景下不够具体。</p>
     */
    public NoOpCredentialStore(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public CredentialResult write(String key, String username, String password) {
        // 不静默假装成功 —— 必须明确告诉调用方「没存」，否则界面会以为存上了
        return CredentialResult.fail(reason);
    }

    @Override
    public CredentialResult read(String key) {
        // 返回「没有密码」而不是失败：这就是事实，上层走「请用户输入密码」的正常分支
        return CredentialResult.notFound();
    }

    @Override
    public CredentialResult delete(String key) {
        // 本来就没存过，删除是空操作且应当算成功
        return CredentialResult.done();
    }
}
