package com.tinyquery.datasource.spec;

/**
 * 数据源异常。
 *
 * <p>{@link #errorKey} 是一个稳定的错误码（对应 {@code Messages} 里的词条键），
 * 与展示文案分开。<b>调用方靠它分支，不要靠 message 内容分支</b> ——
 * message 会随语言变，用中文前缀判断的代码在英文模式下会静默失效。</p>
 *
 * <p>历史包袱：{@code QueryController} 曾经靠
 * {@code e.getMessage().startsWith("数据源不存在：")} 来判断要不要给「您可能想访问 X」
 * 的提示。加了 i18n 之后那种写法立刻失效，所以补上这个键。</p>
 */
public class DataSourceException extends RuntimeException {

    /** 错误码。无特定码时为 null，调用方按普通异常处理 */
    private final String errorKey;

    /** 错误码对应的参数，供 Messages.get 做占位符替换 */
    private final Object[] errorArgs;

    public DataSourceException(String message) {
        this(null, null, message, null);
    }

    public DataSourceException(String message, Throwable cause) {
        this(null, null, message, cause);
    }

    /**
     * 带错误码构造。
     *
     * @param errorKey  Messages 里的词条键，如 {@code error.datasourceNotFound}
     * @param errorArgs 占位符参数
     * @param message   已经按当前语言渲染好的文案，直接展示给用户
     */
    public DataSourceException(String errorKey, Object[] errorArgs, String message) {
        this(errorKey, errorArgs, message, null);
    }

    public DataSourceException(String errorKey, Object[] errorArgs, String message, Throwable cause) {
        super(message, cause);
        this.errorKey = errorKey;
        this.errorArgs = errorArgs == null ? new Object[0] : errorArgs.clone();
    }

    public String errorKey() {
        return errorKey;
    }

    public Object[] errorArgs() {
        return errorArgs.clone();
    }

    /** 是否为指定错误码。调用方用它替代 message 前缀判断 */
    public boolean is(String key) {
        return errorKey != null && errorKey.equals(key);
    }
}
