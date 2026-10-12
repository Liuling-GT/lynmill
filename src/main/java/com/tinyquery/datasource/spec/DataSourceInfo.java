package com.tinyquery.datasource.spec;

import java.util.Map;

/**
 * 对外暴露的数据源视图（已按 sensitive 脱敏）。
 * 与 DataSourceConfig 分开是有意为之：避免脱敏后的对象被误当成合法配置再次注册。
 * 脱敏在服务端完成，前端只负责展示，仅靠前端掩码等于没有防护
 *
 * @param properties 配置项敏感字段的值已被替换为掩码
 * @param status     该数据源当前是否可用。见 {@link LoadStatus}
 * @param failureReason 不可用的原因（人话），可用时为空串。
 *                      为什么要在服务端给出：前端拿不到加载时的异常，
 *                      只回一句「失败」用户无从下手
 * @param registeredAt 注册时间（epoch 毫秒），0 表示未知（老文件没存）。
 *                     前端据此挑出「最新注册的那个」作为默认 SQL
 */
public record DataSourceInfo(String name,
                             String type,
                             Map<String, Object> properties,
                             LoadStatus status,
                             String failureReason,
                             long registeredAt) {

    // 敏感字段的掩码占位符
    public static final String MASK = "******";

    public DataSourceInfo {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        status = status == null ? LoadStatus.OK : status;
        failureReason = failureReason == null ? "" : failureReason;
    }

    /** 已加载可用的数据源。 */
    public static DataSourceInfo ok(String name, String type, Map<String, Object> properties) {
        return new DataSourceInfo(name, type, properties, LoadStatus.OK, "", 0L);
    }

    /** 已加载可用的数据源，带注册时间。 */
    public static DataSourceInfo ok(String name, String type, Map<String, Object> properties,
                                    long registeredAt) {
        return new DataSourceInfo(name, type, properties, LoadStatus.OK, "", registeredAt);
    }

    /** 不可用的数据源，附状态与原因。 */
    public static DataSourceInfo unavailable(String name, String type,
                                             Map<String, Object> properties,
                                             LoadStatus status, String failureReason) {
        return new DataSourceInfo(name, type, properties, status, failureReason, 0L);
    }

    /** 不可用的数据源，附状态、原因与注册时间。 */
    public static DataSourceInfo unavailable(String name, String type,
                                             Map<String, Object> properties,
                                             LoadStatus status, String failureReason,
                                             long registeredAt) {
        return new DataSourceInfo(name, type, properties, status, failureReason, registeredAt);
    }
}
