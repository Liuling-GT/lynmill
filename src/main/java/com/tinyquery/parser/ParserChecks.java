package com.tinyquery.parser;

import com.tinyquery.i18n.Messages;

/**
 * 解析器所有报错的**唯一出口**。
 *
 * <p><b>为什么要有这个类</b>：解析器正在分三层重构
 * （Lexer → Parser → SemanticAnalyzer）。重构前，{@code Messages.get("parse.*")}
 * 的调用散落在 {@link SQLParser} 的 558 行里，共 18 处，与切分逻辑交织在一起。
 * 结果有两个问题：</p>
 *
 * <ol>
 *   <li><b>想看「这个解析器会报哪些错」得通读全文</b>，没有清单。</li>
 *   <li><b>重构时要把校验挪进语义层，得先在一大堆逻辑里把它们一个个找出来</b>，
 *       漏一个就是静默的行为变化。</li>
 * </ol>
 *
 * <p>把报错集中到这里之后：报错清单就是本文件的方法列表；
 * 后续抽 SemanticAnalyzer 时只需要改这一处，而不是在 558 行里翻找。</p>
 *
 * <p><b>本次改动的边界</b>：<b>只搬家，不改行为</b>。
 * 每个方法的调用时机、抛错条件、报错文案与重构前逐字一致 ——
 * 这一点由 {@code ParserEquivalenceTest} 的 114 条行为基线钉住。</p>
 *
 * <p><b>这些方法为什么是静态的</b>：它们只做「拼文案 + 抛异常」，不带状态。
 * 做成实例方法会让人以为解析器有可配置的报错行为，实则没有。</p>
 *
 * <h2>方法命名与分组的由来</h2>
 * <p>本类不按「在哪个方法里被调用」分组，而按<b>报错的性质</b>分三组，
 * 因为重构后它们会去往不同的层：</p>
 * <ul>
 *   <li>{@code lexical*()} —— 词法层（将来归 Lexer）</li>
 *   <li>{@code syntax*()} —— 语法层（将来留在 Parser）</li>
 *   <li>{@code semantic*()} —— 语义层（将来归 SemanticAnalyzer）</li>
 * </ul>
 * <p>分组只是标注归属，<b>当前三者都被 {@link SQLParser} 调用</b>，
 * 没有任何层间依赖 —— 1-1 不做解耦，解耦是 1-2 的事。</p>
 *
 * <p><b>1-2 起本类改为 public</b>：抽出的 {@code lex.Tokenizer} 也需要它。
 * 没有给词法层另建一个报错类，是因为「解析器报啥错」应当是<b>唯一一份清单</b> ——
 * 拆成两个类，以后想列全部报错就得去两个地方找，又会退回重构前的老问题。</p>
 */
public final class ParserChecks {

    private ParserChecks() {
        // 纯工具类
    }

    // ==================== 词法层（将来归 Lexer） ====================

    /**
     * 单引号未闭合。
     *
     * <p>必须最先拦：未闭合时字面量会一路「吃掉」后面的内容，
     * 值是错的却不报错 —— {@code where name = 'abc} 会得到值 {@code 'abc}
     * （前引号没被剥掉），字符串比较永不相等，用户看到的是空表而不是错误提示。</p>
     */
    public static IllegalArgumentException unterminatedQuote() {
        return new IllegalArgumentException(Messages.get("parse.unterminatedQuote"));
    }

    /**
     * 定界标识符未闭合（{@code `x} / {@code "x} / {@code [x}）。
     *
     * <p>与单引号同样的道理，只是<b>报错要指明是哪种定界符</b>：
     * 原来一律报「单引号未闭合」，用户写的是 {@@code `} 却被告知单引号有问题，
     * 会往完全错误的方向排查。</p>
     *
     * @param quote 未闭合的定界符字符
     */
    public static IllegalArgumentException unterminatedDelimiter(char quote) {
        return new IllegalArgumentException(
                Messages.get("parse.unterminatedDelimiter", String.valueOf(quote)));
    }

    /**
     * 定界标识符内容为空（{@code ``} / {@code ""} / {@code []}）。
     *
     * <p>不产出空 IDENTIFIER：空名字没有任何合法含义，
     * 放过去只会让下游在离病根很远的地方报一个看不懂的错。</p>
     */
    public static IllegalArgumentException emptyIdentifier(char quote) {
        return new IllegalArgumentException(
                Messages.get("parse.emptyIdentifier", String.valueOf(quote)));
    }

    // ==================== 语法层（将来留在 Parser） ====================

    /** 整条语句里没有 select */
    public static IllegalArgumentException missingSelect() {
        return new IllegalArgumentException(Messages.get("parse.missingSelect"));
    }

    /** 整条语句里没有 from */
    public static IllegalArgumentException missingFrom() {
        return new IllegalArgumentException(Messages.get("parse.missingFrom"));
    }

    /** select 与 from 之间没有任何内容 */
    public static IllegalArgumentException noColumn() {
        return new IllegalArgumentException(Messages.get("parse.noColumn"));
    }

    /** 列清单里出现了空项，如 {@code select a, , b} */
    public static IllegalArgumentException emptyColumn() {
        return new IllegalArgumentException(Messages.get("parse.emptyColumn"));
    }

    /** from 之后没有表名 */
    public static IllegalArgumentException missingTable() {
        return new IllegalArgumentException(Messages.get("parse.missingTable"));
    }

    /** 条件里找不到任何受支持的运算符 */
    public static IllegalArgumentException unsupportedOperator(String expr) {
        return new IllegalArgumentException(
                Messages.get("parse.unsupportedOperator", expr)
                        + Messages.get("parse.supportedOperators"));
    }

    // ---------- L2：in / between 的写法错误 ----------
    // 这两个运算符本身已经支持（解析期展开成 = / or / and），
    // 下面报的是「写法没写对」，不是「不支持」。文案要能让用户看出差在哪。

    /** {@code between} 缺少中间的 {@code and} */
    public static IllegalArgumentException betweenNeedsAnd(String expr) {
        return new IllegalArgumentException(Messages.get("parse.betweenNeedsAnd", expr));
    }

    /** {@code in} 后面没有括号，或括号没配平 */
    public static IllegalArgumentException inNeedsParens(String expr) {
        return new IllegalArgumentException(Messages.get("parse.inNeedsParens", expr));
    }

    /** {@code in ()} 空列表 —— 没有合法语义，不展开成「永假」 */
    public static IllegalArgumentException inEmptyList(String expr) {
        return new IllegalArgumentException(Messages.get("parse.inEmptyList", expr));
    }

    // ---------- D1：列别名 ----------

    /** {@code as} 两侧缺东西：{@code as 城市} 或 {@code city as} */
    public static IllegalArgumentException aliasIncomplete(String expr) {
        return new IllegalArgumentException(Messages.get("parse.aliasIncomplete", expr));
    }

    /** 别名含空格：{@code city as 城 市} —— 多半是忘了加引号 */
    public static IllegalArgumentException aliasHasSpace(String alias) {
        return new IllegalArgumentException(Messages.get("parse.aliasHasSpace", alias));
    }

    /** 运算符左侧没有列名，如 {@code where = 1} */
    public static IllegalArgumentException missingColumnBeforeOp(String expr) {
        return new IllegalArgumentException(Messages.get("parse.missingColumnBeforeOp", expr));
    }

    /** 运算符右侧没有值，如 {@code where a =} */
    public static IllegalArgumentException missingValueAfterOp(String expr) {
        return new IllegalArgumentException(Messages.get("parse.missingValueAfterOp", expr));
    }

    // ==================== 语义层（将来归 SemanticAnalyzer） ====================

    /**
     * 剥离之后仍残留 limit / offset 关键字，说明写法不受支持。
     *
     * <p>不静默忽略 —— 否则表名会被污染成「t limit 3 offset 3」，
     * 用户看到的是「数据源不存在：t limit 3 offset 3」这种无从下手的报错。</p>
     */
    public static IllegalArgumentException limitOffsetUnsupported() {
        return new IllegalArgumentException(Messages.get("parse.limitOffsetUnsupported"));
    }

    /** where 后没有表达式 */
    public static IllegalArgumentException whereMissingExpr() {
        return new IllegalArgumentException(Messages.get("parse.whereMissingExpr"));
    }

    /**
     * MySQL 的 {@code limit 偏移,行数}。
     *
     * <p>参数顺序与标准写法相反，是最容易读错的一种。明确拒绝并指路，
     * 不静默接受 —— 静默接受会让用户以为写的是行数。</p>
     */
    public static IllegalArgumentException limitCommaUnsupported() {
        return new IllegalArgumentException(Messages.get("parse.limitCommaUnsupported"));
    }

    /** limit / offset 的数字超出 int 范围 */
    public static IllegalArgumentException numberOutOfRange(String clause, String raw) {
        return new IllegalArgumentException(Messages.get("parse.numberOutOfRange", clause, raw));
    }

    /** order by 的写法不受支持（含「出现了 order by 但匹配不上」） */
    public static IllegalArgumentException orderByUnsupported() {
        return new IllegalArgumentException(Messages.get("parse.orderByUnsupported"));
    }

    /**
     * order by 列表里出现空项。
     *
     * <p>早先逗号会留在列名里，用户看到的是「列 dept, 不存在」这种莫名其妙的报错。</p>
     */
    public static IllegalArgumentException orderByEmptyColumn() {
        return new IllegalArgumentException(Messages.get("parse.orderByEmptyColumn"));
    }

    /** order by 后面没跟列名 */
    public static IllegalArgumentException orderByMissingColumn() {
        return new IllegalArgumentException(Messages.get("parse.orderByMissingColumn"));
    }

    /**
     * 比较值未加引号却含空格。
     *
     * <p>多半是漏了引号。宁可报错，也不做一次静默的字符串比较。</p>
     */
    public static IllegalArgumentException valueHasSpace(String value) {
        return new IllegalArgumentException(Messages.get("parse.valueHasSpace", value));
    }
}
