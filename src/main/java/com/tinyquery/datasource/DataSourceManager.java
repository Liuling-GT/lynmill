package com.tinyquery.datasource;

import com.tinyquery.credential.CredentialResult;
import com.tinyquery.credential.CredentialStore;
import com.tinyquery.datasource.spec.*;
import com.tinyquery.executor.ColumnStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DataSourceManager {

    private static final Logger log = LoggerFactory.getLogger(DataSourceManager.class);

    private final Map<String, DataLoader> loaders;
    private final Map<String, DataSourceEntry> entries = new ConcurrentHashMap<>();

    // 配置存在、但没能加载起来的数据源（缺密码 / 文件没了 / 连不上）。
    // 单独一张表而不是塞进 entries：entries 的语义是「可以查询的数据」，
    // 往里塞进没有 ColumnStore 的东西会逼着所有 get() 调用方判空。
    // 它们仍然要出现在 list() 里 —— 否则用户根本看不到自己的数据源去哪了
    private final Map<String, FailedEntry> failed = new ConcurrentHashMap<>();

    // 配置落盘。DataSourceManager 负责「剔除敏感字段」，Store 只管读写文件
    private final DataSourceStore store;

    // 系统凭据存储（保存密码用）。可能是「不可用」的实现，但一定存在 ——
    // 见 NoOpCredentialStore 的类注释：可选能力的缺失不能让程序起不来
    private final CredentialStore credentials;

    public DataSourceManager(List<DataLoader> loaderList, DataSourceStore store,
                             CredentialStore credentials) {
        this.store = store;
        this.credentials = credentials;
        Map<String, DataLoader> registered = new LinkedHashMap<>();
        for (DataLoader loader : loaderList) {
            DataLoader exists = registered.putIfAbsent(loader.type(), loader);
            if (exists != null) {
                // 启动即失败，避免运行期两个实现随机生效
                throw new IllegalStateException(
                        "数据源类型标识冲突：'" + loader.type() + "' 同时被 "
                                + exists.getClass().getName() + " 和 "
                                + loader.getClass().getName() + " 声明");
            }
        }
        this.loaders = Map.copyOf(registered);
    }

    // 注册数据源（按 type 分发到对应加载器，无任何 if/else 分支）
    public void register(DataSourceConfig config) {
        register(config, true, SecretAction.LEAVE);
    }

    /**
     * 注册数据源，并可选地把密码存进系统凭据管理器。
     *
     * @param savePassword 用户是否勾选了「保存密码」。
     *                     勾了但系统凭据存储不可用时，<b>注册仍然成功</b>，
     *                     只是密码没被保存 —— 不能因为存不了密码就拒绝注册
     */
    public void register(DataSourceConfig config, boolean savePassword) {
        register(config, true, savePassword ? SecretAction.SAVE : SecretAction.DELETE);
    }

    /**
     * 启动恢复专用：不落盘，<b>并且绝不动系统凭据</b>。
     *
     * <p>这个方法的存在是因为踩过一次严重的坑：起初恢复走的是
     * {@code register(config, false)}，而那个重载把「不落盘」和
     * 「用户没勾保存密码 → 清掉旧凭据」绑在了一起。结果每启动一次，
     * 程序就把用户的密码删一次 —— 用户勾了「保存密码」，重启后密码却没了，
     * 而且第一次启动还能连上（读取发生在删除之前），第二次才失败，
     * 表现极难复现和理解。</p>
     *
     * <p>真正的教训是：{@code persist} 与「怎么处理凭据」是两个正交的维度，
     * 用一个 boolean 同时表达必然出事。所以现在凭据动作由
     * {@link SecretAction} 显式给出，恢复流程明确使用 {@code LEAVE}。</p>
     *
     * <p>不落盘的理由（另一条独立的坑）：恢复过程中若有数据源加载失败，
     * 它不会进入 entries，此时落盘会把它从文件里抹掉，
     * 等于每重启一次用户的配置就少一条。</p>
     */
    void restoreWithoutPersist(DataSourceConfig config) {
        register(config, false, SecretAction.LEAVE);
    }

    /**
     * 注册时如何处理系统凭据。
     *
     * <p>用枚举而不是 boolean，是为了让调用点必须把意图写出来 ——
     * {@code register(cfg, false)} 这种写法读的人根本不知道那个 false 指什么。</p>
     */
    private enum SecretAction {
        /** 用户勾了保存密码：写入（或覆盖）凭据 */
        SAVE,
        /** 用户明确没勾：清掉可能存在的旧凭据，让「取消勾选」真的有作用 */
        DELETE,
        /** 不涉及用户意愿的流程（启动恢复）：原样保留凭据，绝不删 */
        LEAVE
    }

    private void register(DataSourceConfig config, boolean persist, SecretAction secretAction) {
        if (config.name() == null || config.name().isBlank()) {
            throw new DataSourceException("数据源名称不能为空");
        }
        // 名称不允许带 '.'，否则 TableName 解析时无法区分「后缀」与「名字本身」
        if (config.name().contains(".")) {
            throw new DataSourceException("数据源名称不能包含 '.'：" + config.name());
        }

        DataLoader loader = findLoader(config.type());

        // 快速失败：先查一次给出清晰报错；真正防并发的是下面的 putIfAbsent
        if (entries.containsKey(config.name())) {
            throw new DataSourceException("数据源已存在：" + config.name());
        }

        // 加载失败会抛 DataSourceException，不会走到写入
        ColumnStore loaded = loader.load(config);

        DataSourceEntry entry = new DataSourceEntry(config, loaded);
        if (entries.putIfAbsent(config.name(), entry) != null) {
            // 并发下另一个线程先注册成功，本次加载结果丢弃
            throw new DataSourceException("数据源已存在：" + config.name());
        }

        // 凭据动作放在注册成功之后：加载都失败了，没有理由去动系统凭据。
        // 顺序反过来会留下「凭据存了、数据源没建成」的孤儿条目
        switch (secretAction) {
            case SAVE -> {
                if (saveSecrets(config)) {
                    log.info("已为用户保存数据源密码：{}", config.name());
                } else if (!credentials.isAvailable()) {
                    log.info("用户勾选了保存密码，但系统凭据存储不可用，本次未保存：{}",
                            credentials.unavailableReason());
                }
            }
            case DELETE -> {
                // 用户没勾「保存密码」，但之前可能勾过。此时要清掉旧凭据，
                // 否则「取消勾选」这个动作毫无效果，密码还留在系统里
                deleteSecrets(config.name());
            }
            case LEAVE -> {
                // 启动恢复。这里绝不能删 —— 删了就等于每次启动都丢密码
            }
        }

        if (persist) {
            persist();
        }
    }

    // ==================== 启动恢复 ====================

    /**
     * 启动时恢复一个已保存的数据源。
     *
     * <p>与 {@link #register} 的关键区别：<b>失败不抛异常</b>，
     * 而是把这个数据源记进 {@link #failed}，稍后由 {@link #list()} 带给界面。</p>
     *
     * <p>为什么必须这样：一个数据源失效（CSV 被删、数据库连不上、没存密码）
     * 绝不能让程序起不来。否则用户看到的是「双击没反应」，
     * 而且连能改配置的界面都进不去 —— 彻底没有出路。
     * 正确行为是：跳过它，照常启动，在列表里标出来让用户自己修。</p>
     *
     * <p>全程不落盘：恢复过程中若有条目失败，它不在 entries 里，
     * 此时存盘会把这条配置从文件里抹掉 —— 每重启一次用户的配置就少一条。</p>
     *
     * @return 成功为 true；失败为 false（已记入 failed）
     */
    boolean restore(DataSourceConfig config) {
        try {
            // 先补密码：落盘时敏感字段是被摘掉的，不补的话，
            // 「用户明明勾了保存密码」的数据源会以「缺密码」的姿态出现，
            // 用户会以为这个功能根本没生效
            DataSourceConfig filled = fillSecretsFromStore(config);
            restoreWithoutPersist(filled);
            failed.remove(config.name());
            return true;
        } catch (Exception e) {
            failed.put(config.name(), new FailedEntry(config, classify(config, e), describe(e)));
            return false;
        }
    }

    /**
     * 判断这次失败属于「缺密码」还是「真失败」。
     *
     * <p>判据是「有没有必填的敏感字段没填」。这仍然是最常见的情形：
     * 用户没勾「保存密码」，或勾了但系统凭据存储不可用，
     * 于是重启后配置里就没有密码。它并不是错误，只是等用户补上 ——
     * 区分开之后，界面才能给出对的提示，而不是让用户为一个
     * 「只是没填密码」的数据源去排查文件路径。</p>
     */
    private LoadStatus classify(DataSourceConfig config, Exception failure) {
        if (missingRequiredSecret(config) != null) {
            return LoadStatus.NEEDS_PASSWORD;
        }
        return LoadStatus.FAILED;
    }

    /** 返回第一个「必填、敏感、但没有值」的字段名；没有则返回 null。 */
    private String missingRequiredSecret(DataSourceConfig config) {
        DataLoader loader = loaders.get(config.type());
        if (loader == null) {
            return null;
        }
        for (FieldSpec field : loader.fields()) {
            if (field.sensitive() && field.required() && config.getString(field.name()) == null) {
                return field.name();
            }
        }
        return null;
    }

    // 取出可直接展示给用户的原因。异常信息通常已经是人话（各种 loader 都写得比较清楚），
    // 但为空时要有兜底，不能给界面一个空字符串
    private static String describe(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message;
    }

    /**
     * 把一个数据源从「不可用」重新拉起来（用户补了密码、修好了文件之后）。
     *
     * <p>复用已保存的配置，用传入的补充字段覆盖后再加载 ——
     * 用户只需要提供缺的那一项，不必把整张表单重填一遍。</p>
     *
     * @param name           数据源名
     * @param extraProperties 用户补充的配置项（如密码），可为空
     */
    public void retry(String name, Map<String, Object> extraProperties) {
        FailedEntry entry = failed.get(name);
        if (entry == null) {
            throw new DataSourceException("数据源不存在或本就可用：" + name);
        }

        Map<String, Object> merged = new LinkedHashMap<>(entry.config().properties());
        if (extraProperties != null) {
            merged.putAll(extraProperties);
        }
        DataSourceConfig config = new DataSourceConfig(name, entry.config().type(), merged);

        // 加载失败会抛异常，此时 failed 里那条原样保留，用户还能再试一次
        ColumnStore loaded = findLoader(config.type()).load(config);

        entries.put(name, new DataSourceEntry(config, loaded));
        failed.remove(name);

        // 补上的字段（例如密码）不进文件：当前版本不保存密码，
        // withoutSecrets 会把它摘掉，这里存盘只是为了让配置的时间戳/内容保持最新
        persist();
    }

    /**
     * 该数据源是否处于「配置在但没加载起来」的状态。
     */
    public boolean isUnavailable(String name) {
        return failed.containsKey(name);
    }

    // ==================== 凭据（保存密码） ====================

    /**
     * 凭据在系统凭据管理器里的标识。
     *
     * <p>加前缀是为了让用户打开「凭据管理器」时能一眼认出这些条目属于谁，
     * 而且万一将来要清理，也能按前缀批量识别，不会误删别的软件凭据。</p>
     */
    static String credentialKey(String dataSourceName) {
        return "TinyQuery/" + dataSourceName;
    }

    /** 系统凭据存储是否可用。界面据此决定「保存密码」勾选框是否可点 */
    public boolean isCredentialStorageAvailable() {
        return credentials.isAvailable();
    }

    /** 凭据存储不可用的原因（人话）；可用时为空串 */
    public String credentialStorageUnavailableReason() {
        return credentials.unavailableReason();
    }

    /**
     * 把配置里被摘掉的敏感字段，用系统凭据存储里保存的值补回来。
     *
     * <p>启动恢复时必须先做这一步：落盘时密码是被摘掉的，不补回来的话，
     * 一个「用户明明勾了保存密码」的数据源会以「缺密码」的姿态出现 ——
     * 用户会觉得保存密码这个功能根本没生效。</p>
     *
     * <p>只补「该 loader 声明为敏感、且当前没有值」的字段。已经有值的不动，
     * 避免把用户本次输入的内容覆盖掉。</p>
     *
     * @return 补全后的配置；没有可补的或存储不可用时，原样返回
     */
    DataSourceConfig fillSecretsFromStore(DataSourceConfig config) {
        if (!credentials.isAvailable()) {
            return config;
        }

        DataLoader loader = loaders.get(config.type());
        if (loader == null) {
            return config;
        }

        CredentialResult stored = credentials.read(credentialKey(config.name()));
        if (!stored.success() || stored.missing() || stored.value() == null) {
            // 没存过是常态，不值得记警告
            return config;
        }

        Map<String, Object> merged = new LinkedHashMap<>(config.properties());
        boolean changed = false;
        for (FieldSpec field : loader.fields()) {
            // 只填「敏感 + 必填 + 当前为空」的。填一个敏感但非必填的字段
            // 可能反而覆盖掉用户的本意
            if (field.sensitive() && field.required() && merged.get(field.name()) == null) {
                merged.put(field.name(), stored.value());
                changed = true;
            }
        }

        if (!changed) {
            return config;
        }
        return new DataSourceConfig(config.name(), config.type(), merged);
    }

    /**
     * 把配置里的敏感字段存进系统凭据存储。
     *
     * @return 成功返回 true；失败返回 false（已记日志，调用方据此提示用户）
     */
    private boolean saveSecrets(DataSourceConfig config) {
        DataLoader loader = loaders.get(config.type());
        if (loader == null) {
            return false;
        }

        // 取第一个敏感字段的值。当前所有 loader 都只有一个敏感字段（password）；
        // 将来若有多个，这里要改成把多个值序列化进同一条凭据
        String secret = null;
        for (FieldSpec field : loader.fields()) {
            if (field.sensitive()) {
                Object value = config.properties().get(field.name());
                if (value != null && !String.valueOf(value).isEmpty()) {
                    secret = String.valueOf(value);
                }
                break;
            }
        }

        if (secret == null) {
            // 没填密码就没什么可存的。这不是错误
            return false;
        }

        CredentialResult r = credentials.write(
                credentialKey(config.name()),
                config.getString("username"),
                secret);

        if (!r.success()) {
            log.warn("保存密码失败：数据源={} 原因={}", config.name(), r.message());
            return false;
        }
        return true;
    }

    /** 删除数据源时，把它在凭据存储里的密码一并删掉。 */
    private void deleteSecrets(String name) {
        if (!credentials.isAvailable()) {
            return;
        }
        CredentialResult r = credentials.delete(credentialKey(name));
        if (!r.success()) {
            // 删不掉要留痕：否则用户以为密码没了，实际还在
            log.warn("删除数据源时未能删掉已保存的密码：数据源={} 原因={}", name, r.message());
        }
    }

    // 重新加载已注册的数据源。
    // 用途：CSV 文件被改写、MySQL 表数据变化后，让内存里的快照跟上。
    // 与原 register 的区别：允许覆盖同名数据源，但必须已经存在。
    public void refresh(String name) {
        DataSourceEntry existing = entries.get(name);
        if (existing == null) {
            throw new DataSourceException("数据源不存在：" + name);
        }

        DataSourceConfig config = existing.config();
        DataLoader loader = findLoader(config.type());

        // 加载失败会抛 DataSourceException，此时直接返回，
        // 旧数据保持不变 —— 刷新失败不应该把原有数据源弄坏
        ColumnStore store = loader.load(config);

        // 用新快照整体替换。entries 是 ConcurrentHashMap，单次 put 即原子，
        // 查询方要么看到旧数据，要么看到新数据，不会看到半新半旧
        entries.put(name, new DataSourceEntry(config, store));
    }

    // 测试数据源配置是否可用（不注册、不加载数据）。
    // 按 type 分发到对应加载器，Manager 不感知任何具体数据源类型
    public ConnectionTestResult test(DataSourceConfig config) {
        DataLoader loader = findLoader(config.type());
        return loader.test(config);
    }

    // 按精确名称获取数据，合法性已接受注册校验
    public ColumnStore get(String name) {
        return findEntry(name).store();
    }

    // 按 SQL 中的表名获取数据（表名到数据源的映射收在 Manager 内）
    public ColumnStore getByTableName(String rawTableName) {
        return get(resolveTableName(rawTableName));
    }

    // 列出所有数据源（已按 sensitive 脱敏，返回不可变副本）
    public List<DataSourceInfo> list() {
        List<DataSourceInfo> result = new ArrayList<>();
        for (DataSourceEntry entry : entries.values()) {
            result.add(toInfo(entry.config(), entry.config().properties(), LoadStatus.OK, ""));
        }
        // 加载失败/缺密码的也要列出来 —— 用户的数据源不能因为「暂时用不了」就凭空消失，
        // 否则他会以为自己的配置丢了
        for (FailedEntry failure : failed.values()) {
            String reason = failure.status() == LoadStatus.NEEDS_PASSWORD
                    ? "需要提供密码后才能使用"
                    : failure.reason();
            result.add(toInfo(failure.config(), failure.config().properties(),
                    failure.status(), reason));
        }

        // entries 是 ConcurrentHashMap，迭代顺序未定义，必须显式排序才能保证接口顺序稳定。
        // 先按类型分组、同类型内按名称排序，与 listTypes() 的口径保持一致
        result.sort(Comparator.comparing(DataSourceInfo::type)
                .thenComparing(DataSourceInfo::name));
        return List.copyOf(result);
    }

    // 列出支持的数据源类型及其配置项，供前端动态渲染表单
    public List<DataSourceTypeInfo> listTypes() {
        return loaders.values().stream()
                .map(loader -> new DataSourceTypeInfo(loader.type(), loader.label(), loader.fields()))
                .sorted(Comparator.comparing(DataSourceTypeInfo::type))
                .toList();
    }

    // 删除数据源，名称不存在时抛异常（与 get 的行为保持一致）
    public void remove(String name) {
        // 已加载的与「没加载起来」的都算存在 —— 否则用户删不掉一个失效的数据源，
        // 它会永远挂在列表里
        boolean removedLoaded = entries.remove(name) != null;
        boolean removedFailed = failed.remove(name) != null;

        if (!removedLoaded && !removedFailed) {
            throw new DataSourceException("数据源不存在：" + name);
        }

        // 连同系统凭据一起删掉。漏了这一步的后果很隐蔽：用户删了数据源、
        // 以为密码也没了，实际上密码还躺在凭据管理器里；等他新建一个同名数据源时，
        // 程序会把旧密码捞出来用 —— 他在一个自认为已经删掉的地方被读到了密码
        deleteSecrets(name);

        // 两种情形都要落盘。这里踩过一次坑：起初只给「删掉已加载的」落盘，
        // 理由是「失败的那条本来就不在文件里」—— 这个推理是错的。
        // 失败的数据源恰恰是从文件里恢复出来的，它当然在文件里；
        // 不落盘的话用户删掉它、重启，它又会冒出来。
        persist();
    }

    // ==================== 配置落盘 ====================

    /**
     * 把当前所有数据源的配置写入文件。
     *
     * <p><b>失败只记日志，不向上抛。</b>存盘是「尽力而为」的收尾动作：
     * 数据源已经注册进内存、用户已经能查询了，不能因为磁盘满了就把这次操作回滚 ——
     * 那只会让用户既用不上、又不知道发生了什么。写失败要说清楚后果（重启后会丢），
     * 由日志和后续的界面提示去交代。</p>
     */
    private void persist() {
        try {
            store.save(toPersistable());
        } catch (Exception e) {
            log.error("数据源配置存盘失败，本次改动在重启后会丢失", e);
        }
    }

    // 落盘前把每条配置的敏感字段摘掉
    private List<DataSourceConfig> toPersistable() {
        List<DataSourceConfig> result = new ArrayList<>();
        for (DataSourceEntry entry : entries.values()) {
            result.add(withoutSecrets(entry.config()));
        }
        // 「暂时用不了」的也要写回去。文件记录的是「用户配过哪些数据源」，
        // 不是「哪些现在能用」—— 少写它们就等于：用户删掉一个坏数据源时，
        // 另一个坏数据源会被顺手抹掉；或者某次存盘之后，
        // 之前加载失败的配置从此人间蒸发
        for (FailedEntry failure : failed.values()) {
            result.add(withoutSecrets(failure.config()));
        }
        // entries 是 ConcurrentHashMap，迭代顺序未定义；显式排序让文件内容稳定，
        // 否则每次存盘的行序都会变，看 diff 时全是噪声
        result.sort(Comparator.comparing(DataSourceConfig::name));
        return result;
    }

    /**
     * 做一份去掉敏感字段的配置副本，用于落盘。
     *
     * <p><b>密码一类的字段绝不写进文件。</b>当前版本不提供「保存密码」，
     * 因此落盘时必须把它们摘掉 —— 否则用户没勾任何选项，密码却已经躺在
     * {@code %APPDATA%\TinyQuery\datasources.json} 里了，而且没人知道。</p>
     *
     * <p>摘哪些字段由对应 loader 的 {@code fields()} 声明，与
     * {@link #toInfo} 的脱敏口径完全一致 —— 同一份声明，两处使用，
     * 不会出现「界面掩码了但文件里是明文」这种不一致。</p>
     */
    private DataSourceConfig withoutSecrets(DataSourceConfig config) {
        DataLoader loader = loaders.get(config.type());

        Set<String> sensitiveKeys = new HashSet<>();
        if (loader == null) {
            // 类型已无对应 loader（理论上不会发生，注册时就校验过）。
            // 认不出敏感字段时就全丢：宁可少存，不可泄露
            sensitiveKeys.addAll(config.properties().keySet());
        } else {
            for (FieldSpec field : loader.fields()) {
                if (field.sensitive()) {
                    sensitiveKeys.add(field.name());
                }
            }
        }

        Map<String, Object> safe = new LinkedHashMap<>();
        config.properties().forEach((key, value) -> {
            if (!sensitiveKeys.contains(key)) {
                safe.put(key, value);
            }
        });

        return new DataSourceConfig(config.name(), config.type(), safe);
    }

    // 表名解析：支持 "huge" 与 "huge.csv" 两种写法命中同一数据源。
    // 不判断具体后缀，未来 "users.mysql" 之类的写法也能自然生效。
    private String resolveTableName(String rawTableName) {
        if (rawTableName == null || rawTableName.isBlank()) {
            throw new DataSourceException("表名不能为空");
        }
        String trimmed = rawTableName.trim();

        // 原样命中
        if (entries.containsKey(trimmed)) {
            return trimmed;
        }

        // 去掉最后一段后缀再试
        int dotIndex = trimmed.lastIndexOf('.');
        if (dotIndex > 0) {
            String stripped = trimmed.substring(0, dotIndex);
            if (entries.containsKey(stripped)) {
                return stripped;
            }
        }

        // 都不命中，交给 get 抛出统一的「数据源不存在」
        return trimmed;
    }

    // 按 type 找加载器，找不到时报出当前支持的类型
    private DataLoader findLoader(String type) {
        DataLoader loader = loaders.get(type);
        if (loader == null) {
            throw new DataSourceException(
                    "不支持的数据源类型：" + type + "，当前支持：" + loaders.keySet());
        }
        return loader;
    }

    // 查找数据源，不存在时抛异常
    private DataSourceEntry findEntry(String name) {
        DataSourceEntry entry = entries.get(name);
        if (entry == null) {
            throw new DataSourceException("数据源不存在：" + name);
        }
        return entry;
    }

    // 构造脱敏视图：哪些 key 敏感由对应 loader 的 fields() 决定
    private DataSourceInfo toInfo(DataSourceConfig config,
                                  Map<String, Object> properties,
                                  LoadStatus status,
                                  String failureReason) {
        DataLoader loader = loaders.get(config.type());

        Set<String> sensitiveKeys = new HashSet<>();
        if (loader == null) {
            // 理论上不会发生；真发生了就全量脱敏，宁可多掩不可泄漏
            sensitiveKeys.addAll(properties.keySet());
        } else {
            for (FieldSpec field : loader.fields()) {
                if (field.sensitive()) {
                    sensitiveKeys.add(field.name());
                }
            }
        }

        Map<String, Object> safe = new LinkedHashMap<>();
        properties.forEach((key, value) ->
                safe.put(key, sensitiveKeys.contains(key) ? DataSourceInfo.MASK : value));

        return new DataSourceInfo(config.name(), config.type(), Map.copyOf(safe),
                status, failureReason);
    }

    /**
     * 「配置在、但没加载起来」的一条记录。
     *
     * @param config 原始配置（含服务端内存里的敏感字段，仅用于重试，不对外暴露）
     * @param status NEEDS_PASSWORD 或 FAILED
     * @param reason 失败原因（人话）
     */
    private record FailedEntry(DataSourceConfig config, LoadStatus status, String reason) {
    }
}
