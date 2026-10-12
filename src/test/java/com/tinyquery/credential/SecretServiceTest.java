package com.tinyquery.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SecretService} 里那套手写内存偏移的守卫。
 *
 * <p>本类的偏移是照 libsecret 的头文件布局、并用 C 程序在真实 ABI 上
 * {@code sizeof} / {@code offsetof} 量出来的：</p>
 *
 * <pre>
 * SecretSchemaAttribute:   sizeof=16   name@0   type@8
 * SecretSchema:            sizeof=592  name@0   flags@8   attributes@16
 *                                      attributes[1]@32（跨距 16）  sizeof(attributes)=512（32 槽）
 * </pre>
 *
 * <p>为什么值得为几个常量写测试：这些数字一旦写错，症状不是编译失败，
 * 而是运行时段错误或读回垃圾数据—— 在用户的机器上偶发，
 * 极难定位到「某个偏移少了 4 个字节」。有这组断言，改错时会立刻红。</p>
 *
 * <p>本测试不加载任何原生库，因此在三个平台上都能跑。</p>
 */
@DisplayName("SecretService 的 SecretSchema 内存布局")
class SecretServiceTest {

    @Test
    @DisplayName("SecretSchema 各字段偏移与 C 侧实测一致")
    void schemaOffsetsMatchRealAbi() {
        assertEquals(592, SecretService.SCHEMA_SIZE, "sizeof(SecretSchema)");
        assertEquals(0, SecretService.SCHEMA_OFF_NAME, "offsetof(name)");
        assertEquals(8, SecretService.SCHEMA_OFF_FLAGS, "offsetof(flags)");
        assertEquals(16, SecretService.SCHEMA_OFF_ATTRIBUTES, "offsetof(attributes)");
    }

    @Test
    @DisplayName("SecretSchemaAttribute 跨距与内部偏移与 C 侧一致")
    void attributeLayoutMatchesRealAbi() {
        assertEquals(16, SecretService.ATTRIBUTE_STRIDE, "sizeof(SecretSchemaAttribute)");
        assertEquals(0, SecretService.ATTRIBUTE_OFF_NAME, "offsetof(name)");
        assertEquals(8, SecretService.ATTRIBUTE_OFF_TYPE, "offsetof(type)");
        assertEquals(32, SecretService.ATTRIBUTE_SLOTS, "attributes 槽位数");
    }

    @Test
    @DisplayName("attributes 数组总大小必须是 512（头文件里是 attributes[32]，每项 16 字节）")
    void attributeArraySizeMatchesHeader() {
        assertEquals(512, SecretService.ATTRIBUTE_STRIDE * SecretService.ATTRIBUTE_SLOTS);
    }

    @Test
    @DisplayName("结构体总大小必须与各字段推算值吻合 —— 不吻合说明有字段漏算或对齐搞错")
    void schemaSizeIsConsistentWithItsFields() {
        // C 侧的布局：
        //   name(指针 8) + flags(枚举 4) + 对齐填充(4)
        //   + attributes(512)
        //   + reserved(gint 4) + 对齐填充(4)
        //   + reserved1..reserved7(7 个指针 56)
        long derived = 8 + 4 + 4
                + (long) SecretService.ATTRIBUTE_STRIDE * SecretService.ATTRIBUTE_SLOTS
                + 4 + 4
                + 7L * 8;
        assertEquals(SecretService.SCHEMA_SIZE, derived);
    }

    @Test
    @DisplayName("枚举常量取值正确（写错会让 libsecret 按错误的类型解析属性）")
    void enumConstantsMatchHeader() {
        assertEquals(0, SecretService.SCHEMA_FLAGS_NONE, "SECRET_SCHEMA_NONE");
        assertEquals(0, SecretService.ATTRIBUTE_TYPE_STRING, "SECRET_SCHEMA_ATTRIBUTE_STRING");
    }

    // ==================== ReadOutcome ====================

    @Test
    @DisplayName("ReadOutcome 必须能区分「读到了」「本来就没有」「出错」三种情况")
    void readOutcomeDistinguishesThreeCases() {
        SecretService.ReadOutcome found = SecretService.ReadOutcome.found("p@ss");
        assertTrue(found.found());
        assertEquals("p@ss", found.password());
        assertEquals("", found.error());

        SecretService.ReadOutcome missing = SecretService.ReadOutcome.missing();
        assertFalse(missing.found());
        assertEquals("", missing.error(), "「本来就没有」不是错误，error 必须为空");

        SecretService.ReadOutcome failed = SecretService.ReadOutcome.failed("D-Bus 连不上");
        assertFalse(failed.found());
        assertEquals("D-Bus 连不上", failed.error());
    }

    @Test
    @DisplayName("空密码是合法值，不能与「没有密码」混为一谈")
    void emptyPasswordIsStillFound() {
        SecretService.ReadOutcome found = SecretService.ReadOutcome.found("");
        assertTrue(found.found());
        assertEquals("", found.password());
        assertTrue(found.error().isEmpty(), "空密码不是错误");
    }
}
