package com.tinyquery.datasource.spec;

import java.util.Map;

/**
 * 对外暴露的数据源视图（已按 sensitive 脱敏）。
 * 与 DataSourceConfig 分开是有意为之：避免脱敏后的对象被误当成合法配置再次注册。
 * 脱敏在服务端完成，前端只负责展示，仅靠前端掩码等于没有防护
 * @param properties 配置项敏感字段的值已被替换为掩码
 */
public record DataSourceInfo(String name, String type, Map<String, Object> properties) {

    //敏感字段的掩码占位符
    public static final String MASK = "******";
}
