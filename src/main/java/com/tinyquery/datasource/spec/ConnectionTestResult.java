package com.tinyquery.datasource.spec;

/**
 * 连接可用性测试结果。
 * @param success 是否通过
 * @param message 结果说明。成功时描述探测到什么，失败时给出失败原因
 */
public record ConnectionTestResult(boolean success, String message) {

    // 测试通过
    public static ConnectionTestResult ok(String message) {
        return new ConnectionTestResult(true, message);
    }

    // 测试未通过
    public static ConnectionTestResult fail(String message) {
        return new ConnectionTestResult(false, message);
    }
}
