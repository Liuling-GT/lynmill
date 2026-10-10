package com.tinyquery.parser;

/**
 * 带提示的查询异常。
 *
 * <p>message 是真正的错误（红色显示），hint 是补充提示（紫色显示，可为 null）。
 * 两者分开而不是拼进同一个字符串，是因为前端要用不同颜色并排展示 ——
 * 拼在一起就只能整段一个颜色。</p>
 *
 * <p>典型场景：{@code select sum(salary) from t} 而表里的列叫 {@code Salary}。
 * message =「Unknown column:salary」，hint =「您可能想要访问 "Salary"」。</p>
 *
 * <p>继承 {@link IllegalArgumentException} 而不是 RuntimeException：查询层的既有代码
 * 与测试一律按 IllegalArgumentException 处理这类用户输入错误，换成平级类型会让
 * 所有既有 catch 失效 —— 多一句话提示不该改变异常的种类。</p>
 */
public class QueryHintException extends IllegalArgumentException {

    private final String hint;

    public QueryHintException(String message, String hint) {
        super(message);
        this.hint = hint;
    }

    /** 补充提示；没有时为 null */
    public String getHint() {
        return hint;
    }
}
