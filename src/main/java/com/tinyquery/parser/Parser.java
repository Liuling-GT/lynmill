package com.tinyquery.parser;

import com.tinyquery.parser.lex.Token;
import com.tinyquery.parser.lex.TokenType;
import com.tinyquery.parser.lex.Tokenizer;
import com.tinyquery.parser.where.AndCondition;
import com.tinyquery.parser.where.Condition;
import com.tinyquery.parser.where.NotCondition;
import com.tinyquery.parser.where.OrCondition;
import com.tinyquery.parser.where.SimpleCondition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语法分析器 —— <b>只认 token，永远不碰字符</b>。
 *
 * <p>本类不应出现 {@code indexOf}、{@code literalMask}、{@code Pattern} 这类字符级操作。
 * 所有「哪些字是引号内的」「哪个词是关键字」的判断，都由 {@link Tokenizer} 完成。</p>
 *
 * <h2>切分顺序与旧实现保持一致</h2>
 * <p>旧实现从语句<b>末尾往回剥</b>：先 limit → 再 order by → 再 where → 最后表名。
 * 这个顺序是敏感的（先摘掉 limit，order by 才落到末尾）。</p>
 * <p>换成 token 之后本可以让顺序无关，但那会改变一批边界语句的报错内容。
 * 本轮的靶子是 D2/D4/L3 三条，不是顺手改别的行为，所以<b>刻意沿用同一套顺序</b>，
 * 基线里除这三条外的条目才能逐字不变。</p>
 */
public final class Parser {

    /** 子句被切出来之后的中间产物，尚未做语义校验 */
    public record Parsed(
            String[] selectColumns,
            String[] aliases,
            boolean[] delimitedFlags,
            String tableName,
            Condition condition,
            SortSpecs sort,
            Integer limit,
            Integer offset) {
    }

    private final List<Token> tokens;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Parsed parse(String sql) {
        return new Parser(Tokenizer.tokenize(sql)).run();
    }

    // ==================== 主流程 ====================

    private Parsed run() {
        if (tokens.isEmpty()) {
            throw ParserChecks.missingSelect();
        }

        int selectIdx = indexOfKeyword("select");
        if (selectIdx < 0) {
            throw ParserChecks.missingSelect();
        }
        int fromIdx = indexOfKeyword("from");
        if (fromIdx < 0) {
            throw ParserChecks.missingFrom();
        }

        // 各子句的起点。用「语句范围」而不是「整个 token 流」来限定查找范围，
        // 否则 order by 里的逗号会被误认成 limit 的逗号形式
        int bodyEnd = tokens.size();

        // ---- 1) limit / offset：从末尾剥 ----
        LimitResult lim = takeLimitOffset(fromIdx + 1, bodyEnd);
        int afterLimit = lim.startIdx >= 0 ? lim.startIdx : bodyEnd;

        // ---- 2) order by：必须在 limit 之后剥，此时才落到末尾 ----
        OrderResult ord = takeOrderBy(fromIdx + 1, afterLimit);
        int afterOrder = ord.startIdx >= 0 ? ord.startIdx : afterLimit;

        // ---- 3) where：切点取「from 之后、order/limit 之前」 ----
        int whereIdx = indexOfKeyword(fromIdx + 1, afterOrder, "where");
        int beforeWhere = whereIdx >= 0 ? whereIdx : afterOrder;

        // ---- 4) 兜底闸：剥离后仍残留 limit/offset ----
        if (hasKeywordBetween(fromIdx + 1, afterLimit, "limit")
                || hasKeywordBetween(fromIdx + 1, afterLimit, "offset")) {
            throw ParserChecks.limitOffsetUnsupported();
        }

        // ---- 表名：from 之后到 where/order/limit 中最早的一个 ----
        String tableName = join(fromIdx + 1, beforeWhere);
        if (tableName.isEmpty()) {
            throw ParserChecks.missingTable();
        }

        // ---- 列清单：select 到 from ----
        SelectList cols = parseSelectList(selectIdx + 1, fromIdx);

        // ---- 条件：where 之后到 order/limit 中最早的一个 ----
        Condition condition = null;
        if (whereIdx >= 0) {
            if (whereIdx + 1 >= afterOrder) {
                throw ParserChecks.whereMissingExpr();
            }
            condition = parseCondition(whereIdx + 1, afterOrder);
        }

        return new Parsed(cols.columns(), cols.aliases(), cols.delimited(), tableName, condition,
                ord.specs, lim.limit, lim.offset);
    }

    // ==================== 列清单 ====================

    /** 列清单的解析结果：列表达式、别名、以及「是否定界标识符」分开保存 */
    private record SelectList(String[] columns, String[] aliases, boolean[] delimited) {
    }

    /**
     * 解析 SELECT 列表，并把 {@code as 别名} 拆出去。
     *
     * <p>D1：别名以前完全没实现 —— {@code select city as 城市 from t} 会把
     * {@code city as 城市} 整串当列名，报 {@code Unknown column: city as 城市}。
     * 现在拆成「列表达式 = city，别名 = 城市」两部分。</p>
     *
     * <h2>为什么要把别名拆出去，而不是留在表达式里</h2>
     * <p>{@code selectColumns} 是整条链路的公共契约：{@link QueryStatement#isAggregateExpr}
     * 按前缀识别聚合、{@code looksLikeColumnRef} 按引号识别常量、
     * {@code QueryEngine} 按它去数据源取列。别名混在里面会让这三处全部误判 ——
     * {@code sum(x) as 总} 会变成 {@code sum(x) as 总(} 开头的怪东西，聚合都识别不出来。</p>
     *
     * <p>拆开之后：<b>列表达式保持纯净</b>（既有逻辑一行不改），
     * 别名单独走一条线，只在「生成结果表头」时生效。</p>
     *
     * <h2>两种写法都支持</h2>
     * <ul>
     *   <li>{@code city as 城市} —— 显式 as</li>
     *   <li>{@code city 城市} —— 省略 as（标准 SQL 允许）</li>
     * </ul>
     *
     * <p>省略 as 的形态要小心：{@code select city 城市 from t} 里
     * 「city 城市」是两个 token，靠空格分隔。判据是「这一段有 2 个 token
     * 且第一个不是聚合/常量」。含空格的列名不在支持范围内 ——
     * 那种情况本来就是旧缺陷（现在也仍然只按第一个词取）。</p>
     */
    private SelectList parseSelectList(int from, int to) {
        if (from >= to) {
            throw ParserChecks.noColumn();
        }
        // 整个列清单就是单个 * 时保持旧行为
        if (to - from == 1 && tokens.get(from).isStar()) {
            return new SelectList(new String[]{"*"}, new String[]{null}, new boolean[]{false});
        }

        List<String> cols = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        List<Boolean> delimited = new ArrayList<>();
        int segStart = from;
        for (int i = from; i <= to; i++) {
            if (i == to || tokens.get(i).type() == TokenType.COMMA) {
                String col = join(segStart, i);
                if (col.isEmpty()) {
                    throw ParserChecks.emptyColumn();
                }
                String[] split = splitAlias(segStart, i, col);
                cols.add(split[0]);
                aliases.add(split[1]);
                // 整段就是「一个定界标识符」时才标记 —— 这一列的文本已经是最终列名，
                // 执行层不该再对它做 unquote（见 QueryStatement#delimitedColumnAt）
                delimited.add(i - segStart == 1 && tokens.get(segStart).delimited());
                segStart = i + 1;
            }
        }
        boolean[] flags = new boolean[delimited.size()];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = delimited.get(i);
        }
        return new SelectList(cols.toArray(new String[0]), aliases.toArray(new String[0]), flags);
    }

    /**
     * 把一段 SELECT 项拆成「列表达式, 别名」；没有别名时别名为 null。
     *
     * <p>返回长度为 2 的数组，{@code [0]} 是列表达式、{@code [1]} 是别名。</p>
     */
    private String[] splitAlias(int from, int to, String joined) {
        int span = to - from;
        if (span <= 1) {
            return new String[]{joined, null};       // 单 token，不可能含别名
        }

        // 显式 as：`city as 城市` / `sum(x) as 总`
        int asIdx = -1;
        for (int i = from; i < to; i++) {
            if (tokens.get(i).isKeyword("as")) {
                asIdx = i;
                break;
            }
        }
        if (asIdx >= 0) {
            if (asIdx == from || asIdx + 1 >= to) {
                // `as 城市`（缺表达式）或 `city as`（缺别名）
                throw ParserChecks.aliasIncomplete(joined);
            }
            if (asIdx + 2 < to) {
                // `city as 城 市` —— 别名含空格，多半是忘了引号
                throw ParserChecks.aliasHasSpace(join(asIdx + 1, to));
            }
            String expr = join(from, asIdx);
            String alias = renderAlias(tokens.get(asIdx + 1));
            return new String[]{expr, alias};
        }

        // 省略 as：`sum(x) 总额` / `amount * 2 双倍` —— 最后一个 token 是别名，
        // 前提是它前面那段「明显是个表达式」而不可能是列名的一部分。
        //
        // 【这里刻意收得很紧】第一版写成「两个 token 就按别名拆」，结果把
        // `select city_as from_x from t_from_x` 拆成了列 city_as + 别名 from_x，
        // 而 D2 刚刚才把「列名内部含 from」修好 —— 等于反手又打开一个口子。
        // `select distinct a from t` 同样被拆成列 distinct + 别名 a。
        //
        // 收紧后：只有前 n-1 段「不可能是一个完整列名的一部分」时才拆。
        // 裸标识符 + 裸标识符（`city 城市`）不满足这个条件 ——
        // 它在语法上与「含空格的列名」完全同形，无法可靠区分。
        // 那种写法请写显式 as：`city as 城市`。
        if (span >= 2 && tokens.get(to - 1).type() == TokenType.IDENTIFIER
                && !isPlainIdentifierSegment(tokens.get(from), join(from, to - 1))) {
            String expr = join(from, to - 1);
            // 括号必须配平才当别名。`select sum(salary from t` 这种缺右括号的写法
            // 里，最后一个词是聚合的字段名而不是别名 —— 拆出去会把
            // 「缺少右括号，表达式是 sum(salary」这条报错弄丢，
            // 用户看到的是别名相关的错，方向全错
            if (!expr.isEmpty() && parensBalanced(from, to - 1)) {
                return new String[]{expr, renderAlias(tokens.get(to - 1))};
            }
        }
        return new String[]{joined, null};
    }

    /** 区间内左右括号是否配平。 */
    private boolean parensBalanced(int from, int to) {
        int depth = 0;
        for (int i = from; i < to; i++) {
            TokenType t = tokens.get(i).type();
            if (t == TokenType.LPAREN) {
                depth++;
            } else if (t == TokenType.RPAREN) {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    /**
     * 这一段是不是「一个平平无奇的裸标识符」—— 是的话就不按别名拆。
     *
     * <p>判据：第一段是裸标识符，且拼回的原文里没有括号、星号、运算符、引号。</p>
     * <ul>
     *   <li>{@code city 城市} —— 第一段 city 平平无奇，整段也可能是「含空格的列名」，
     *       无法可靠区分 → <b>返回 true，不拆</b>，按列名处理（与 D2 的修复方向一致）</li>
     *   <li>{@code sum(x) 总额} —— 含括号 → <b>返回 false，拆</b></li>
     *   <li>{@code amount * 2 双倍} —— 含星号 → <b>返回 false，拆</b></li>
     * </ul>
     *
     * <p>这个取舍是有意的：省 as 的写法本就罕见，而「含空格的列名」
     * 在对拍语料里是真实存在的。让前者要求写全 as，比让后者静默算错安全。</p>
     */
    private static boolean isPlainIdentifierSegment(Token first, String joined) {
        if (first.type() != TokenType.IDENTIFIER || first.delimited()) {
            return false;
        }
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (c == '(' || c == '*' || c == '+' || c == '/' || c == '\'' || c == '-') {
                return false;
            }
        }
        return true;
    }

    /** 别名用它的语义文本：字符串字面量别名去掉引号，定界标识符也去掉定界符。 */
    private static String renderAlias(Token t) {
        return t.text();
    }

    // ==================== LIMIT / OFFSET ====================

    private record LimitResult(Integer limit, Integer offset, int startIdx) {
    }

    /**
     * 抓语句末尾的 limit / offset。
     *
     * <p>只接受三种形式，且必须落在语句末尾：{@code limit n}、
     * {@code limit n offset m}、{@code offset m}。非末尾的写法留给兜底闸报错。</p>
     */
    private LimitResult takeLimitOffset(int from, int to) {
        // 逗号形式先拒绝（limit 偏移,行数）。只在末尾两个 token 上判，
        // 不能扫整段 —— 否则 order by a, b 的逗号会被误伤
        if (isCommaLimitForm(to)) {
            throw ParserChecks.limitCommaUnsupported();
        }

        // limit n offset m
        if (to - from >= 4) {
            Token a = tokens.get(to - 4);
            Token b = tokens.get(to - 3);
            Token c = tokens.get(to - 2);
            Token d = tokens.get(to - 1);
            if (a.isKeyword("limit") && isNumber(b) && c.isKeyword("offset") && isNumber(d)) {
                return new LimitResult(parseCount(b.text(), "LIMIT"),
                        parseCount(d.text(), "OFFSET"), to - 4);
            }
        }
        // offset m
        if (to - from >= 2) {
            Token a = tokens.get(to - 2);
            Token b = tokens.get(to - 1);
            if (a.isKeyword("offset") && isNumber(b)) {
                return new LimitResult(null, parseCount(b.text(), "OFFSET"), to - 2);
            }
            if (a.isKeyword("limit") && isNumber(b)) {
                return new LimitResult(parseCount(b.text(), "LIMIT"), null, to - 2);
            }
        }
        return new LimitResult(null, null, -1);
    }

    /** 末尾是否为 {@code limit 3, 3} 形式（逗号两侧都是数字） */
    private boolean isCommaLimitForm(int to) {
        if (to < 4) {
            return false;
        }
        Token a = tokens.get(to - 4);
        Token comma = tokens.get(to - 3);
        return a.isKeyword("limit")
                && comma.type() == TokenType.COMMA
                && isNumber(tokens.get(to - 2))
                && isNumber(tokens.get(to - 1));
    }

    private static boolean isNumber(Token t) {
        if (t.type() != TokenType.IDENTIFIER && t.type() != TokenType.RAW) {
            return false;
        }
        String s = t.text();
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int parseCount(String raw, String clause) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw ParserChecks.numberOutOfRange(clause, raw);
        }
    }

    // ==================== ORDER BY ====================

    private record OrderResult(SortSpecs specs, int startIdx) {
    }

    /**
     * 抓 {@code order by} 子句。
     *
     * <p>旧实现用 {@code ORDER_BY_PATTERN} 正则从末尾匹配，引号里的
     * {@code 'order by x'} 会被误判（D4）。现在 {@code order} / {@code by}
     * 必须是 KEYWORD token —— 引号内是 STRING token，压根不参与判断。</p>
     */
    private OrderResult takeOrderBy(int from, int to) {
        int orderIdx = -1;
        for (int i = from; i + 1 < to; i++) {
            if (tokens.get(i).isKeyword("order") && tokens.get(i + 1).isKeyword("by")) {
                orderIdx = i;
                break;
            }
        }

        if (orderIdx < 0) {
            // 出现 order 但没有配对的 by：写法不受支持。
            // 旧实现用 CONTAINS_ORDER_BY 正则查，同样有 D4 的引号问题
            if (hasKeywordBetween(from, to, "order")) {
                throw ParserChecks.orderByUnsupported();
            }
            return new OrderResult(null, -1);
        }

        // order by 之后没有内容
        if (orderIdx + 2 >= to) {
            throw ParserChecks.orderByMissingColumn();
        }

        List<SortSpec> specs = new ArrayList<>();
        int segStart = orderIdx + 2;
        for (int i = orderIdx + 2; i <= to; i++) {
            if (i == to || tokens.get(i).type() == TokenType.COMMA) {
                List<Token> seg = tokens.subList(segStart, i);
                if (seg.isEmpty()) {
                    throw ParserChecks.orderByEmptyColumn();
                }
                specs.add(toSortSpec(seg));
                segStart = i + 1;
            }
        }

        return new OrderResult(new SortSpecs(specs), orderIdx);
    }

    /** 一段排序列：{@code 列名} 或 {@code 列名 asc|desc} */
    private SortSpec toSortSpec(List<Token> seg) {
        if (seg.size() > 2) {
            throw ParserChecks.orderByUnsupported();
        }
        Token col = seg.get(0);
        if (col.type() != TokenType.IDENTIFIER && col.type() != TokenType.KEYWORD) {
            throw ParserChecks.orderByUnsupported();
        }
        boolean desc = false;
        if (seg.size() == 2) {
            Token dir = seg.get(1);
            if (dir.isKeyword("desc")) {
                desc = true;
            } else if (!dir.isKeyword("asc")) {
                throw ParserChecks.orderByUnsupported();
            }
        }
        return new SortSpec(col.text(), desc);
    }

    // ==================== WHERE ====================

    private Condition parseCondition(int from, int to) {
        if (from >= to) {
            throw ParserChecks.whereMissingExpr();
        }

        int lo = from;
        int hi = to;
        while (hi - lo >= 2 && tokens.get(lo).type() == TokenType.LPAREN
                && matchingParen(lo) == hi - 1) {
            lo++;
            hi--;
        }
        if (lo >= hi) {
            throw ParserChecks.whereMissingExpr();
        }

        int orIdx = findTopLevelKeyword(lo, hi, "or");
        if (orIdx >= 0) {
            return new OrCondition(parseCondition(lo, orIdx), parseCondition(orIdx + 1, hi));
        }

        // `between` 里的 and 属于 between 自身，不是逻辑连接词。
        // 必须抢在下面的顶层 AND 拆分之前处理 —— 否则
        // `n between 1 and 10` 会被拆成 `n between 1` 与 `10` 两半，
        // 前半立刻报「缺少 and」。这是 L2 实现时实际踩到的坑。
        Condition betweenForm = tryParseBetween(lo, hi);
        if (betweenForm != null) {
            return betweenForm;
        }

        int andIdx = findTopLevelKeyword(lo, hi, "and");
        if (andIdx >= 0) {
            return new AndCondition(parseCondition(lo, andIdx), parseCondition(andIdx + 1, hi));
        }
        if (tokens.get(lo).isKeyword("not")) {
            return new NotCondition(parseCondition(lo + 1, hi));
        }
        return parseSimpleCondition(lo, hi);
    }

    /**
     * 识别 {@code 列 [not] between 小值 and 大值} 这一种形态。
     *
     * <p>返回 null 表示「这段不是 between 形态」，交回常规流程。
     * 单独抽出来是因为它必须排在顶层 AND 拆分<b>之前</b> ——
     * {@code between} 里的那个 {@code and} 优先级高于逻辑 AND。</p>
     */
    private Condition tryParseBetween(int lo, int hi) {
        // 找 between（含可能的 not 前缀）；只在顶层找，括号内的 between 属内层
        int depth = 0;
        int kw = -1;
        for (int i = lo; i < hi; i++) {
            Token t = tokens.get(i);
            if (t.type() == TokenType.LPAREN) {
                depth++;
            } else if (t.type() == TokenType.RPAREN) {
                depth--;
            } else if (depth == 0 && t.type() == TokenType.KEYWORD && !t.delimited()
                    && t.text().equalsIgnoreCase("between")) {
                kw = i;
                break;
            }
        }
        if (kw < 0) {
            return null;
        }

        boolean negated = kw > lo && tokens.get(kw - 1).isKeyword("not");
        int fieldEnd = negated ? kw - 1 : kw;
        String field = join(lo, fieldEnd);
        if (field.isEmpty()) {
            throw ParserChecks.missingColumnBeforeOp(join(lo, hi));
        }

        int andIdx = findTopLevelKeyword(kw + 1, hi, "and");
        if (andIdx < 0) {
            throw ParserChecks.betweenNeedsAnd(join(kw + 1, hi));
        }
        String low = valueText(kw + 1, andIdx, kw);
        String high = valueText(andIdx + 1, hi, andIdx);
        if (negated) {
            return new OrCondition(new SimpleCondition(field, "<", low),
                    new SimpleCondition(field, ">", high));
        }
        return new AndCondition(new SimpleCondition(field, ">=", low),
                new SimpleCondition(field, "<=", high));
    }

    private Condition parseSimpleCondition(int from, int to) {
        // L2：in / not in 在解析期展开成 = / != 加 or / and 组合。
        // 放在这里而不是执行层，是因为执行层只认 SimpleCondition(field, op, value)；
        // 展开成已有的 Condition 组合后，执行层一行都不用改。
        //
        // between 不在这里 —— 它含一个 and，必须抢在顶层 AND 拆分之前处理，
        // 所以由 parseCondition 里的 tryParseBetween 负责。
        Condition rewritten = tryRewriteInForm(from, to);
        if (rewritten != null) {
            return rewritten;
        }

        int opIdx = -1;
        for (int i = from; i < to; i++) {
            if (tokens.get(i).type() == TokenType.OPERATOR) {
                opIdx = i;
                break;
            }
        }
        if (opIdx < 0) {
            throw ParserChecks.unsupportedOperator(join(from, to));
        }

        String field = join(from, opIdx);
        if (field.isEmpty()) {
            throw ParserChecks.missingColumnBeforeOp(join(from, to));
        }

        int valFrom = opIdx + 1;
        int valTo = to;
        while (valTo - valFrom >= 2 && tokens.get(valFrom).type() == TokenType.LPAREN
                && matchingParen(valFrom) == valTo - 1) {
            valFrom++;
            valTo--;
        }
        if (valFrom >= valTo) {
            throw ParserChecks.missingValueAfterOp(join(from, to));
        }

        return new SimpleCondition(field, tokens.get(opIdx).text(), valueText(valFrom, valTo, opIdx));
    }

    /**
     * L2：把 {@code 列 [not] in (a, b, ...)} 改写成等价的比较组合。
     *
     * <p>用户写 {@code city in ('北京','上海')} 的意思是
     * {@code city = '北京' or city = '上海'}。以前直接报「不支持的运算符」，
     * 等于让用户自己手写展开 —— 而 NOTES 里就是这么建议的
     * （「改用 c = 'a' or c = 'b'」）。规则明确、没有歧义，机器展开比手写可靠。</p>
     *
     * <h2>为什么在解析期做，而不是在执行层做</h2>
     * <p>执行层只认 {@link SimpleCondition}（{@code QueryEngine.bind} 的三元处理）。
     * 在这里展开成已有的 {@link OrCondition} / {@link AndCondition} 树之后，
     * 执行层、图表、分页、PDF 导出全都不用动 —— 改动面小，回归风险也小。</p>
     *
     * <h2>展开规则</h2>
     * <ul>
     *   <li>{@code f in (a, b, c)} → {@code f = a or f = b or f = c}</li>
     *   <li>{@code f not in (a, b)} → {@code f != a and f != b}</li>
     * </ul>
     *
     * <p>{@code f in (a)} 展开成单个等式（不套 OrCondition）。
     * {@code f in ()} 直接报错 —— 空集合没有合法语义，
     * 展开成「永假」会让用户看到空结果而不是看出自己写错了。</p>
     *
     * @return 改写后的条件；不是 in 形态时返回 null，交回原有逻辑
     */
    private Condition tryRewriteInForm(int from, int to) {
        int lo = from;
        int hi = to;
        // 去外层括号：`(a in (1,2))` 与 `a in (1,2)` 等价
        while (hi - lo >= 2 && tokens.get(lo).type() == TokenType.LPAREN
                && matchingParen(lo) == hi - 1) {
            lo++;
            hi--;
        }
        if (lo >= hi) {
            return null;
        }

        // 找 in。必须是裸关键字 —— 列名叫 in 时用户会写成定界标识符 `in`，
        // 那时 delimited() 为 true，不该当运算符。只在顶层找，括号内的属内层
        int kw = -1;
        int depth = 0;
        for (int i = lo; i < hi; i++) {
            Token t = tokens.get(i);
            if (t.type() == TokenType.LPAREN) {
                depth++;
            } else if (t.type() == TokenType.RPAREN) {
                depth--;
            } else if (depth == 0 && t.type() == TokenType.KEYWORD && !t.delimited()
                    && t.text().equalsIgnoreCase("in")) {
                kw = i;
                break;
            }
        }
        if (kw < 0) {
            return null;
        }

        boolean negated = kw > lo && tokens.get(kw - 1).isKeyword("not");
        int fieldEnd = negated ? kw - 1 : kw;
        String field = join(lo, fieldEnd);
        if (field.isEmpty()) {
            throw ParserChecks.missingColumnBeforeOp(join(lo, hi));
        }
        return rewriteIn(field, negated, kw + 1, hi);
    }

    /** {@code f in (a, b)} → {@code f = a or f = b}；否定形式用 {@code !=} 与 AND。 */
    private Condition rewriteIn(String field, boolean negated, int kwEnd, int hi) {
        int lo = kwEnd;
        int hi2 = hi;
        if (lo >= hi2 || tokens.get(lo).type() != TokenType.LPAREN) {
            throw ParserChecks.inNeedsParens(join(kwEnd, hi));
        }
        int close = matchingParen(lo);
        if (close != hi2 - 1) {
            throw ParserChecks.inNeedsParens(join(kwEnd, hi));
        }

        // 按顶层逗号切分元素
        List<int[]> items = new ArrayList<>();
        int depth = 0;
        int start = lo + 1;
        for (int i = lo + 1; i < close; i++) {
            Token t = tokens.get(i);
            if (t.type() == TokenType.LPAREN) {
                depth++;
            } else if (t.type() == TokenType.RPAREN) {
                depth--;
            } else if (t.type() == TokenType.COMMA && depth == 0) {
                items.add(new int[]{start, i});
                start = i + 1;
            }
        }
        items.add(new int[]{start, close});
        // 空集合：`f in ()` —— 中间什么都没有
        if (items.size() == 1 && items.get(0)[0] >= items.get(0)[1]) {
            throw ParserChecks.inEmptyList(join(kwEnd, hi));
        }

        String eq = negated ? "!=" : "=";
        Condition acc = null;
        for (int[] seg : items) {
            String v = valueText(seg[0], seg[1], kwEnd - 1);
            Condition one = new SimpleCondition(field, eq, v);
            acc = (acc == null) ? one
                    : (negated ? new AndCondition(acc, one) : new OrCondition(acc, one));
        }
        return acc;
    }

    /**
     * 取条件的值。
     *
     * <p>三种情形：</p>
     * <ul>
     *   <li>单个 STRING token —— 用它<b>已还原</b>的文本（{@code 'it''s'} → {@code it's}）</li>
     *   <li>单个其他 token —— 原文本</li>
     *   <li>多个 token —— 说明用户写了不带引号、含空格的值，报错</li>
     * </ul>
     */
    private String valueText(int from, int to, int opIdx) {
        if (to - from == 1) {
            return tokens.get(from).text();
        }
        // 多 token 说明值没加引号却含空格（引号内的内容在词法层已合成一个 STRING）。
        // 报错原文用「运算符之后到末尾」的拼接，与旧实现一致
        throw ParserChecks.valueHasSpace(join(opIdx + 1, to));
    }

    // ==================== 工具 ====================

    /**
     * 把 token 区间 [from, to) 拼回文本。
     *
     * <p>拼接规则：<b>相邻 token 在原始 SQL 里挨着就紧贴，有间隔才补一个空格</b>。
     * 这不是洁癖 —— 旧实现是直接 {@code substring} 原文，
     * 所以 {@code count(*)} 就是 {@code count(*)}。
     * 若在这里无脑补空格，会变成 {@code count ( * )}，
     * 而 {@link QueryStatement#isAggregateExpr} 是按 {@code count(} 前缀识别的，
     * 聚合就全部失效了。</p>
     *
     * <p>重排空格还会污染列名：用户写的 {@code sum(a)} 会被原样回显到表头。</p>
     *
     * <p><b>字符串字面量要还原定界符</b>：{@code select 'abc' from t} 的列名是
     * {@code 'abc'} 而不是 {@code abc}。这不是显示问题 ——
     * {@link QueryStatement#looksLikeColumnRef} 靠「带引号 = 常量」把
     * {@code select 'x', count(*)} 判为合法，引号丢了就会被误判成列引用而报错。</p>
     */
    private String join(int from, int to) {
        StringBuilder sb = new StringBuilder();
        int prevEnd = -1;
        for (int i = from; i < to && i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (prevEnd >= 0 && t.pos() > prevEnd) {
                // 原始文本里这两段之间有空隙 —— 补一个空格
                sb.append(' ');
            }
            sb.append(render(t));
            prevEnd = t.pos() + rawLength(t);
        }
        return sb.toString();
    }

    /**
     * token 的「原文形态」：拼回语句片段时用。
     *
     * <p>与 {@link Token#text()} 的区别只有一处：字符串字面量要补回单引号。
     * text 是<b>语义值</b>（已还原转义），而拼回语句片段需要的是<b>源文本形态</b>。</p>
     */
    private static String render(Token t) {
        if (t.type() == TokenType.STRING) {
            // 值里的单引号要还原成 '' 转义形式，否则拼回去会变成未闭合引号
            return "'" + t.text().replace("'", "''") + "'";
        }
        return t.text();
    }

    /**
     * token 在原始 SQL 里占用的字符数。
     *
     * <p>不能直接用 {@code text().length()}：定界标识符与字符串字面量的
     * text 是<b>已剥掉定界符</b>的，比原文短。{@code `销售`} 的 text 是 2 个字，
     * 原文占 4 个。算错会让下一个 token 的间距判断跟着错，
     * 表现出来就是 {@code count (*)} 少了个空格或多了一个。</p>
     */
    private static int rawLength(Token t) {
        return switch (t.type()) {
            // 定界标识符：`x` / "x" / [x] 各多 2 个字符
            case IDENTIFIER -> t.delimited() ? t.text().length() + 2 : t.text().length();
            // 字符串：两侧单引号（+2），内部的每个 ' 在原文里写成 ''（每个 +1）
            case STRING -> t.text().length() + 2 + countChar(t.text(), '\'');
            default -> t.text().length();
        };
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private int indexOfKeyword(String keyword) {
        return indexOfKeyword(0, tokens.size(), keyword);
    }

    private int indexOfKeyword(int from, int to, String keyword) {
        for (int i = from; i < to && i < tokens.size(); i++) {
            if (tokens.get(i).isKeyword(keyword)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasKeywordBetween(int from, int to, String keyword) {
        return indexOfKeyword(from, to, keyword) >= 0;
    }

    private int matchingParen(int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type() == TokenType.LPAREN) {
                depth++;
            } else if (t.type() == TokenType.RPAREN) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int findTopLevelKeyword(int from, int to, String keyword) {
        int depth = 0;
        for (int i = from; i < to; i++) {
            Token t = tokens.get(i);
            if (t.type() == TokenType.LPAREN) {
                depth++;
            } else if (t.type() == TokenType.RPAREN) {
                depth--;
            } else if (depth == 0 && t.isKeyword(keyword)) {
                return i;
            }
        }
        return -1;
    }
}
