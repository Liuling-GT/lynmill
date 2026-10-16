package com.tinyquery.parser;

import com.tinyquery.i18n.Messages;
import com.tinyquery.parser.where.Condition;

/**
 * 语义分析 —— 在语法结构已经确定之后，检查它「说不说得通」。
 *
 * <p>语法层只回答「这句话是不是合法 SQL 的形状」，
 * 语义层回答「这个形状有没有意义」。典型区别：</p>
 * <ul>
 *   <li>{@code select name, count(*) from t} —— 语法完全合法（列清单、聚合、表名），
 *       但语义上说不通：聚合只有一行，{@code name} 没有「当前行」可引用</li>
 *   <li>{@code limit -1} —— 语法合法（关键字 + 数字），语义荒谬</li>
 * </ul>
 *
 * <h2>为什么这一层是必要的</h2>
 * <p>这类问题如果不在解析阶段拦下，会一路走到执行期：</p>
 * <ul>
 *   <li>聚合混用列名 → 执行期把列名当字符串输出，表头写着 {@code name}、值也是 {@code name}，
 *       用户分不清是查询坏了还是数据本身如此</li>
 *   <li>{@code limit -1} → 引擎按负数取范围，结果不可预期</li>
 * </ul>
 * <p>都不是「崩溃」，而是<b>静默给出错误结果</b> —— 比崩溃更难发现。</p>
 *
 * <h2>校验项从哪来</h2>
 * <p>重构前这些校验散在 {@code SQLParser} 与 {@code QueryStatement} 构造器里。
 * 1-2 把「属于语义的」集中到本类；{@link QueryStatement} 构造器里
 * 那两条负数校验<b>保留原处</b> —— 它们是该 record 自身的不变式，
 * 挪出来会让「直接 new QueryStatement 的人」绕过校验。</p>
 */
public final class SemanticAnalyzer {

    private SemanticAnalyzer() {
    }

    /**
     * 校验语法层产物并组装为对外契约 {@link QueryStatement}。
     *
     * @throws IllegalArgumentException 语义不合法时抛出
     */
    public static QueryStatement analyze(Parser.Parsed parsed) {
        // limit / offset 为负数没有意义。这里只查「字面量写成负数」的情形；
        // 通过 SQL 传入的负数在词法层是 OPERATOR(-) + 数字两个 token，
        // 现有的 limit 解析只接受单个 token，所以那种写法走的是「不受支持」报错
        if (parsed.limit() != null && parsed.limit() < 0) {
            throw new IllegalArgumentException(Messages.get("parse.limitNegative", parsed.limit()));
        }
        if (parsed.offset() != null && parsed.offset() < 0) {
            throw new IllegalArgumentException(Messages.get("parse.offsetNegative", parsed.offset()));
        }

        QueryStatement stmt = new QueryStatement(
                parsed.selectColumns(), parsed.aliases(), parsed.delimitedFlags(),
                parsed.tableName(), parsed.condition(),
                parsed.sort(), parsed.limit(), parsed.offset());

        // 聚合查询里混入普通列是写错了。放在这里而不是 QueryStatement 构造器里，
        // 是因为它需要「列清单 + 聚合形态」两方面的信息，属于分析而不属于数据不变式
        stmt.validateAggregateSelect();
        return stmt;
    }
}
