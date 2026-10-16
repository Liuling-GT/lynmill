package com.tinyquery.parser;

import com.tinyquery.parser.lex.Tokenizer;

/**
 * SQL 解析入口 —— 对外契约的**唯一**保持者。
 *
 * <p><b>1-2 后的结构</b>：本类不再自己切分字符串，而是把三件事串起来：</p>
 * <ol>
 *   <li>{@link Tokenizer} —— 分词，唯一的字符级组件</li>
 *   <li>{@link Parser} —— 切子句，只认 token</li>
 *   <li>{@link SemanticAnalyzer} —— 语义校验</li>
 * </ol>
 *
 * <p>对外签名 {@code QueryStatement parse(String)} <b>一字未变</b>，
 * 调用方（{@code QueryController} / {@code QueryEngine}）无需改动。</p>
 *
 * <h2>为什么保留这个类而不是让调用方直接用 Parser</h2>
 * <p>三层各有各的输入输出，串起来还有顺序要求（必须先词法再语法再语义）。
 * 让每个调用方各写一遍这三步，等于把「正确的顺序」复制到多处 ——
 * 重构前 {@code SQLParser} 一个类做了三件事固然不对，
 * 但把编排责任推给调用方是另一个极端。这里保留一个薄编排层。</p>
 */
public class SQLParser {

    /**
     * 解析一条 SQL。
     *
     * @throws IllegalArgumentException 词法、语法或语义错误
     */
    public QueryStatement parse(String sql) {
        // 1) 词法 —— 未闭合引号在这里报错
        // 2) 语法 —— 切出各级子句
        Parser.Parsed parsed = Parser.parse(sql);

        // 3) 语义 —— 校验并组装对外契约
        return SemanticAnalyzer.analyze(parsed);
    }
}
