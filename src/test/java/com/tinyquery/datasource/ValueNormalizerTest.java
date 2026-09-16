package com.tinyquery.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValueNormalizer 值归一化")
class ValueNormalizerTest {

    // ==================== 空值 ====================

    @Test
    @DisplayName("null 原样返回")
    void normalizeNull() {
        assertNull(ValueNormalizer.normalize(null));
    }

    @Test
    @DisplayName("空串原样返回，不转成 null（CSV 空字段的形态）")
    void normalizeEmptyString() {
        assertEquals("", ValueNormalizer.normalize(""));
    }

    // ==================== 字符串来源（CSV） ====================

    @Test
    @DisplayName("整数字符串转 Integer")
    void normalizeIntegerString() {
        assertEquals(123, ValueNormalizer.normalize("123"));
    }

    @Test
    @DisplayName("非数字字符串原样保留")
    void normalizeTextString() {
        assertEquals("北京", ValueNormalizer.normalize("北京"));
    }

    @Test
    @DisplayName("小数字符串转 BigDecimal，保留原始精度")
    void normalizeDecimalString() {
        assertEquals(new BigDecimal("27.8"), ValueNormalizer.normalize("27.8"));
    }

    @Test
    @DisplayName("超 int 范围的整数字符串转 BigDecimal，不降级为字符串")
    void normalizeHugeIntegerString() {
        Object result = ValueNormalizer.normalize("3000000000");

        assertInstanceOf(BigDecimal.class, result);
        assertEquals(new BigDecimal("3000000000"), result);
    }

    @Test
    @DisplayName("前后空白被 trim 后再判断")
    void normalizePaddedNumber() {
        assertEquals(42, ValueNormalizer.normalize("  42  "));
    }

    // ==================== 数值来源（MySQL JDBC） ====================

    @Test
    @DisplayName("Integer 原样返回")
    void normalizeIntegerObject() {
        assertEquals(7, ValueNormalizer.normalize(7));
    }

    @Test
    @DisplayName("Short / Byte 提升为 Integer")
    void normalizeSmallIntegralTypes() {
        assertEquals(5, ValueNormalizer.normalize((short) 5));
        assertEquals(9, ValueNormalizer.normalize((byte) 9));
    }

    @Test
    @DisplayName("int 范围内的 Long 收敛为 Integer")
    void normalizeLongInRange() {
        assertEquals(40000, ValueNormalizer.normalize(40000L));
    }

    @Test
    @DisplayName("超 int 范围的 Long 保留为 BigDecimal，不溢出成负数")
    void normalizeLongOutOfRange() {
        Object result = ValueNormalizer.normalize(3_000_000_000L);

        assertInstanceOf(BigDecimal.class, result);
        assertEquals(new BigDecimal("3000000000"), result);
    }

    @Test
    @DisplayName("BigInteger 超范围时保留为 BigDecimal")
    void normalizeBigInteger() {
        Object result = ValueNormalizer.normalize(new BigInteger("9999999999"));

        assertEquals(new BigDecimal("9999999999"), result);
    }

    @Test
    @DisplayName("DECIMAL(10,2) 的 12.00 保留 scale，不丢成 12")
    void normalizeDecimalKeepsScale() {
        Object result = ValueNormalizer.normalize(new BigDecimal("12.00"));

        assertEquals(new BigDecimal("12.00"), result);
    }

    @Test
    @DisplayName("Double 转 BigDecimal，保留原始 scale")
    void normalizeDouble() {
        assertEquals(new BigDecimal("1.5"), ValueNormalizer.normalize(1.5d));

        // BigDecimal.equals 会比较 scale，3.0d 归一后是 "3.0" 而非 "3"。
        // 用 compareTo 比较数值本身，与 QueryEngine 的比较方式保持一致
        assertEquals(0, new BigDecimal("3.0")
                .compareTo((BigDecimal) ValueNormalizer.normalize(3.0d)));
    }

    @Test
    @DisplayName("Float 经字符串转换，避免 1.1f 变成 1.100000023841858")
    void normalizeFloatAvoidsPrecisionNoise() {
        assertEquals(new BigDecimal("1.1"), ValueNormalizer.normalize(1.1f));
    }

    // ==================== 布尔 ====================

    @Test
    @DisplayName("Boolean 归一为 1 / 0，以便参与数值比较与聚合")
    void normalizeBoolean() {
        assertEquals(1, ValueNormalizer.normalize(true));
        assertEquals(0, ValueNormalizer.normalize(false));
    }

    // ==================== 时间 ====================

    @Test
    @DisplayName("Timestamp 转 ISO 格式字符串，字典序与时间序一致")
    void normalizeTimestamp() {
        Object result = ValueNormalizer.normalize(Timestamp.valueOf("2023-08-22 13:42:00"));

        assertEquals("2023-08-22T13:42", result);
    }

    @Test
    @DisplayName("LocalDate / LocalDateTime 转 ISO 格式")
    void normalizeLocalDateTime() {
        assertEquals("2023-08-22", ValueNormalizer.normalize(LocalDate.of(2023, 8, 22)));
        assertEquals("2023-08-22T13:42",
                ValueNormalizer.normalize(LocalDateTime.of(2023, 8, 22, 13, 42)));
    }

    // ==================== 其他 ====================

    @Test
    @DisplayName("byte[] 按 UTF-8 解码")
    void normalizeBytes() {
        Object result = ValueNormalizer.normalize("中文".getBytes(StandardCharsets.UTF_8));

        assertEquals("中文", result);
    }

    // ==================== 核心契约 ====================

    @Test
    @DisplayName("同一数值的 CSV 与 MySQL 输入必须归一成同一类型 —— 这是两条路径一致性的基础")
    void csvAndMysqlPathsConverge() {
        // CSV 侧输入字符串，MySQL 侧输入原生类型，结果必须相同
        assertEquals(ValueNormalizer.normalize("123"), ValueNormalizer.normalize(123));
        assertEquals(ValueNormalizer.normalize("123"), ValueNormalizer.normalize(123L));
        assertEquals(ValueNormalizer.normalize("27.8"), ValueNormalizer.normalize(new BigDecimal("27.8")));
    }
}
