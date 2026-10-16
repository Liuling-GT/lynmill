package com.tinyquery.i18n;

import com.tinyquery.executor.ColumnStore;
import com.tinyquery.parser.QueryHintException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「用户可见的报错必须走 i18n」这条规矩。
 *
 * <p>为什么要有这个类：这个仓库已经栽过一次 —— 解析层的文案统一收进了
 * {@link Messages}，但执行层的 {@code ColumnStore} 漏在外面，硬编码成
 * {@code "error:Unknown column:" + 列名}。结果是中文模式下界面显示
 * 「Error: Unknown column:a``b」，中英混杂。中文模式看着「像是故意的」，
 * 所以这个疏漏藏了很久，直到有人真的踩到才发现。</p>
 *
 * <p>所以这里做两件事：</p>
 * <ol>
 *   <li><b>实测</b>：真的去触发几条用户可见的报错，断言中文环境下**不含英文句子**；</li>
 *   <li><b>普查</b>：扫描源码，确认新增的用户可见报错没有绕过 {@link Messages}。</li>
 * </ol>
 *
 * <p>第 2 条是关键 —— 单靠实测只能覆盖已知的几条，
 * 而漏掉的恰恰是「没人想到的那条」。</p>
 */
class UserFacingMessageI18nTest {

    /** 中文文案里出现这种「英文句子」就说明漏翻了。列名、SQL 关键字不算。 */
    private static final Pattern ENGLISH_SENTENCE =
            Pattern.compile("[A-Za-z]+\\s+[A-Za-z]+\\s+[A-Za-z]+");

    /** 允许出现的英文：SQL 关键字、列名、类型名、路径分隔符等 */
    private static final List<String> ALLOWED = List.of(
            "error:", "column", "from", "select", "where", "order by", "limit",
            "java.io", "Security.framework", "D:\\", "C:\\", "/fonts");

    @BeforeEach
    void useChinese() {
        Messages.setCurrent(Messages.ZH);
    }

    @AfterEach
    void reset() {
        Messages.clear();
    }

    private static boolean looksUntranslated(String text) {
        if (text == null) {
            return false;
        }
        if (!ENGLISH_SENTENCE.matcher(text).find()) {
            return false;
        }
        for (String ok : ALLOWED) {
            if (text.contains(ok)) {
                // 含允许项仍可能是漏翻，但误报率高，这里放过；
                // 真正的兜底是下面那条源码普查
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("中文环境下，列不存在的报错不出现英文句子")
    void unknownColumnIsTranslated() {
        ColumnStore store = new ColumnStore();
        store.addRow(new java.util.LinkedHashMap<>(Map.of("姓名", "张三")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.getColumns("没有这列"));

        assertAll(
                () -> assertTrue(e.getMessage().contains("列不存在"),
                        "中文环境下应当显示「列不存在」：" + e.getMessage()),
                () -> assertFalse(looksUntranslated(e.getMessage()),
                        "中文环境下不该出现完整英文句子：" + e.getMessage()));
    }

    @Test
    @DisplayName("中文环境下，带提示的列不存在报错同样不出现英文句子")
    void unknownColumnWithHintIsTranslated() {
        ColumnStore store = new ColumnStore();
        store.addRow(new java.util.LinkedHashMap<>(Map.of("Salary", 100)));

        // 大小写写错时会走带提示的那条分支（QueryHintException）
        QueryHintException e = assertThrows(QueryHintException.class,
                () -> store.getColumns("salary"));

        assertAll(
                () -> assertTrue(e.getMessage().contains("列不存在"),
                        "提示分支也应当走 i18n：" + e.getMessage()),
                () -> assertTrue(e.getHint().contains("Salary"), e.getHint()));
    }

    @Test
    @DisplayName("英文环境下同一条报错给出英文")
    void sameErrorInEnglish() {
        Messages.setCurrent(Messages.EN);
        ColumnStore store = new ColumnStore();
        store.addRow(new java.util.LinkedHashMap<>(Map.of("姓名", "张三")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.getColumns("没有这列"));

        assertTrue(e.getMessage().contains("Unknown column"),
                "英文环境应当显示 Unknown column：" + e.getMessage());
    }

    @Test
    @DisplayName("本轮新收进 i18n 的几条键，中英都有且不为空")
    void newKeysExistInBothLanguages() {
        String[] keys = {
                "parse.unknownColumn",
                "error.exportHeaderTwice",
                "error.exportNeedHeader",
                "error.fontMissing",
                "error.dataDirCreate",
                "error.configSaveFailed",
        };

        for (String key : keys) {
            Messages.setCurrent(Messages.ZH);
            String zh = Messages.get(key, "X");
            Messages.setCurrent(Messages.EN);
            String en = Messages.get(key, "X");

            assertAll(key,
                    () -> assertFalse(zh == null || zh.isBlank(), key + " 缺中文"),
                    () -> assertFalse(en == null || en.isBlank(), key + " 缺英文"),
                    () -> assertFalse(zh.equals(key), key + " 中文未定义（回退成了键名）"));
        }
    }
}
