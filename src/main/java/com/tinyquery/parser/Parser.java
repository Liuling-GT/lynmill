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
        String[] columns = parseSelectList(selectIdx + 1, fromIdx);

        // ---- 条件：where 之后到 order/limit 中最早的一个 ----
        Condition condition = null;
        if (whereIdx >= 0) {
            if (whereIdx + 1 >= afterOrder) {
                throw ParserChecks.whereMissingExpr();
            }
            condition = parseCondition(whereIdx + 1, afterOrder);
        }

        return new Parsed(columns, tableName, condition, ord.specs, lim.limit, lim.offset);
    }

    // ==================== 列清单 ====================

    private String[] parseSelectList(int from, int to) {
        if (from >= to) {
            throw ParserChecks.noColumn();
        }
        // 整个列清单就是单个 * 时保持旧行为
        if (to - from == 1 && tokens.get(from).isStar()) {
            return new String[]{"*"};
        }

        List<String> cols = new ArrayList<>();
        int segStart = from;
        for (int i = from; i <= to; i++) {
            if (i == to || tokens.get(i).type() == TokenType.COMMA) {
                String col = join(segStart, i);
                if (col.isEmpty()) {
                    throw ParserChecks.emptyColumn();
                }
                cols.add(col);
                segStart = i + 1;
            }
        }
        return cols.toArray(new String[0]);
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
        int andIdx = findTopLevelKeyword(lo, hi, "and");
        if (andIdx >= 0) {
            return new AndCondition(parseCondition(lo, andIdx), parseCondition(andIdx + 1, hi));
        }
        if (tokens.get(lo).isKeyword("not")) {
            return new NotCondition(parseCondition(lo + 1, hi));
        }
        return parseSimpleCondition(lo, hi);
    }

    private Condition parseSimpleCondition(int from, int to) {
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
