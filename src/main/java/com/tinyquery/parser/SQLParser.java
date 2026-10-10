package com.tinyquery.parser;

import com.tinyquery.i18n.Messages;

import com.tinyquery.parser.where.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SQLParser {

    // LIMIT / OFFSET 子句必须从语句末尾剥离，否则会被 WHERE 或表名的切分吃掉。
    // 三种受支持形式，按「最具体优先」的顺序匹配：limit n offset m > offset m > limit n
    private static final Pattern LIMIT_OFFSET_PATTERN =
            Pattern.compile("\\s+limit\\s+(\\d+)\\s+offset\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFSET_ONLY_PATTERN =
            Pattern.compile("\\s+offset\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("\\s+limit\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    // MySQL 的 limit 偏移,行数：参数顺序与标准写法相反，是最容易读错的一种。
    // 明确拒绝并指路，不静默接受 —— 静默接受会让用户以为写的是行数
    private static final Pattern LIMIT_COMMA_PATTERN =
            Pattern.compile("\\s+limit\\s+\\d+\\s*,\\s*\\d+\\s*$", Pattern.CASE_INSENSITIVE);

    // ORDER BY 同样从末尾剥离，且必须在 LIMIT 之后剥离 ——
    // 先把 LIMIT 摘掉，ORDER BY 才落到语句末尾。
    // 这里抓出「order by 之后的整段」，多列切分交给 splitOrderByItems 做 ——
    // 正则里塞不下引号感知的逗号切分
    private static final Pattern ORDER_BY_PATTERN =
            Pattern.compile("\\s+order\\s+by\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    // 单个排序列：列名 + 可选方向。列名不含空白与逗号，逗号在这里已被切成段
    private static final Pattern ORDER_BY_ITEM_PATTERN =
            Pattern.compile("^(\\S+?)(?:\\s+(asc|desc))?$", Pattern.CASE_INSENSITIVE);

    // 用于判断「出现了 order by 但没被上面的模式匹配到」的写法
    private static final Pattern CONTAINS_ORDER_BY =
            Pattern.compile("\\border\\s+by\\b", Pattern.CASE_INSENSITIVE);

    // 支持的运算符。双字符必须排在单字符之前，否则 ">=" 会被切成 ">" 再吃掉 "="
    private static final String[] OPERATORS = {">=", "<=", "!=", "<>", ">", "<", "="};

    public QueryStatement parse(String sql) {

        // 引号未闭合必须最先拦住：未闭合时字面量会一路"吃掉"后面的内容，
        // 值是错的却不报错 —— where name = 'abc 会得到值 'abc（前引号没被剥掉），
        // 字符串比较永不相等，用户看到的是空表而不是一个错误提示。
        if (hasUnterminatedLiteral(sql)) {
            throw new IllegalArgumentException(Messages.get("parse.unterminatedQuote"));
        }

        // 预处理：只归一化引号外的片段。引号内是用户数据，任何改写都是数据污染 ——
        // 原来的全局替换会把 where name = 'And' 改成 ' and '，
        // 凭空插入空格并制造出孤立引号，最终崩在去引号的 substring 上。
        sql = normalize(sql);

        if (!sql.toLowerCase().contains("select")) {
            throw new IllegalArgumentException(Messages.get("parse.missingSelect"));
        }
        if (!sql.toLowerCase().contains("from")) {
            throw new IllegalArgumentException(Messages.get("parse.missingFrom"));
        }

        // LIMIT/OFFSET 必须在 WHERE 之前剥离：WHERE 分支会把「where 之后的所有内容」
        // 整段当作条件，LIMIT 留在里面会让条件解析直接失败
        LimitClause limitClause = parseLimitClause(sql);
        Integer limit = limitClause == null ? null : limitClause.limit();
        Integer offset = limitClause == null ? null : limitClause.offset();
        if (limitClause != null) {
            // start 由 parseLimitClause 一并返回，不再二次匹配 ——
            // 原来的写法依赖「两个方法用同一个模式」这层隐式契约，改一处就会静默失配
            sql = sql.substring(0, limitClause.start()).trim();
        }

        // 兜底闸：剥离之后仍残留 limit / offset 关键字，说明写法不受支持。
        // 不静默忽略 —— 否则表名会被污染成「t limit 3 offset 3」，
        // 用户看到的是「数据源不存在：t limit 3 offset 3」这种无从下手的报错。
        // 放在「剥离之后」而不是「匹配失败时」，是为了连组合写法一起覆盖：
        // limit 3, 3 offset 5 的逗号模式锚定末尾匹配不上，offset 会被剥掉，
        // 残留的 limit 3, 3 就由这道闸拦下
        boolean[] limitMask = literalMask(sql);
        if (findTopLevelKeyword(sql, limitMask, "limit") >= 0
                || findTopLevelKeyword(sql, limitMask, "offset") >= 0) {
            throw new IllegalArgumentException(
                    Messages.get("parse.limitOffsetUnsupported"));
        }

        // ORDER BY 在 LIMIT 之后剥离（此时它才位于末尾），同样必须在 WHERE 之前
        OrderByClause orderBy = parseOrderBy(sql);
        SortSpecs sort = orderBy == null ? null : orderBy.specs();
        if (orderBy != null) {
            // 与 LIMIT 一样，剥离位置由解析方法一并返回，不二次匹配
            sql = sql.substring(0, orderBy.start()).trim();
        }

        // where 部分
        String wherePart = null;
        int whereIndex = indexOfWhereKeyword(sql);
        if (whereIndex != -1) {
            wherePart = sql.substring(whereIndex + 5).trim();
            sql = sql.substring(0, whereIndex).trim();
            if (wherePart.isEmpty()) {
                // 静默「不过滤」会让用户以为加了条件、实际拿到全表 —— 宁可报错
                throw new IllegalArgumentException(Messages.get("parse.whereMissingExpr"));
            }
        }

        // 列名
        String selectPart = sql.substring(
                sql.toLowerCase().indexOf("select") + 6,
                sql.toLowerCase().indexOf("from")
        ).trim();

        if (selectPart.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("parse.noColumn"));
        }

        String[] columns;
        if ("*".equals(selectPart)) {
            columns = new String[]{"*"};
        } else {
            String[] raw = selectPart.split(",");
            columns = new String[raw.length];
            for (int i = 0; i < raw.length; i++) {
                String col = raw[i].trim();
                columns[i] = col;
                if (columns[i].isEmpty()) {
                    throw new IllegalArgumentException(Messages.get("parse.emptyColumn"));
                }
            }
        }

        // 表名
        String tableName = sql.substring(sql.toLowerCase().indexOf("from") + 4).trim();

        if (tableName.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("parse.missingTable"));
        }

        // 解析 where
        Condition condition = null;
        if (wherePart != null && !wherePart.isEmpty()) {
            condition = parseCondition(wherePart);
        }

        QueryStatement stmt = new QueryStatement(columns, tableName, condition, sort, limit, offset);
        // 聚合查询里混入普通列是写错了，在这里拦下 ——
        // 等到执行期会把列名当成字符串输出，用户拿到的是一列看不懂的值而不是报错
        stmt.validateAggregateSelect();
        return stmt;
    }

    /**
     * LIMIT / OFFSET 子句的解析结果。
     *
     * <p>一并带上子句在语句中的起始下标 {@code start}，供调用方剥离。
     * 早先的做法是返回行数后由调用方「用同一个模式再 find 一次」取位置，
     * 两个方法靠一层隐式契约耦合 —— 模式改了一处就会静默失配。</p>
     *
     * @param limit  行数上限；未指定时为 null
     * @param offset 跳过的行数；未指定时为 null
     * @param start  子句在语句中的起始下标
     */
    private record LimitClause(Integer limit, Integer offset, int start) {
    }

    /**
     * 解析语句末尾的 LIMIT / OFFSET 子句。
     *
     * <p>支持三种形式：{@code limit n}、{@code limit n offset m}、{@code offset m}。
     * 全部用「行首关键字 + 数字 + 语句结束」的锚定匹配，因此
     * {@code WHERE note = 'limit 10'} 这类字符串字面量不会被误判。</p>
     *
     * <p>匹配不上时返回 null，由调用方的兜底闸决定是否报错 ——
     * 这里不报错是为了让「语句里压根没有 limit/offset」和
     * 「有但写错了」两种情况能被区分开。</p>
     *
     * @return 解析结果；未出现 LIMIT / OFFSET 时返回 null
     */
    private LimitClause parseLimitClause(String sql) {
        // 逗号形式先拒绝：limit 偏移,行数 的顺序与标准相反，静默接受会让用户
        // 以为第一个数是行数，拿到的却是从第 N 行开始的另一段数据
        if (LIMIT_COMMA_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException(
                    Messages.get("parse.limitCommaUnsupported"));
        }

        // 按「最具体优先」匹配：limit n offset m 必须排在 offset m 之前，
        // 否则后者会把前半截当成普通内容留在语句里
        Matcher matcher = LIMIT_OFFSET_PATTERN.matcher(sql);
        if (matcher.find()) {
            return new LimitClause(parseCount(matcher.group(1), "LIMIT"),
                    parseCount(matcher.group(2), "OFFSET"), matcher.start());
        }

        matcher = OFFSET_ONLY_PATTERN.matcher(sql);
        if (matcher.find()) {
            return new LimitClause(null, parseCount(matcher.group(1), "OFFSET"), matcher.start());
        }

        matcher = LIMIT_PATTERN.matcher(sql);
        if (matcher.find()) {
            return new LimitClause(parseCount(matcher.group(1), "LIMIT"), null, matcher.start());
        }

        return null;
    }

    /** 把子句里的数字转成 int，超出 int 范围时给出带子句名的报错 */
    private static int parseCount(String raw, String clause) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(Messages.get("parse.numberOutOfRange", clause, raw));
        }
    }

    /**
     * ORDER BY 子句的解析结果
     * 一并带上子句起始下标，供调用方剥离（与 LimitClause 同样的理由）
     *
     * @param specs 排序列，至少一个
     * @param start 子句在语句中的起始下标
     */
    private record OrderByClause(SortSpecs specs, int start) {
    }

    /**
     * 解析语句末尾的 ORDER BY 子句，支持多列。
     *
     * <p>用锚定匹配抓出「order by 之后的整段」，避免
     * {@code WHERE note = 'a order by b'} 这类字面量被误判；
     * 再按<b>顶层逗号</b>切分成若干个「列名 [asc|desc]」。</p>
     *
     * <p>切分必须跳过引号内的逗号，否则 {@code order by 'a,b'} 会被切成两段。
     * 归一化阶段不会把引号内的逗号改写，所以这里能直接复用 literalMask。</p>
     *
     * @return 解析结果；未出现 ORDER BY 时返回 null
     */
    private OrderByClause parseOrderBy(String sql) {
        Matcher matcher = ORDER_BY_PATTERN.matcher(sql);
        if (!matcher.find()) {
            // 没有匹配到完整形式，但语句里确实出现了 order by，
            // 说明写法不受支持。不静默忽略，否则表名会被污染成「t order by」这种难以理解的形态
            if (CONTAINS_ORDER_BY.matcher(sql).find()) {
                throw new IllegalArgumentException(
                        Messages.get("parse.orderByUnsupported"));
            }
            return null;
        }

        String body = matcher.group(1).trim();
        List<SortSpec> specs = new ArrayList<>();
        for (String item : splitTopLevelCommas(body)) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                // order by a, / order by ,a / order by a,,b
                // 早先逗号会留在列名里，用户看到的是「列 dept, 不存在」这种莫名其妙的报错
                throw new IllegalArgumentException(
                        Messages.get("parse.orderByEmptyColumn"));
            }
            Matcher itemMatcher = ORDER_BY_ITEM_PATTERN.matcher(trimmed);
            if (!itemMatcher.find()) {
                // order by a b c 这类：列表里混进了多余的片段
                throw new IllegalArgumentException(
                        Messages.get("parse.orderByUnsupported"));
            }
            // 方向缺省为升序
            specs.add(new SortSpec(itemMatcher.group(1), "desc".equalsIgnoreCase(itemMatcher.group(2))));
        }

        if (specs.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("parse.orderByMissingColumn"));
        }
        return new OrderByClause(new SortSpecs(specs), matcher.start());
    }

    /** 按顶层逗号切分（跳过引号内的逗号） */
    private static List<String> splitTopLevelCommas(String text) {
        boolean[] inLiteral = literalMask(text);
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!inLiteral[i] && text.charAt(i) == ',') {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private Condition parseCondition(String expr) {
        expr = expr.trim();

        // 剥掉最外层成对的括号：where (age > 30) 等价于 where age > 30。
        // 必须是「闭合于末尾」才剥，否则 (a = 1) and (b = 2) 会被切坏
        expr = stripOuterParens(expr);

        // 引号掩码算一次，下面所有查找都靠它跳过引号内的内容
        boolean[] inLiteral = literalMask(expr);

        // OR 优先级最低，先找
        int orIndex = findTopLevelKeyword(expr, inLiteral, "or");
        if (orIndex != -1) {
            Condition left = parseCondition(expr.substring(0, orIndex));
            Condition right = parseCondition(expr.substring(orIndex + 2));
            return new OrCondition(left, right);
        }

        // AND
        int andIndex = findTopLevelKeyword(expr, inLiteral, "and");
        if (andIndex != -1) {
            Condition left = parseCondition(expr.substring(0, andIndex));
            Condition right = parseCondition(expr.substring(andIndex + 3));
            return new AndCondition(left, right);
        }

        // NOT。原来只认 "not " 前缀，改成同时认 not(age > 5) 这种紧贴括号的写法
        if (startsWithKeyword(expr, inLiteral, "not")) {
            return new NotCondition(parseCondition(expr.substring(3)));
        }

        return parseSimpleCondition(expr);
    }

    /**
     * 简单条件：按运算符切分，不按空格切分。
     *
     * <p>原来的 {@code split(" ")} 只取前三段，第四段之后全部静默丢弃 ——
     * 这既让值里的空格被截断，也让 {@code and (a = 1 or b = 2)} 这种
     * 括号内的分支凭空消失。改成「找第一个引号外的运算符，其余全部是值」。</p>
     */
    private Condition parseSimpleCondition(String expr) {
        boolean[] inLiteral = literalMask(expr);

        int opIndex = -1;
        String op = null;
        search:
        for (int i = 0; i < expr.length(); i++) {
            if (inLiteral[i]) {
                continue;
            }
            for (String candidate : OPERATORS) {
                if (expr.startsWith(candidate, i)) {
                    opIndex = i;
                    op = candidate;
                    break search;
                }
            }
        }

        if (opIndex < 0) {
            throw new IllegalArgumentException(
                    Messages.get("parse.unsupportedOperator", expr)
                            + Messages.get("parse.supportedOperators"));
        }

        String field = expr.substring(0, opIndex).trim();
        String value = expr.substring(opIndex + op.length()).trim();

        if (field.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("parse.missingColumnBeforeOp", expr));
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("parse.missingValueAfterOp", expr));
        }

        // 值两侧的括号同样剥掉，让 where a > (1) 与 where a > 1 等价
        value = stripOuterParens(value);

        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            // 长度判断是必需的：value = "'" 时 substring(1, 0) 会抛 StringIndexOutOfBounds。
            // '' 在这里还原成单个引号，让 'it''s' 得到 it's
            value = value.substring(1, value.length() - 1).replace("''", "'");
        } else if (value.indexOf(' ') >= 0) {
            // 未加引号却含空格，多半是漏了引号。宁可报错，也不做一次静默的字符串比较
            throw new IllegalArgumentException(Messages.get("parse.valueHasSpace", value));
        }

        return new SimpleCondition(field, op, value);
    }

    // ==================== 引号感知 ====================
    // 下面这组方法解决同一类根因：原实现把 SQL 当纯字符串处理，看不见单引号边界，
    // 于是引号内的用户数据会被改写、被当作关键字、被当作运算符。

    /**
     * 标出每个字符是否位于单引号字面量内。
     *
     * <p>引号内的内容属于用户数据：既不参与关键字识别，也不参与运算符切分，
     * 更不允许被预处理改写。</p>
     */
    private static boolean[] literalMask(String s) {
        boolean[] mask = new boolean[s.length()];
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                // 单引号无论开还是闭，本身都算字面量内容，直接标 true 并翻转状态即可。
                // 不需要为 '' 转义单开分支：'' 等价于「闭合+开启」两次翻转，状态回到原样，
                // 而两个引号位置本来就都是 true，掩码结果完全相同。
                // 转义的还原放在取值之后做（见 parseSimpleCondition）。
                in = !in;
                mask[i] = true;
                continue;
            }
            mask[i] = in;
        }
        return mask;
    }

    /**
     * 单引号必须成对。
     *
     * <p>未闭合时字面量会一直延伸到语句末尾，把后面的子句全部吃掉，
     * 得到的值是错的却不报错。数奇偶即可，不需要处理 {@code ''} 转义 ——
     * 转义是两个引号，翻转两次等于没翻转，不影响奇偶性。</p>
     */
    private static boolean hasUnterminatedLiteral(String sql) {
        boolean in = false;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '\'') {
                in = !in;
            }
        }
        return in;
    }

    /** 归一化：按引号状态切段，只对引号外的片段做替换 */
    private static String normalize(String sql) {
        boolean[] inLiteral = literalMask(sql);
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int i = 0;
        while (i < sql.length()) {
            int start = i;
            boolean lit = inLiteral[i];
            while (i < sql.length() && inLiteral[i] == lit) {
                i++;
            }
            String seg = sql.substring(start, i);
            out.append(lit ? seg : normalizeCode(seg));
        }
        return out.toString().trim();
    }

    /** 原来的 5 个替换，一字未改，只是现在只作用在引号外的片段上 */
    private static String normalizeCode(String seg) {
        return seg.replaceAll("(?i)\\band\\b", " and ")
                .replaceAll("(?i)\\bor\\b", " or ")
                .replaceAll("(?i)\\bnot\\b", " not ")
                .replaceAll("(>=|<=|!=|<>|>|<|=)", " $1 ")
                .replaceAll("\\s+", " ");
    }

    /**
     * 找真正的 WHERE 关键字：跳过引号内，并要求前后是词边界。
     *
     * <p>原来的 {@code indexOf("where")} 有两个问题：
     * {@code select 'where' as x from t} 会扫到字面量里的 where，
     * 使后面的 substring 抛 StringIndexOutOfBoundsException；
     * 列名含 where（如 {@code anywhere}）也会被误认。</p>
     */
    private static int indexOfWhereKeyword(String sql) {
        boolean[] inLiteral = literalMask(sql);
        String lower = sql.toLowerCase();
        for (int i = 0; i + 5 <= lower.length(); i++) {
            if (inLiteral[i] || !lower.startsWith("where", i)) {
                continue;
            }
            boolean leftOk = i == 0 || !isWordChar(lower.charAt(i - 1));
            boolean rightOk = i + 5 >= lower.length() || !isWordChar(lower.charAt(i + 5));
            if (leftOk && rightOk) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ==================== 括号 ====================

    /** 反复剥掉「最外层且闭合于末尾」的括号 */
    private static String stripOuterParens(String expr) {
        while (expr.length() >= 2
                && expr.charAt(0) == '('
                && matchingParen(expr, 0) == expr.length() - 1) {
            expr = expr.substring(1, expr.length() - 1).trim();
        }
        return expr;
    }

    /** 返回与 openIndex 处左括号配对的右括号下标；配对时跳过引号内的括号 */
    private static int matchingParen(String s, int openIndex) {
        boolean[] inLiteral = literalMask(s);
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            if (inLiteral[i]) {
                // where name = ')' 里的右括号不算数
                continue;
            }
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean startsWithKeyword(String expr, boolean[] inLiteral, String keyword) {
        if (!expr.regionMatches(true, 0, keyword, 0, keyword.length())) {
            return false;
        }
        if (inLiteral.length > 0 && inLiteral[0]) {
            return false;
        }
        int after = keyword.length();
        return after >= expr.length()
                || expr.charAt(after) == ' '
                || expr.charAt(after) == '(';
    }

    /**
     * 找到最外层关键字位置（跳过引号内的位置）。
     *
     * <p>必须跳过引号内，否则 {@code where name = 'x and y'} 里的 and
     * 前后正好都是空格，会被误当成条件连接词，把用户的值切成两半。</p>
     */
    private static int findTopLevelKeyword(String expr, boolean[] inLiteral, String keyword) {
        int depth = 0;
        String lower = expr.toLowerCase();
        for (int i = 0; i <= lower.length() - keyword.length(); i++) {
            if (inLiteral[i]) {
                continue;
            }
            char c = lower.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && lower.startsWith(keyword, i)) {
                boolean leftOk = i == 0 || lower.charAt(i - 1) == ' ';
                boolean rightOk = i + keyword.length() >= lower.length()
                        || lower.charAt(i + keyword.length()) == ' ';
                if (leftOk && rightOk) return i;
            }
        }
        return -1;
    }
}
