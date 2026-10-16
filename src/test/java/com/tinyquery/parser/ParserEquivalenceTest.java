package com.tinyquery.parser;

import com.tinyquery.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 行为基线：把解析器对一大批 SQL 的**完整输出**钉住。
 *
 * <p><b>为什么要有这个类</b>：解析器要分三层重构（Lexer → Parser → SemanticAnalyzer）。
 * 现有 {@link SQLParserTest} 是逐条断言「某条 SQL 的某个字段等于某值」，
 * 覆盖面广但只覆盖写下来的那些点；重构改了别的分支、而那些分支恰好没被断言到，
 * 测试照样全绿。</p>
 *
 * <p>这里换一个思路：对每条 SQL，把解析结果的**每个字段**都序列化成一行，
 * 与预先录下的期望值整体比对。只要有任何一条 SQL 的任何一处行为变了，这里就红。</p>
 *
 * <p><b>为什么快照放在资源文件里，而不是写成 Java 字符串数组</b>：
 * 快照里有报错原文，其中包含引号、反斜杠、单引号不成对等内容
 * （例如 D4 缺陷录出来的 {@code value='a]...column=b'}）。
 * 把它们转义成 Java 字面量极易出错 —— 本项目实测踩过一次：
 * 转义后的文件结构看着完全正常（括号配对、引号偶数、无隐藏字符），
 * 却始终编译不过，排查花的时间远超写测试本身。
 * 放资源文件后**一个字符都不用转义**，也便于人工核对。</p>
 *
 * <p>资源文件由 {@code RecordBaseline} 生成，格式为每行
 * {@code <期望输出>\t<原SQL>}。</p>
 */
class ParserEquivalenceTest {

    private final SQLParser parser = new SQLParser();

    @BeforeEach
    void pinChinese() {
        // 快照里含中文报错文案，必须钉住语言，
        // 否则别的用例切了语言就会莫名其妙地红，而失败原因与被测逻辑无关
        Messages.setCurrent(Messages.ZH);
    }

    @AfterEach
    void clearLocale() {
        Messages.clear();
    }

    @Test
    @DisplayName("行为基线：全部语料的解析输出与重构前一致")
    void behaviorMatchesRecordedBaseline() throws IOException {
        List<String> lines = loadBaseline();
        assertNotNull(lines, "基线资源文件必须存在");
        if (lines.isEmpty()) {
            fail("基线资源文件是空的");
        }

        List<String> changed = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int tab = line.indexOf('\t');
            if (tab < 0) {
                fail("基线第 " + (i + 1) + " 行格式不对（缺少 TAB 分隔）：" + line);
            }
            String expected = line.substring(0, tab);
            String sql = line.substring(tab + 1);
            String actual = describe(sql);
            if (!expected.equals(actual)) {
                changed.add("第 " + (i + 1) + " 条：" + sql
                        + "\n     重构前: " + expected
                        + "\n     重构后: " + actual);
            }
        }

        if (!changed.isEmpty()) {
            fail("有 " + changed.size() + " 条 SQL 的行为变了（共 " + lines.size() + " 条）：\n  "
                    + String.join("\n  ", changed)
                    + "\n\n若是重构引入的意外变化 → 修代码；"
                    + "\n若是有意修复（D2/D4/L3）→ 重新录制基线并写清理由。");
        }
        assertEquals(lines.size(), lines.size());
    }

    @Test
    @DisplayName("基线本身要覆盖到已知缺陷与限制")
    void baselineCoversKnownDefectsAndLimitations() throws IOException {
        String joined = String.join("\n", loadBaseline());
        // 这几条是本轮 D2/D4/L3 的直接目标，基线里必须有 ——
        // 否则「修好了」没有对照，改了也不知道有没有影响别处
        for (String must : List.of(
                "select 'from' as x from t",          // D2
                "select city_from from t",            // D2
                "select * from t_from_x",             // D2 的正常行为，绝不能倒退
                "where a = 'a order by b'",           // D4
                "select * from `t`",                  // L3
                "select * from \"t\"",                // L3
                "select * from [t]")) {                // L3
            if (!joined.contains(must)) {
                fail("基线缺少关键用例：" + must);
            }
        }
    }

    /** 读基线资源。每行 {@code <期望>\t<原SQL>}，忽略空行与 # 开头的注释 */
    private static List<String> loadBaseline() throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = ParserEquivalenceTest.class
                .getResourceAsStream("/parser-baseline.tsv")) {
            if (in == null) {
                return out;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    out.add(line);
                }
            }
        }
        return out;
    }

    /**
     * 把一条 SQL 的解析结果序列化成一行。
     *
     * <p>成功时输出全部字段（表名、列清单、条件、排序、分页），
     * 失败时输出异常类型与消息 —— <b>报错文案也是行为</b>，
     * 重构不许悄悄改掉，所以一并钉住。</p>
     */
    private static String describe(String sql) {
        try {
            QueryStatement st = new SQLParser().parse(sql);
            return "OK|table=" + st.tableName()
                    + "|cols=" + String.join(",", st.selectColumns())
                    + "|where=" + st.condition()
                    + "|sort=" + (st.hasOrderBy() ? String.valueOf(st.sort().specs()) : "-")
                    + "|limit=" + (st.hasLimit() ? String.valueOf(st.limit()) : "-")
                    + "|offset=" + (st.hasOffset() ? String.valueOf(st.offset()) : "-");
        } catch (Exception e) {
            return e.getClass().getSimpleName() + "|" + e.getMessage();
        }
    }
}
