package com.tinyquery.datasource;

import java.util.List;

/**
 * 数据源配置项描述。
 *
 * @param name        配置项键名，与 DataSourceConfig.properties 的 key 对应
 * @param label       展示名称，保持简短，说明性文字放 description
 * @param valueType   值类型（控件选择依据）
 * @param sensitive   是否敏感（掩码依据，且驱动服务端脱敏）
 * @param required    是否必填
 * @param description 补充说明，前端以图标悬停的方式展示；无说明时传空串
 * @param placeholder 输入框内的格式示例，仅在需要示例时给出；无示例时传空串
 * @param options     仅 ENUM 使用，其余类型传 List.of()
 */
public record FieldSpec(String name,
                        String label,
                        FieldValueType valueType,
                        boolean sensitive,
                        boolean required,
                        String description,
                        String placeholder,
                        List<String> options) {

    public FieldSpec {
        // 紧凑构造器：选项与文案归一，避免调用方判空
        options = options == null ? List.of() : List.copyOf(options);
        description = description == null ? "" : description;
        placeholder = placeholder == null ? "" : placeholder;
    }

    // 普通文本字段（非敏感）
    public static FieldSpec text(String name, String label, boolean required) {
        return new FieldSpec(name, label, FieldValueType.STRING, false, required, "", "", List.of());
    }

    // 普通文本字段（非敏感，带说明与格式示例）
    public static FieldSpec text(String name, String label, boolean required,
                                 String description, String placeholder) {
        return new FieldSpec(name, label, FieldValueType.STRING, false, required,
                description, placeholder, List.of());
    }

    // 数值字段（非敏感）
    public static FieldSpec number(String name, String label, boolean required) {
        return new FieldSpec(name, label, FieldValueType.NUMBER, false, required, "", "", List.of());
    }

    // 数值字段（非敏感，带说明与格式示例）
    public static FieldSpec number(String name, String label, boolean required,
                                   String description, String placeholder) {
        return new FieldSpec(name, label, FieldValueType.NUMBER, false, required,
                description, placeholder, List.of());
    }

    // 布尔字段（非敏感）：前端渲染为开关，后端由 DataSourceConfig#getBoolean 读取
    public static FieldSpec bool(String name, String label, boolean required) {
        return new FieldSpec(name, label, FieldValueType.BOOLEAN, false, required, "", "", List.of());
    }

    // 布尔字段（非敏感，带说明）
    public static FieldSpec bool(String name, String label, boolean required, String description) {
        return new FieldSpec(name, label, FieldValueType.BOOLEAN, false, required,
                description, "", List.of());
    }

    // 敏感字段：值类型仍是 STRING，敏感性独立成轴
    public static FieldSpec secret(String name, String label, boolean required) {
        return new FieldSpec(name, label, FieldValueType.STRING, true, required, "", "", List.of());
    }

    // 敏感字段（带说明与格式示例）
    public static FieldSpec secret(String name, String label, boolean required,
                                   String description, String placeholder) {
        return new FieldSpec(name, label, FieldValueType.STRING, true, required,
                description, placeholder, List.of());
    }

    // 枚举字段
    public static FieldSpec select(String name, String label, boolean required, List<String> options) {
        return new FieldSpec(name, label, FieldValueType.ENUM, false, required,
                "", "", options);
    }

    // 枚举字段（带说明）
    public static FieldSpec select(String name, String label, boolean required,
                                   String description, List<String> options) {
        return new FieldSpec(name, label, FieldValueType.ENUM, false, required,
                description, "", options);
    }
}
