package com.tinyquery.datasource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 数据源值类型归一器：CSV 与 MySQL 共用这一条路径，不存在「CSV 保持原样、MySQL 单独转」的分叉
 * 输出只可能是 Integer / BigDecimal / String / null 四种
 * 整数值 → Integer，保留 QueryEngine 的快速比较路径
 * 大数、小数 → BigDecimal
 * 避免字典序比较（"9" &gt; "10"）给出错误结果
 * 布尔 → Integer 1/0，与 MySQL 中 BOOLEAN 即 TINYINT(1) 的语义一致
 * 日期时间、字节流、其余类型 → String
 * 本类的输出类型必须与 QueryEngine 的比较、聚合逻辑一致，
 * 两者不能分阶段单独修改，否则中间态会出现「条件永远不匹配」的静默错误
 */
public final class ValueNormalizer {

    // 禁止实例化
    private ValueNormalizer() {
    }

    /**
     * 把任意来源的原始值归一成 Integer / BigDecimal / String / null。
     * @param raw CSV 传入 String，MySQL 传入 JDBC 原生类型
     * @return 归一化后的值
     */
    public static Object normalize(Object raw) {
        if (raw == null) {
            return null;
        }

        // CSV 走这条：数字串转数值，其余保持字符串
        if (raw instanceof String str) {
            return normalizeString(str);
        }

        if (raw instanceof Integer intValue) {
            return intValue;
        }
        if (raw instanceof Short shortValue) {
            return (int) shortValue;
        }
        if (raw instanceof Byte byteValue) {
            return (int) byteValue;
        }

        // Long / BigInteger：能收进 int 就收，收不下则保留 BigDecimal
        if (raw instanceof Long longValue) {
            return intOrDecimal(BigDecimal.valueOf(longValue));
        }
        if (raw instanceof BigInteger bigInteger) {
            return intOrDecimal(new BigDecimal(bigInteger));
        }

        // 小数类型保持 BigDecimal 原样，不 stripTrailingZeros，
        // 以便 DECIMAL(10,2) 的 12.00 在结果里仍显示为 "12.00"
        if (raw instanceof BigDecimal decimal) {
            return plain(decimal);
        }
        if (raw instanceof Double doubleValue) {
            return plain(BigDecimal.valueOf(doubleValue));
        }
        // Float 必须经 Float.toString 转换，否则 BigDecimal.valueOf(1.1f) 会得到 1.100000023841858
        if (raw instanceof Float floatValue) {
            return plain(new BigDecimal(Float.toString(floatValue)));
        }

        // MySQL 的 BOOLEAN 即 TINYINT(1)，归一为 1/0 才能真正参与比较与聚合。
        // MySQLLoader 已开 tinyInt1isBit=false，只有真正的 BIT/BOOLEAN 列会走到这里
        if (raw instanceof Boolean boolValue) {
            return boolValue ? 1 : 0;
        }

        if (raw instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (raw instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (raw instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (raw instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        if (raw instanceof LocalDate date) {
            return date.toString();
        }
        if (raw instanceof LocalTime time) {
            return time.toString();
        }

        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        // 兜底：其余类型一律按字符串处理
        return String.valueOf(raw);
    }

    // 字符串归一：整数字符串转 Integer，小数字符串转 BigDecimal，其余原样返回。
    // 与 MySQL 侧的数值类型保持同一套查询语义
    private static Object normalizeString(String str) {
        String trimmed = str.trim();
        if (trimmed.isEmpty()) {
            return str;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            // 不是整数，继续尝试小数与大数
        }
        BigDecimal decimal = parseDecimal(trimmed);
        return decimal != null ? plain(decimal) : str;
    }

    // 只有能被 BigDecimal 完整解析的字符串才视为数值
    private static BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 整型来源归一：能精确收进 int 就收成 Integer，否则保留 BigDecimal（不再降级为字符串）
    private static Object intOrDecimal(BigDecimal value) {
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            return plain(value);
        }
    }

    // 保证 BigDecimal 不出现科学计数法：scale 为负时 toString 会输出 1E+5 这类形态
    private static BigDecimal plain(BigDecimal value) {
        return value.scale() < 0 ? value.setScale(0) : value;
    }
}