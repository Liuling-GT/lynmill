package com.tinyquery.credential;

/**
 * 凭据操作的结果。
 *
 * <p>用返回值而不是异常，是因为「凭据存不了」在本程序里属于可预期的正常分支：
 * Linux 上没装 keyring、Windows 策略禁用、密码超长 —— 这些都要让上层能平静地处理，
 * 而不是靠 catch 异常来控制流程。</p>
 *
 * @param success 操作是否成功
 * @param value   仅 {@link CredentialStore#read} 使用：成功时的密码值。
 *                密码不存在时为 {@code null}（注意这与失败不同）
 * @param message 失败原因（人话）；成功时为空串
 * @param missing 仅 {@link CredentialStore#read} 使用：凭据确实不存在。
 *                用来把「用户没存过」和「存储坏了」区分开 —— 前者是常态，后者才是问题
 */
public record CredentialResult(boolean success, String value, String message, boolean missing) {

    public CredentialResult {
        message = message == null ? "" : message;
    }

    /** 读取成功且密码存在 */
    public static CredentialResult ok(String value) {
        return new CredentialResult(true, value, "", false);
    }

    /** 读取成功，但该 key 下没有密码 —— 这不是错误 */
    public static CredentialResult notFound() {
        return new CredentialResult(true, null, "", true);
    }

    /** 操作失败 */
    public static CredentialResult fail(String message) {
        return new CredentialResult(false, null, message, false);
    }

    /** 写入/删除成功的简写（无返回值） */
    public static CredentialResult done() {
        return new CredentialResult(true, null, "", false);
    }
}
