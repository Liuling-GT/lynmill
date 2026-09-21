package com.tinyquery.datasource;

import com.tinyquery.datasource.spec.*;
import com.tinyquery.executor.ColumnStore;

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

    private final Map<String, DataLoader> loaders;
    private final Map<String, DataSourceEntry> entries = new ConcurrentHashMap<>();

    public DataSourceManager(List<DataLoader> loaderList) {
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
        ColumnStore store = loader.load(config);

        DataSourceEntry entry = new DataSourceEntry(config, store);
        if (entries.putIfAbsent(config.name(), entry) != null) {
            // 并发下另一个线程先注册成功，本次加载结果丢弃
            throw new DataSourceException("数据源已存在：" + config.name());
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
            result.add(toInfo(entry));
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
        if (entries.remove(name) == null) {
            throw new DataSourceException("数据源不存在：" + name);
        }
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
    private DataSourceInfo toInfo(DataSourceEntry entry) {
        DataSourceConfig config = entry.config();
        DataLoader loader = loaders.get(config.type());

        Set<String> sensitiveKeys = new HashSet<>();
        if (loader == null) {
            // 理论上不会发生；真发生了就全量脱敏，宁可多掩不可泄漏
            sensitiveKeys.addAll(config.properties().keySet());
        } else {
            for (FieldSpec field : loader.fields()) {
                if (field.sensitive()) {
                    sensitiveKeys.add(field.name());
                }
            }
        }

        Map<String, Object> safe = new LinkedHashMap<>();
        config.properties().forEach((key, value) ->
                safe.put(key, sensitiveKeys.contains(key) ? DataSourceInfo.MASK : value));

        return new DataSourceInfo(config.name(), config.type(), Map.copyOf(safe));
    }
}
