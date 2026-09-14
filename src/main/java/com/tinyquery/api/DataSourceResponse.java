package com.tinyquery.api;

/**
 * 数据源接口的统一响应结构。
 * @param code    0 表示成功，非 0 表示失败
 * @param message 提示信息
 * @param data    业务数据，失败时为 null
 * @param <T>     业务数据类型
 */
public record DataSourceResponse<T>(int code, String message, T data) {

    //成功响应
    public static <T> DataSourceResponse<T> success(T data) {
        return new DataSourceResponse<>(0, "success", data);
    }

    //失败响应
    public static <T> DataSourceResponse<T> failure(String message) {
        return new DataSourceResponse<>(1, message, null);
    }
}