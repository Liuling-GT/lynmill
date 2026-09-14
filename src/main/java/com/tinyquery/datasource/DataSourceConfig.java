package com.tinyquery.datasource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 数据源配置
 * 统一用 properties 承载各数据源自己的参数，字段清单由 DataLoader#fields() 声明。
 * 因此新增数据源时本类无需改动
 * @param name       数据源名称，供 SQL 中 FROM 引用，不允许包含 '.'
 * @param type       数据源类型标识，与 DataLoader#type() 对应
 * @param properties 各数据源自己的配置项
 */
public record DataSourceConfig(String name, String type, Map<String, Object> properties) {

    public DataSourceConfig {
        if (name != null) {
            name = name.trim();
        }
        if (type != null) {
            type = type.trim().toLowerCase(Locale.ROOT);
        }
        properties = properties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    // 读取配置项，缺失或为 null 时返回 null
    public String getString(String key) {
        Object value = properties.get(key);
        if (value == null) {
            return null;
        }
        String str = String.valueOf(value).trim();
        return str.isEmpty() ? null : str;
    }

    // 读取必填配置项，缺失时抛业务异常
    public String requireString(String key) {
        String value = getString(key);
        if (value == null) {
            throw new DataSourceException("数据源 [" + name + "] 缺少必填配置项：" + key);
        }
        return value;
    }

    // 读取整数配置项，缺失时返回默认值，格式非法时抛业务异常
    public int getInt(String key, int defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new DataSourceException("数据源 [" + name + "] 配置项 " + key + " 不是合法整数：" + value);
        }
    }

    // 读取布尔配置项，缺失时返回默认值，格式非法时抛业务异常。
    // 前端用 el-switch 提交的是 JSON 布尔值，经 String.valueOf 后为 "true" / "false"
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new DataSourceException("数据源 [" + name + "] 配置项 " + key + " 不是合法布尔值：" + value);
    }
}