package com.tinyquery.credential;

/**
 * macOS 钥匙串原生调用的接缝。
 *
 * <h2>为什么要有这层接口</h2>
 *
 * <p>{@link WinCred} 是「只有静态方法的工具类」，于是 {@link WindowsCredentialStore}
 * 的逻辑没法用假实现替换 —— 想验「重复保存是覆盖」就只能上真机。macOS 这边不想
 * 重蹈覆辙，所以把原生调用抽出来。</p>
 *
 * <p>这样做最主要的好处是：<b>「write 必须是原地改、绝不能先删后建」这条规则
 * 可以在任何平台上被测试盯住</b>（包括 Windows 端复核时）。那条规则一旦破功，
 * 症状是「用户点了『始终允许』还是每次都弹窗」，在没有真机的 CI 上根本发现不了。</p>
 *
 * <p>拆成 exists / modify / add 三个动作而不是一个笼统的 write，正是为了让测试能
 * 断言「更新已有条目时调用的是 modify，且 delete 一次都没被调用」。</p>
 *
 * <h2>为什么方法只收 service、不收 account</h2>
 *
 * <p>与 {@link MacKeychain} 的说明一致：{@link CredentialStore#read} 只收 key，
 * 拿不到用户名，所以查找只能按 service。为了读写删三边对称（改了用户名之后
 * 仍然改得掉、删得掉），这里统一只按 service。account 只在 {@link #add} 时出现，
 * 因为它只是写进条目给人看的元数据。</p>
 *
 * <p>所有返回 {@code String} 的方法约定一致：<b>成功返回 null，失败返回可直接展示
 * 给用户的中文原因</b>（沿用了 {@link WinCred} 的约定）。</p>
 */
interface MacKeychainBackend {

    /**
     * 原生层能否加载。
     *
     * <p><b>实现不得访问钥匙串条目</b> —— 只能确认 Security.framework 加载得起来。
     * 原因是：macOS 上读钥匙串可能弹系统授权框，如果「判断可用性」也要读写一次，
     * 就变成每次启动弹一个框。</p>
     *
     * <p>放在这个接口上而不是让 {@code MacCredentialStore} 直接调
     * {@code MacKeychain}，是为了让 Store 的全部逻辑（包括不可用时的退化行为）
     * 都能用假实现驱动，从而在 Windows 上也能跑。</p>
     *
     * @throws Throwable 加载失败时允许抛出（JNA 失败抛的是 Error）
     */
    boolean loadable();

    /** 读取密码。条目不存在返回 {@link MacKeychain.ReadOutcome#missing()}，不是失败 */
    MacKeychain.ReadOutcome read(String service);

    /** 条目是否已存在 */
    boolean exists(String service);

    /** <b>原地</b>修改已存在条目的密码。成功返回 null */
    String modify(String service, String password);

    /** 新建条目。account 只在这里有意义 */
    String add(String service, String account, String password);

    /** 删除条目。本来就不存在也算成功，返回 null */
    String delete(String service);
}
