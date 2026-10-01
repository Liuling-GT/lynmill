package com.tinyquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SecurityToken 访问令牌")
class SecurityTokenTest {

    // ==================== 令牌本身 ====================

    @Test
    @DisplayName("令牌长度为 43 个字符（32 字节 URL-safe Base64 去掉尾部填充）")
    void tokenHasExpectedLength() {
        assertEquals(43, SecurityToken.value().length());
    }

    @Test
    @DisplayName("同一进程内多次取值完全一致 —— 每次请求都换一个的话前端根本没法用")
    void tokenIsStableWithinProcess() {
        assertEquals(SecurityToken.value(), SecurityToken.value());
        assertSame(SecurityToken.value(), SecurityToken.value());
    }

    @Test
    @DisplayName("令牌只含 URL-safe 字符，放进 HTML 与请求头都不需要转义")
    void tokenIsUrlSafe() {
        String token = SecurityToken.value();

        assertTrue(token.matches("[A-Za-z0-9_-]+"), "出现了需要转义的字符：" + token);
        assertFalse(token.contains("+"), token);
        assertFalse(token.contains("/"), token);
        assertFalse(token.contains("="), token);
    }

    // ==================== 比较 ====================

    @Test
    @DisplayName("正确的令牌校验通过")
    void correctTokenMatches() {
        assertTrue(SecurityToken.matches(SecurityToken.value()));
    }

    @Test
    @DisplayName("错误令牌、空串、null 一律不通过")
    void wrongTokenIsRejected() {
        assertFalse(SecurityToken.matches(null));
        assertFalse(SecurityToken.matches(""));
        assertFalse(SecurityToken.matches("  "));
        assertFalse(SecurityToken.matches("not-the-token"));
        assertFalse(SecurityToken.matches(SecurityToken.value() + "x"), "多一个字符也不能通过");
        assertFalse(SecurityToken.matches(SecurityToken.value().substring(0, 42)), "前缀不能通过");
    }

    @Test
    @DisplayName("大小写敏感：改动一个字母就必须失败")
    void comparisonIsCaseSensitive() {
        String token = SecurityToken.value();

        // 令牌里既有数字也有字母，得先找准一个字母再改大小写，
        // 否则改到数字上等于没改，测试会变成假通过
        int index = -1;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLetter(token.charAt(i))) {
                index = i;
                break;
            }
        }
        assertTrue(index >= 0, "令牌里应该有字母：" + token);

        char original = token.charAt(index);
        char swapped = Character.isUpperCase(original)
                ? Character.toLowerCase(original)
                : Character.toUpperCase(original);
        String tampered = token.substring(0, index) + swapped + token.substring(index + 1);

        assertNotEquals(token, tampered);
        assertFalse(SecurityToken.matches(tampered));
    }
}
