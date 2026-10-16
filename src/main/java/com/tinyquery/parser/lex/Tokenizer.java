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
            //
            // F3：先判「这个位置只可能放列名」，是则一律 IDENTIFIER。
            // 否则 `where order = 1` 会被当成 order by 的 order，
            // `where limit = 1` 被当成 limit 子句 —— 而那张表里 order / limit
            // 确实就是列名。判据见 isOperandPosition。
            //
            // from 必须排除在外：`select a, from t` 里 from 前一个 token 是逗号，
            // 但它是子句关键字，得交给下面的 from 分支去判（那里有 isColumnNamedFrom）。
            // 让逗号规则抢先生效会把 from 吃掉，报错从「列名为空」
            // 变成「缺少 FROM 关键字」，指错了方向。
            TokenType decided;
            if (isAlwaysKeyword(lower)) {
                // not / as 永远是关键字，不给「操作数位置」规则抢走的机会。
                // not：否则 `where a > 1 and not b = 2` 里的 not 会被 and 的规则判成列名
                // as ：否则 `select a, as` 这种残缺写法会被当成列名，报错方向全错
                decided = TokenType.KEYWORD;
            } else if (lower.equals("from") && !inSelectList && isOperandPosition(tokens)) {
                // `order by from` / `where from = 1` —— from 出现在操作数位置时是列名。
                //
                // `!inSelectList` 这个条件是必需的：`select a, from t` 里
                // from 前一个 token 是逗号，也在「操作数位置」，但它是子句关键字。
                // 列名段里的 from 归 isColumnNamedFrom 判（那里会往后看有没有第二个 from），
                // 不能在这里抢走 —— 否则报错从「列名为空」变成「缺少 FROM 关键字」。
                decided = TokenType.IDENTIFIER;
            } else if (seenSelect && !lower.equals("from") && isOperandPosition(tokens)) {
                decided = TokenType.IDENTIFIER;
            } else if (lower.equals("select") && !seenSelect) {
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
            } else if (lower.equals("in") || lower.equals("between")) {
                // L2：in / between 在 where 里是运算符关键字（Parser 会展开成 = / or / and）。
                // 限定 seenWhere 是因为它们也可能是列名（关键字列名表里就有），
                // 只有进了 where 段才按运算符看待
                decided = (seenWhere && !inSelectList) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
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
     * 当前位置是不是「只能放操作数」的位置 —— 是则该裸词必然是列名，不可能是关键字。
     *
     * <p>这是 F3 的核心。实测里存在列名就叫 {@code order} / {@code limit} /
     * {@code and} 的表（见 {@code 07-对拍测试} 的「对拍关键字列名表」），
     * 而 {@code where order = 1} 与 {@code order by x} 都以 {@code order} 开头，
     * 光看这个词本身分不出来 —— 只能看<b>前一个 token</b>。</p>
     *
     * <h2>触发条件（前一个 token 是什么）</h2>
     * <ul>
     *   <li>{@code where} —— 紧接着就是要比较的列名：{@code where limit = 1}</li>
     *   <li>比较运算符 —— 右操作数：{@code a = limit}</li>
     *   <li>{@code by} —— 排序键：{@code order by limit}</li>
     *   <li>{@code and} / {@code or} —— 逻辑连接词之后是新的条件，
     *       起头的是列名：{@code where a = 1 and limit = 2}</li>
     * </ul>
     *
     * <h2>为什么 {@code not} 不在触发集里，但 {@code where} 在</h2>
     * <p>{@code where not a = 1} 里的 {@code not} 是<b>逻辑非</b>，必须保持 KEYWORD ——
     * 把它判成列名会让整个表达式降级成一个普通比较
     * （实测：{@code NotCondition} 变成 {@code SimpleCondition[field=not a]}）。</p>
     *
     * <p>但 {@code where} 后面接的<b>只能</b>是两种情况：一个列名开头，
     * 或一个 {@code not}。{@code not} 已经被 {@link #isAlwaysKeyword} 提前拦掉了，
     * 走不到这里 —— 所以凡是能走到 {@code where} 分支的，必然是列名。</p>
     *
     * <p>代价是 {@code where not = 1}（列名叫 not）修不好。但这个取舍是明确的：
     * {@code not} 作逻辑非是<b>标准 SQL 且常用</b>，作列名是极端边缘情况。
     * 宁可让后者报错，也不能让前者算错 —— 算错比报错危险得多。</p>
     *
     * <h2>为什么其余几条不会误伤</h2>
     * <p>关键字出现在这些位置时，语句本身就是错的，此前的行为也是报错
     * （比如 {@code where order by = 1} 本来就没人这么写）。
     * 把它们判成列名，最多是把「语法错误」变成「列不存在」，
     * 而后者对用户更有信息量 —— 列名写错时能直接看出是列名的问题。</p>
     */
    private static boolean isOperandPosition(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return false;
        }
        Token prev = tokens.get(tokens.size() - 1);
        switch (prev.type()) {
            case OPERATOR:              // a = <这里>
                return true;
            case KEYWORD:
                // where / and / or / by 之后起头的是列名。
                // not 由 isAlwaysKeyword 提前拦掉，所以这里不必排除它
                return prev.isKeyword("where")
                        || prev.isKeyword("and")
                        || prev.isKeyword("or")
                        || prev.isKeyword("by");
            case COMMA:
                // select a, <这里> —— 列清单里续写的也是列名
                return true;
            default:
                return false;
        }
    }

    /**
     * 这个词本身是不是「逻辑非」性质的词 —— 排在操作数位置的判断之前。
     *
     * <p>{@code not} 有两重身份：多数时候是逻辑非（{@code where not a = 1}），
     * 少数时候是列名（{@code where not = 1}）。{@code and} / {@code or} 之后的
     * {@code not} 属于前者 —— {@code where a > 1 and not b = 2} 是标准写法，
     * 若因为前面是 {@code and} 就把它当列名，整条语句会以
     * {@code Unknown column: not b} 失败（这条是 ReadmeAuditTest 抓出来的，
     * 我第一版就踩了）。</p>
     *
     * <p>取舍同「为什么 not 不在触发集里」：{@code not} 作逻辑非是标准且常用的，
     * 作列名是极端边缘情况，宁可后者报错。</p>
     */
    private static boolean isAlwaysKeyword(String lower) {
        return lower.equals("not") || lower.equals("as");
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
                // order / limit 这类「也可能是列名」的词：只有后面跟着 by / 数字时
                // 才说明它是子句开头。`select * from k order by from` 里，
                // 扫描若不在 order 处停下，就会看见 by 后面那个 from，
                // 把真正的子句 from 误判成列名。
                if (w.equalsIgnoreCase("order") && nextWordIs("by", i)) {
                    return false;
                }
                if ((w.equalsIgnoreCase("limit") || w.equalsIgnoreCase("offset"))
                        && nextNonSpaceIsDigit(i)) {
                    return false;
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

    /** 跳过空白后，从 {@code from} 开始的下一个词是不是 {@code word}。 */
    private boolean nextWordIs(String word, int from) {
        int i = from;
        while (i < length && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < length && !Character.isWhitespace(sql.charAt(i))
                && sql.charAt(i) != ',' && sql.charAt(i) != '(' && sql.charAt(i) != ')') {
            i++;
        }
        return sql.substring(start, i).equalsIgnoreCase(word);
    }

    /** 跳过空白后，从 {@code from} 开始的下一个字符是不是数字。 */
    private boolean nextNonSpaceIsDigit(int from) {
        int i = from;
        while (i < length && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i < length && Character.isDigit(sql.charAt(i));
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
     * <p><b>转义规则</b>：定界符自身成对出现表示一个字面定界符
     * （{@code `a``b`} → {@code a`b}，{@code "a""b"} → {@code a"b}）。
     * 与单引号字符串的规则一致。</p>
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
                index++;                            // 收尾定界符
                // 空名字直接报错，不要产出空 IDENTIFIER：
                // 一对裸定界符（`` 或 ""）会切出一个空 token，
                // 下游「列名为空」的检查会在别处报错，报错位置离病根很远。
                if (sb.length() == 0) {
                    throw ParserChecks.emptyIdentifier(quote);
                }
                return new Token(TokenType.IDENTIFIER, sb.toString(), start, true);
            }
            sb.append(c);
            index++;
        }
        throw ParserChecks.unterminatedDelimiter(quote);
    }

    /**
     * 尝试读 {@code [名称]} 形式的标识符，<b>支持 {@code ]]} 转义</b>。
     *
     * <p>方括号的转义规则与反引号/双引号不同：{@code ]]} 表示一个字面 {@code ]}
     * （SQL Server 习惯）。所以不能像原来那样直接 {@code indexOf(']')} ——
     * {@code [a]]b]} 表示列名 {@code a]b}，直接找第一个 {@code ]} 会从中间切断。</p>
     *
     * <p><b>只有「中间含空格」才退回普通字符</b>（那多半是数组字面量而非列名）。
     * 其余情况一律当作定界标识符消费掉整个 {@code [...]} —— 否则
     * {@code [} 会被 {@code readBareWord} 吃掉并粘到后面的词上，
     * 切出一个既不是列名也不是任何合法东西的 token，报错位置离病根很远。</p>
     *
     * <p>返回 null 的两类情形：没有配对的 {@code ]}（交给普通字符处理，
     * 让单引号/裸词的报错路径接手）、中间含空格。</p>
     */
    private Token tryReadBracketIdentifier(int start) {
        int i = start + 1;
        StringBuilder sb = new StringBuilder();
        while (i < length) {
            char c = sql.charAt(i);
            if (c == ']') {
                if (i + 1 < length && sql.charAt(i + 1) == ']') {
                    sb.append(']');                 // ]] → ]
                    i += 2;
                    continue;
                }
                String inner = sb.toString();
                if (inner.isEmpty()) {
                    throw ParserChecks.emptyIdentifier('[');
                }
                if (inner.chars().anyMatch(Character::isWhitespace)) {
                    return null;                    // 含空格：不是列名，退回普通字符
                }
                index = i + 1;
                return new Token(TokenType.IDENTIFIER, inner, start, true);
            }
            sb.append(c);
            i++;
        }
        return null;                                // 没有配对的 ]，不消费
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
