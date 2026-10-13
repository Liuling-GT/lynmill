package com.tinyquery.credential;

/**
 * 操作系统凭据存储的抽象。
 *
 * <p>「保存密码」这个功能的所有平台差异都被收在这个接口后面。
 * 将来加 macOS 钥匙串、Linux Secret Service 时，只需再写一个实现，
 * 上层（DataSourceManager、Controller、前端）一行都不用改。</p>
 *
 * <p>三个方法都必须满足的约定：不抛异常，实现类要把底层错误翻译成
 * {@link CredentialResult} 返回 —— 凭据存储是可选能力，它的任何故障都不该变成
 * 业务异常，最终用户看到「存密码失败」时程序必须还能正常用；{@link #read}
 * 找不到凭据时返回「成功但值为空」而不是失败，这是最常见的情形（用户从没勾过
 * 保存密码），把它当成错误会让正常流程看起来像故障；实现会被多个请求线程并发
 * 调用，必须可重入。</p>
 *
 * <p>本接口背后是操作系统提供的凭据存储。以 Windows 为例，底层是 DPAPI，
 * 密钥绑定在当前登录用户账户上 —— 也就是说，任何以该用户身份运行的程序都能解开
 * 这些密码。所以这个功能的真实价值是：密码不随 {@code datasources.json} 被备份、
 * 上传网盘、复制给别人；用户能在系统的凭据管理器里看得见、随时删得掉；截图配置
 * 文件求助时不会顺带泄露密码。它不是「密码从此安全了」，不要在对用户或文档的
 * 表述里夸大这一点。</p>
 */
public interface CredentialStore {

    /**
     * 当前平台的凭据存储是否可用。
     *
     * <p>界面要靠它决定「保存密码」勾选框是否可点。返回 false 时，
     * 它对应的实现里 {@code write} 会失败、{@code read} 会返回空，
     * 但程序其余部分必须完全正常。</p>
     */
    boolean isAvailable();

    /** 不可用的原因（人话，可直接展示给用户）；可用时返回空串。 */
    String unavailableReason();

    /**
     * 保存密码。
     *
     * <p>同一个 key 重复保存应当覆盖而不是报错 —— 用户改密码后重新注册是常规操作。</p>
     *
     * @param key      凭据标识，需全局唯一（本项目中用「LynMill/数据源名」）
     * @param username 一并保存的用户名；可为 null
     * @param password 要保存的密码；null 视为空串
     */
    CredentialResult write(String key, String username, String password);

    /**
     * 读取密码。
     *
     * @return 成功时 {@link CredentialResult#ok(String)}，密码不存在时值为 {@code null}；
     *         失败时 {@link CredentialResult#fail(String)}
     */
    CredentialResult read(String key);

    /**
     * 删除密码。
     *
     * <p>删除不存在的条目视为成功。删除的语义是「让这个 key 不再有密码」，
     * 它本来就没有，结果是一样的 —— 报错只会让「删数据源」这个动作莫名其妙地失败。</p>
     *
     * @return 成功（含本来就不存在）；失败时 {@link CredentialResult#fail(String)}
     */
    CredentialResult delete(String key);
}
