package com.tinyquery.credential;

/**
 * Linux 凭据存储原生调用的接缝。
 *
 * <p>与 {@link MacKeychainBackend} 同一个理由：让 {@link LinuxCredentialStore}
 * 的全部逻辑能用假实现驱动，从而在没有 D-Bus 会话的机器上（Windows 复核、CI、
 * 无桌面服务器）也能被测试盯住。</p>
 *
 * <p>这里没有 exists / modify / add 三件套。macOS 那边必须拆成三个动作，因为钥匙串
 * 条目带 ACL 分区列表，「先删后建」会把用户点过的「始终允许」丢掉，所以要把
 * 「更新已有条目必须原地改」这条规则做成可断言的形状。Linux 没有这个问题：
 * libsecret 的 {@code secret_password_storev_sync} 本身就是按 schema + 属性匹配的
 * upsert 语义，匹配到就原地更新，匹配不到才新建，所以不需要在 Java 这层再模拟
 * 一遍「先查再决定」，那样反而多一次往返、还引入竞态。因此 {@link #store} 是一个
 * 动作，但它是 upsert，不要误以为「重复保存会报错」而加一层删除。</p>
 *
 * <p>所有返回 {@code String} 的方法约定一致：成功返回 null，
 * 失败返回可直接展示给用户的中文原因。</p>
 */
interface LinuxKeychainBackend {

    /**
     * 原生层能否加载。
     *
     * <p>实现不得访问钥匙串 —— 只能确认 libsecret 与 GLib 加载得起来。
     * 原因是「判断可用性」会在启动时调用，若它也要真读写一次，
     * 在没有解锁钥匙串的机器上就会弹出解锁框。</p>
     *
     * @throws Throwable 加载失败时允许抛出（JNA 失败抛的是 Error）
     */
    boolean loadable();

    /** 读取密码。条目不存在返回 {@link SecretService.ReadOutcome#missing()}，不是失败 */
    SecretService.ReadOutcome read(String key);

    /**
     * 保存密码。是 upsert 语义：同 key 已存在则原地更新，不存在则新建。
     *
     * @param key      凭据标识（本项目用「TinyQuery/数据源名」）
     * @param label    在钥匙串界面里给人看的名字
     * @param password 密码
     * @return 成功返回 null，失败返回原因
     */
    String store(String key, String label, String password);

    /** 删除条目。本来就不存在也算成功，返回 null */
    String delete(String key);
}
