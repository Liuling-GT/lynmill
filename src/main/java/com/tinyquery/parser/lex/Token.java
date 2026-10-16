package com.tinyquery.parser.lex;

/**
 * 词法单元。
 *
 * <p><b>存在的理由</b>：重构前 {@link com.tinyquery.parser.SQLParser} 把 SQL 当纯字符串切，
 * 用 {@code indexOf} / 正则找关键字。这带来一类必然的缺陷：只要关键字出现在
 * 它不该出现的地方（列名里、字符串值里），切分就会错。</p>
 *
 * <p>实测过的三例：</p>
 * <ul>
 *   <li>{@code select city_from from t} → 表名被切成 {@code from t}，列名被截成 {@code city_}（D2）</li>
 *   <li>{@code where 备注 = 'order by x'} → 报「ORDER BY 写法不受支持」（D4）</li>
 *   <li>{@code select 城市 from `销售`} → 表名带着反引号去查数据源（L3）</li>
 * </ul>
 *
 * <p>有了 Token 之后，{@code from} 只在它<b>真的是关键字</b>时才参与语法判断，
 * 上面三种情况在新结构里无处容身 —— 不是打补丁修掉的。</p>
 *
 * @param type  词法类型
 * @param text  词的<b>语义</b>文本。定界符已剥掉：
 *              {@code `销售`} 的 text 是 {@code 销售}，{@code 'it''s'} 的 text 是 {@code it's}
 * @param pos   在原始 SQL 中的起始下标，仅用于报错定位
 * @param delimited 是否来自 {@code `...`} / {@code "..."} / {@code [...]} 定界形式。
 *              仅用于「拼回原文时算占用宽度」——语义上定界符已剥掉，不参与任何判断
 */
public record Token(TokenType type, String text, int pos, boolean delimited) {

    /** 裸词形式的便捷构造（绝大多数 token 都不是定界形式） */
    public Token(TokenType type, String text, int pos) {
        this(type, text, pos, false);
    }

    /** 该词是否为指定关键字（大小写不敏感）。仅对 {@link TokenType#KEYWORD} 有意义。 */
    public boolean isKeyword(String keyword) {
        return type == TokenType.KEYWORD && text.equalsIgnoreCase(keyword);
    }

    /** {@code *}，例如 {@code select *} 与 {@code count(*)} */
    public boolean isStar() {
        return type == TokenType.STAR;
    }

    @Override
    public String toString() {
        return type + "(" + text + ")@" + pos;
    }
}
