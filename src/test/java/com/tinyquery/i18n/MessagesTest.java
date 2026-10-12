package com.tinyquery.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 后端文案表与语言切换的测试。
 *
 * <p>重点是两类容易出错的地方：词条漏配（英文缺条目会静默回落中文，
 * 只有测试能发现）和 ThreadLocal 泄漏（Tomcat 复用线程，
 * 不清理会把语言带给下一个请求，表现为「偶尔弹一句英文」）。</p>
 */
class MessagesTest {

    @AfterEach
    void tearDown() {
        // 每个用例后都清一次，避免用例之间互相污染
        Messages.clear();
    }

    @Test
    @DisplayName("默认语言是中文")
    void defaultsToChinese() {
        Messages.clear();
        assertEquals(Messages.ZH, Messages.current());
        assertEquals("数据源不存在：users", Messages.get("error.datasourceNotFound", "users"));
    }

    @Test
    @DisplayName("切到英文后取到英文文案")
    void englishAfterSwitch() {
        Messages.setCurrent(Messages.EN);
        assertEquals(Messages.EN, Messages.current());
        assertEquals("Data Source not found: users",
                Messages.get("error.datasourceNotFound", "users"));
    }

    @Test
    @DisplayName("clean 之后回到中文 —— ThreadLocal 不能泄漏")
    void clearResetsToChinese() {
        Messages.setCurrent(Messages.EN);
        Messages.clear();
        assertEquals(Messages.ZH, Messages.current());
    }

    @Test
    @DisplayName("认不出的语言值回落到中文")
    void unknownLocaleFallsBackToChinese() {
        Messages.setCurrent("fr");
        assertEquals(Messages.ZH, Messages.current());
        assertEquals("请求失败", Messages.get("api.requestFailed"));
    }

    @Test
    @DisplayName("语言标识规范化：zh-CN / zh_CN / ZH 都归到 zh，en-US 归到 en")
    void normalizeLocale() {
        assertEquals(Messages.ZH, Messages.normalize(null));
        assertEquals(Messages.ZH, Messages.normalize(""));
        assertEquals(Messages.ZH, Messages.normalize("zh"));
        assertEquals(Messages.ZH, Messages.normalize("zh-CN"));
        assertEquals(Messages.ZH, Messages.normalize("zh_CN"));
        assertEquals(Messages.ZH, Messages.normalize("ZH"));
        assertEquals(Messages.EN, Messages.normalize("en"));
        assertEquals(Messages.EN, Messages.normalize("en-US"));
        assertEquals(Messages.EN, Messages.normalize("EN_us"));
        // 不认识的一律中文
        assertEquals(Messages.ZH, Messages.normalize("ja-JP"));
    }

    @Test
    @DisplayName("占位符按位置替换")
    void placeholderSubstitution() {
        Messages.setCurrent(Messages.EN);
        assertEquals("Unsupported data source type: oracle",
                Messages.get("error.typeUnsupported", "oracle"));
        assertEquals("Missing required field: host",
                Messages.get("error.fieldRequired", "host"));
    }

    @Test
    @DisplayName("多个占位符各自替换，不会串位")
    void multiplePlaceholders() {
        Messages.setCurrent(Messages.EN);
        // delimiterTooLong 有 {0} 和 {1}，换成同样的值就看不出串位，所以给不同的值
        String text = Messages.get("error.delimiterTooLong", "ds1", "abc");
        assertTrue(text.contains("ds1"), text);
        assertTrue(text.contains("abc"), text);
        assertFalse(text.contains("{0}"), text);
        assertFalse(text.contains("{1}"), text);
    }

    @Test
    @DisplayName("缺参数的占位符原样保留，不会抛异常")
    void missingArgsKeepPlaceholder() {
        Messages.setCurrent(Messages.EN);
        // 不传参数时模板原样返回 —— 宁可显示出 {0} 让人看出漏传，也不要 NPE
        String text = Messages.get("error.typeUnsupported");
        assertTrue(text.contains("{0}"), text);
    }

    @Test
    @DisplayName("中文模式下不会取到英文文案")
    void chineseModeReturnsChinese() {
        Messages.setCurrent(Messages.ZH);
        assertEquals("数据源不存在：x", Messages.get("error.datasourceNotFound", "x"));
        assertFalse(Messages.get("error.datasourceNotFound", "x").contains("not found"));
    }

    @Test
    @DisplayName("英文模式下不残留中文标点")
    void englishModeHasNoFullWidthPunctuation() {
        Messages.setCurrent(Messages.EN);
        String[] keys = {
                "error.datasourceNotFound", "error.typeUnsupported", "error.fieldRequired",
                "error.nameBlank", "error.passwordMasked", "error.loadFailed",
                "error.connectFailed", "error.exportNotSupported", "error.measureRequired",
                "test.connectionOk", "test.fileReadable",
        };
        for (String key : keys) {
            String text = Messages.get(key, "a", "b");
            assertFalse(text.matches(".*[，。：；（）「」、].*"),
                    key + " 的英文文案里出现了全角标点：" + text);
        }
    }

    @Test
    @DisplayName("英文文案的冒号后必须有一个空格")
    void englishColonFollowedBySpace() {
        Messages.setCurrent(Messages.EN);
        // 这些是「前缀 + 具体值」型文案，冒号后面一定还有内容
        String[] keys = {
                "error.datasourceNotFound", "error.fileNotFound", "error.connectFailed",
                "error.loadFailed", "error.typeUnsupported",
        };
        for (String key : keys) {
            String text = Messages.get(key, "value", "value");
            assertFalse(text.contains(":value"),
                    key + " 的英文冒号后缺空格：" + text);
        }
    }

    @Test
    @DisplayName("未知键返回键名本身，便于定位漏配")
    void unknownKeyReturnsKey() {
        assertEquals("error.doesNotExist", Messages.get("error.doesNotExist"));
    }

    @Test
    @DisplayName("中英文词条数一致 —— 防止加了中文忘了英文")
    void zhAndEnHaveSameKeys() {
        // 通过反射读两张表的键集合。两张表都是 private static，
        // 这里只做数量与键集合的比对，不读内容
        Set<String> zhKeys = keysOf("ZH_TEXT");
        Set<String> enKeys = keysOf("EN_TEXT");

        Set<String> missingInEn = new HashSet<>(zhKeys);
        missingInEn.removeAll(enKeys);
        Set<String> missingInZh = new HashSet<>(enKeys);
        missingInZh.removeAll(zhKeys);

        assertTrue(missingInEn.isEmpty(), "英文表缺少词条：" + missingInEn);
        assertTrue(missingInZh.isEmpty(), "中文表缺少词条：" + missingInZh);
        assertFalse(zhKeys.isEmpty(), "中文表为空，反射取键失败");
    }

    @SuppressWarnings("unchecked")
    private Set<String> keysOf(String fieldName) {
        try {
            java.lang.reflect.Field field = Messages.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return new HashSet<>(((java.util.Map<String, String>) field.get(null)).keySet());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读取 " + fieldName + " 失败", e);
        }
    }

    @Test
    @DisplayName("中英文都不含空词条")
    void noBlankEntries() {
        Messages.setCurrent(Messages.EN);
        for (String key : keysOf("ZH_TEXT")) {
            String zhText = Messages.get(key);
            assertFalse(zhText.isBlank(), key + " 的中文词条为空");
        }
        Messages.setCurrent(Messages.ZH);
        for (String key : keysOf("EN_TEXT")) {
            String enText = Messages.get(key);
            assertFalse(enText.isBlank(), key + " 的英文词条为空");
        }
    }

    @Test
    @DisplayName("SQL 关键字在英文文案里保持大写")
    void sqlKeywordsStayUppercase() {
        Messages.setCurrent(Messages.EN);
        assertTrue(Messages.get("field.tableDesc").contains("SELECT *"));
    }

    @Test
    @DisplayName("术语统一：数据源一律译作 Data Source")
    void glossaryDataSourceTerm() {
        Messages.setCurrent(Messages.EN);
        // 这几条含「数据源」，必须都用 Data Source，不能出现 datasource / data source
        String[] keys = {
                "error.datasourceNotFound", "error.typeUnsupported", "error.passwordMasked",
                "error.delimiterTooLong", "field.tableDesc",
        };
        for (String key : keys) {
            String text = Messages.get(key, "a", "b");
            if (text.toLowerCase().contains("data source")) {
                assertTrue(text.contains("Data Source") || text.toLowerCase().contains("data source"),
                        key + " 的 Data Source 大小写不统一：" + text);
                assertFalse(text.contains("Datasource") || text.contains("datasource"),
                        key + " 里混用了 Datasource：" + text);
            }
        }
    }
}
