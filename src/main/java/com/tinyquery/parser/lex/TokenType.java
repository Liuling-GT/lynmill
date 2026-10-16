package com.tinyquery.parser.lex;

/**
 * 词法类型。
 *
 * <p>划分的依据是「Parser 需要用它们做哪些区分」，而不是「像不像教科书」。
 * 每多一个类型，Parser 里就多一个分支；所以只区分真正影响语法判断的类别。</p>
 */
public enum TokenType {

    /**
     * SQL 关键字，如 {@code select} / {@code from} / {@code order} / {@code and}。
     *
     * <p><b>这是本次重构的关键</b>：只有被判定为 KEYWORD 的词才参与语法切分。
     * 列名叫 {@code from_date}、字符串值是 {@code 'order by x'} 时都不是 KEYWORD，
     * 因此不会被误当成子句边界。</p>
     *
     * <p>注意：判定「是不是关键字」不靠一张白名单，而靠<b>位置和上下文</b> ——
     * 见 {@link Tokenizer} 的说明。原因是我们必须支持列名就叫 {@code from} 的表
     * （实测存在这种数据），白名单会把它们全部误伤。</p>
     */
    KEYWORD,

    /** 标识符：列名、表名。可能是裸写的，也可能被 {@code `} / {@code "} / {@code []} 包起来。 */
    IDENTIFIER,

    /**
     * 字符串字面量，单引号包裹。
     *
     * <p>{@code text} 是<b>已还原</b>的值：{@code 'it''s'} → {@code it's}。
     * 还原放在词法层，是因为「两个单引号表示一个单引号」是词法规则，
     * 不该让语法层知道。</p>
     */
    STRING,

    /**
     * 字符串之外的原始片段：数字、以及无法归类的杂项。
     *
     * <p>刻意不细分数字类型 —— 解析器只把它们当「一个字面值」搬运，
     * 真正的数值转换在 QueryEngine 里做。细分只会增加 Parser 的分支。</p>
     */
    RAW,

    /** 二元比较运算符：{@code >= <= != <> > < =} */
    OPERATOR,

    /** 逗号 */
    COMMA,

    /** 左括号 */
    LPAREN,

    /** 右括号 */
    RPAREN,

    /** 星号 {@code *}。单独一类，因为它既表示「所有列」又能出现在 {@code count(*)} 里。 */
    STAR,
}
