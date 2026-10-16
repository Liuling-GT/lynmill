package com.tinyquery.parser.lex;

import com.tinyquery.parser.ParserChecks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 词法分析器 —— <b>整个解析流程里唯一接触字符的组件</b>。
 *
 * <p>输入 SQL 文本，输出 {@link Token} 列表。此后 Parser 与 SemanticAnalyzer
 * 都只认 token，不再有任何 {@code indexOf} / 正则 / 引号掩码。</p>
 *
 * <h2>为什么要抽这一层</h2>
 * <p>重构前同一件事有四种实现，只有两种做对了引号感知：</p>
 * <table border="1">
 *   <caption>关键字查找的四种实现</caption>
 *   <tr><th>关键字</th><th>实现</th><th>引号感知</th></tr>
 *   <tr><td>{@code where}</td><td>专用方法 {@code indexOfWhereKeyword}</td><td>✅</td></tr>
 *   <tr><td>{@code limit}/{@code offset}</td><td>三个锚定正则</td><td>✅</td></tr>
 *   <tr><td>{@code from}</td><td>裸 {@code indexOf}</td><td>❌</td></tr>
 *   <tr><td>{@code order by}</td><td>纯正则</td><td>❌</td></tr>
 * </table>
 * <p>没有任何机制保证新增关键字时不会漏做引号感知 —— 所以 D2/D4
 * 和「以后还会冒出来的同款」是必然，不是偶然。抽成一层后，
 * 引号处理只写一遍，所有关键字共享。</p>
 *
 * <h2>关键设计：词性由「位置」决定，不由白名单决定</h2>
 * <p>本分词器<b>不使用关键字白名单</b>。原因是实测数据里存在
 * 列名就叫 {@code from} / {@code where} / {@code order} 的表
 * （见 {@code 07-对拍测试} 的「对拍关键字列名表」）。
 * 用白名单会把 {@code select from from t} 里的第一个 {@code from}（列名）
 * 也判成关键字，直接误伤合法查询。</p>
 *
 * <p>改为按<b>出现位置</b>判定，规则如下：</p>
 * <ol>
 *   <li>{@code select} 之后、本层第一个 {@code from} 之前的裸词 —— 是列名（IDENTIFIER），
 *       哪怕它拼写就是 {@code from}。这正确处理了 {@code select from from t}。</li>
 *   <li>{@code from} 之后的裸词 —— 是表名（IDENTIFIER）。</li>
 *   <li>{@code where} 之后 —— 只把 {@code and} / {@code or} / {@code not} 判为关键字，
 *       其余裸词一律 IDENTIFIER。这正确处理了 D2 的
 *       {@code select city_from from t}（{@code city_from} 含 from 但是标识符）。</li>
 *   <li>{@code order} 后面紧跟的 {@code by}，以及 {@code order} 本身 —— 关键字。</li>
 * </ol>
 *
 * <p><b>位置由谁决定</b>：本层在扫描时维护一个「已确认子句序列」的小状态机。
 * 状态只依赖已经产出的 token，不依赖字符位置 —— 这正是旧实现的病根。</p>
 */
public final class Tokenizer {

    private final String sql;
    private final int length;
    private int index;

    /** 下一个待扫描字符在原始文本中的下标 */
    private Tokenizer(String sql) {
        this.sql = sql;
        this.length = sql.length();
    }

    /**
     * 把 SQL 切成 token 列表。
     *
     * @throws IllegalArgumentException 单引号未闭合时抛出
     *                                  （词法错误，报错文案与重构前逐字一致）
     */
    public static List<Token> tokenize(String sql) {
        return new Tokenizer(sql).run();
    }

    private List<Token> run() {
        List<Token> tokens = new ArrayList<>();
        // 子句位置状态：记录「走到哪了」，词性判定全靠它。
        // 这些字段只在扫描过程中用，不对外暴露 —— Parser 拿到的是纯 token 流
        boolean seenSelect = false;
        boolean seenFrom = false;
        boolean seenWhere = false;
        // select 与 from 之间的裸词是列名；此时即使拼作 from 也当列名（见类注释规则 1）
        boolean inSelectList = false;

        while (index < length) {
            char c = sql.charAt(index);

            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }

            int start = index;

            if (c == '\'') {
                tokens.add(readStringLiteral(start));
                continue;
            }
            if (c == '`') {
                tokens.add(readDelimited(start, '`'));
                continue;
            }
            if (c == '"') {
                tokens.add(readDelimited(start, '"'));
                continue;
            }
            if (c == '[') {
                // 方括号定界符：{@code [销售]}。只在『是 [名称]』时当作定界符，
                // 否则 {@code [1]} 这种会被误吞。判据见 tryReadBracketIdentifier
                Token br = tryReadBracketIdentifier(start);
                if (br != null) {
                    tokens.add(br);
                    continue;
                }
            }
            if (c == ',') {
                index++;
                tokens.add(new Token(TokenType.COMMA, ",", start));
                continue;
            }
            if (c == '(') {
                index++;
                tokens.add(new Token(TokenType.LPAREN, "(", start));
                continue;
            }
            if (c == ')') {
                index++;
                tokens.add(new Token(TokenType.RPAREN, ")", start));
                continue;
            }
            if (c == '*') {
                index++;
                tokens.add(new Token(TokenType.STAR, "*", start));
                continue;
            }

            // 比较运算符。双字符必须排在单字符之前，否则 ">=" 会被切成 ">" 再吃掉 "="
            String op = matchOperator();
            if (op != null) {
                tokens.add(new Token(TokenType.OPERATOR, op, start));
                continue;
            }

            // 裸词：关键字、标识符、数字或其他杂项，需要靠上下文定词性
            Token word = readBareWord(start);
            String lower = word.text().toLowerCase(Locale.ROOT);

            // ---- 按位置判定词性 ----
            TokenType decided;
            if (lower.equals("select") && !seenSelect) {
                decided = TokenType.KEYWORD;
                seenSelect = true;
                inSelectList = true;
            } else if (lower.equals("from") && seenSelect && !seenFrom) {
                // 列名叫 from 的情形：后面还有另一个 from，当前这个只是列名。
                // 不用状态计数是因为 `select a from t` 与 `select from from t`
                // 在读到这个 from 时状态完全相同，只能往后看（见 isColumnNamedFrom）。
                if (isColumnNamedFrom()) {
                    decided = TokenType.IDENTIFIER;
                } else {
                    decided = TokenType.KEYWORD;
                    seenFrom = true;
                    inSelectList = false;
                }
            } else if (lower.equals("where") && seenFrom && !seenWhere) {
                decided = TokenType.KEYWORD;
                seenWhere = true;
            } else if (lower.equals("and") || lower.equals("or") || lower.equals("not")) {
                // 连接词只在 where 之后有意义；select 列表里叫 and 的列名要保住
                decided = (seenWhere && !inSelectList) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
            } else if (lower.equals("order") || lower.equals("by")) {
                // order / by 只有在「已过 from、且不在列名段」时才是关键字
                decided = (seenFrom && !inSelectList) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
            } else if (lower.equals("limit") || lower.equals("offset")
                    || lower.equals("asc") || lower.equals("desc")) {
                decided = (seenFrom && !inSelectList) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
            } else {
                decided = TokenType.IDENTIFIER;
            }

            tokens.add(new Token(decided, word.text(), start));
        }

        return tokens;
    }

    /**
     * 判断当前位置（刚读完一个裸 {@code from}）是不是<b>列名</b>而不是子句关键字。
     *
     * <p>要区分两种情况，它们在读到这个 from 时状态完全一样：</p>
     * <ul>
     *   <li>{@code select a from t} —— 这个 from 是子句关键字</li>
     *   <li>{@code select from from t} —— 第一个 from 是<b>列名</b>，第二个才是关键字</li>
     * </ul>
     *
     * <p>判据：<b>只看列名段内</b>还有没有另一个裸 from。
     * 找到 → 当前这个是列名；找不到 → 它是子句关键字。</p>
     *
     * <h2>为什么必须限定在列名段内</h2>
     * <p>最初写成「往后再找一个 from」，结果
     * {@code select * from sales union select * from sales}
     * 里第一个 from 也被判成了列名 —— 它看见了 union 后面那个 from。
     * 但 union 一旦出现，select 列表就已经结束了，后面的 from 与当前这个无关。</p>
     *
     * <p>所以扫描时遇到下列任一情况立即停下，不再往后看：</p>
     * <ul>
     *   <li>括号：子查询里的 from 属于内层（{@code select * from (select a from t) x}）</li>
     *   <li>{@code union} / {@code select}：新的查询开始了</li>
     *   <li>{@code where} / {@code group} / {@code having} / {@code join} 等子句关键字：
     *       列名段到此为止</li>
     * </ul>
     */
    private boolean isColumnNamedFrom() {
        int i = index;
        int depth = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i++;
                while (i < length) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < length && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth == 0) {
                    return false;       // 出了当前层，列名段结束
                }
                depth--;
                i++;
                continue;
            }
            if (depth > 0) {
                i++;
                continue;
            }
            if (Character.isLetter(c)) {
                int wordStart = i;
                while (i < length && !Character.isWhitespace(sql.charAt(i))
                        && sql.charAt(i) != ',' && sql.charAt(i) != '(' && sql.charAt(i) != ')'
                        && sql.charAt(i) != '=' && sql.charAt(i) != '>'
                        && sql.charAt(i) != '<' && sql.charAt(i) != '*') {
                    i++;
                }
                String w = sql.substring(wordStart, i);
                if (w.equalsIgnoreCase("from")) {
                    return true;        // 列名段内还有 from → 当前这个是列名
                }
                if (isSelectListTerminator(w)) {
                    return false;       // 列名段结束，后面的 from 与当前无关
                }
                continue;
            }
            i++;
        }
        return false;
    }

    /**
     * 这个词是否意味着「select 列表已经结束」。
     *
     * <p>只列<b>一定会</b>终止列名段的词。像 {@code order} / {@code limit}
     * 这类本身也可能是列名的词不能列进来 —— 否则
     * {@code select order from t} 会被误判。</p>
     */
    private static boolean isSelectListTerminator(String w) {
        return w.equalsIgnoreCase("union")
                || w.equalsIgnoreCase("select")
                || w.equalsIgnoreCase("where")
                || w.equalsIgnoreCase("group")
                || w.equalsIgnoreCase("having")
                || w.equalsIgnoreCase("join")
                || w.equalsIgnoreCase("inner")
                || w.equalsIgnoreCase("left")
                || w.equalsIgnoreCase("right")
                || w.equalsIgnoreCase("on");
    }

    // ==================== 单引号字符串 ====================

    /**
     * 读单引号字面量，并把 {@code ''} 还原成 {@code '}。
     *
     * <p>未闭合时报错 —— 这是<b>必须</b>在词法层拦下的情况：未闭合时字面量
     * 会一路吃掉后面的内容，值是错的却不报错，用户看到的是空表而不是错误提示。</p>
     */
    private Token readStringLiteral(int start) {
        index++;                                    // 跳过开引号
        StringBuilder sb = new StringBuilder();
        while (index < length) {
            char c = sql.charAt(index);
            if (c == '\'') {
                if (index + 1 < length && sql.charAt(index + 1) == '\'') {
                    sb.append('\'');                // '' → '
                    index += 2;
                    continue;
                }
                index++;                            // 收尾引号
                return new Token(TokenType.STRING, sb.toString(), start);
            }
            sb.append(c);
            index++;
        }
        throw ParserChecks.unterminatedQuote();
    }

    // ==================== 定界标识符 ====================

    /**
     * 读 {@code `...`} 或 {@code "..."} 形式的定界标识符，<b>剥掉定界符</b>。
     *
     * <p>这就是 L3 的修复：引号是 SQL 的<b>定界符</b>，不是名字的一部分。
     * LynMill 里列名不可能真的带反引号（Excel 里一列叫 {@code 销售}，
     * 不会叫 {@code `销售`}），用户写 {@code from `销售`} 就是想引用 {@code 销售}。</p>
     *
     * <p>找不到收尾定界符时按未闭合处理 —— 与单引号同样的道理，
     * 静默吞掉会让后面的子句全部消失。</p>
     */
    private Token readDelimited(int start, char quote) {
        index++;                                    // 跳过开定界符
        StringBuilder sb = new StringBuilder();
        while (index < length) {
            char c = sql.charAt(index);
            if (c == quote) {
                // 定界符自身成对出现表示一个字面定界符（`a``b` → a`b）
                if (index + 1 < length && sql.charAt(index + 1) == quote) {
                    sb.append(quote);
                    index += 2;
                    continue;
                }
                index++;
                return new Token(TokenType.IDENTIFIER, sb.toString(), start, true);
            }
            sb.append(c);
            index++;
        }
        throw ParserChecks.unterminatedQuote();
    }

    /**
     * 尝试读 {@code [名称]} 形式的标识符。
     *
     * <p>返回 null 表示这里不是定界标识符（比如 {@code [1]} 或孤立的 {@code [}），
     * 由调用方按普通字符继续处理。判据是「存在配对的 ]，且中间不含空格」——
     * 太宽松会把数组字面量吞掉，太严格又认不出 {@code [销售]}。</p>
     */
    private Token tryReadBracketIdentifier(int start) {
        int close = sql.indexOf(']', start + 1);
        if (close < 0) {
            return null;
        }
        String inner = sql.substring(start + 1, close);
        if (inner.isEmpty() || inner.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        index = close + 1;
        return new Token(TokenType.IDENTIFIER, inner, start, true);
    }

    // ==================== 运算符 ====================

    /** 匹配比较运算符，长的优先（{@code >=} 不能切成 {@code >} + {@code =}） */
    private String matchOperator() {
        if (index + 1 < length) {
            String two = sql.substring(index, index + 2);
            if (two.equals(">=") || two.equals("<=") || two.equals("!=") || two.equals("<>")) {
                index += 2;
                return two;
            }
        }
        char c = sql.charAt(index);
        if (c == '>' || c == '<' || c == '=') {
            index++;
            return String.valueOf(c);
        }
        return null;
    }

    // ==================== 裸词 ====================

    /**
     * 读一个「裸词」：连续的、不属于上述任何类别的字符。
     *
     * <p>终止条件是<b>分隔符</b>（空白、逗号、括号、星号、比较运算符、各种引号）
     * 而不是「标识符字符集」。这是刻意放宽的：LynMill 要支持中文列名
     * （{@code 姓名}）、含点号的限定名、以及用户表头里各种奇怪的字符。
     * 用严格字符集会把它们切碎。</p>
     */
    private Token readBareWord(int start) {
        while (index < length) {
            char c = sql.charAt(index);
            if (Character.isWhitespace(c) || c == ',' || c == '(' || c == ')'
                    || c == '*' || c == '\'' || c == '`' || c == '"' || c == '['
                    || c == '>' || c == '<' || c == '=' || c == '!') {
                break;
            }
            index++;
        }
        if (index == start) {
            // 兜底：不该发生，但若某个字符既不匹配运算符也不在终止集里，
            // 至少消费掉它，避免死循环
            index++;
        }
        return new Token(TokenType.IDENTIFIER, sql.substring(start, index), start);
    }
}
