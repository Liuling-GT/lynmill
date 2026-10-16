package com.tinyquery.datasource.spec;

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
 * @param probeable   是否可由「探测」按钮取得可选值（见 DataLoader#probeOptions）。
 *                    为 true 时前端在输入框旁渲染一个探测按钮，点一下把该字段
 *                    变成下拉框。用于「选项要打开文件/建连才知道」的字段，
 *                    例如 Excel 的工作表名 —— 那种字段没法用静态的 options
 */
public record FieldSpec(String name,
                        String label,
                        FieldValueType valueType,
                        boolean sensitive,
                        boolean required,
                        String description,
                        String placeholder,
                        List<String> options,
                        boolean probeable) {

    public FieldSpec {
        // 紧凑构造器：选项与文案归一，避免调用方判空
        options = options == null ? List.of() : List.copyOf(options);
        description = description == null ? "" : description;
        placeholder = placeholder == null ? "" : placeholder;
    }

    // 普通文本字段（非敏感）
    public static FieldSpec text(String name, String label, boolean required) {
        return new FieldSpec(name, label, FieldValueType.STRING, false, required,
                "", "", List.of(), false);
    }

    // 普通文本字段（非敏感，带说明与格式示例）
    public static FieldSpec text(String name, String label, boolean required,
                                 String description, String placeholder) {
        return new FieldSpec(name, label, FieldValueType.STRING, false, required,
                description, placeholder, List.of(), false);
    }

    /**
     * 可探测的文本字段：选项由「探测」按钮向后端要（见 DataLoader#probeOptions）。
     *
     * <p>与 ENUM 的区别是「选项从哪来」：ENUM 的选项是编译期常量，
     * 这个要打开文件才知道，所以只能等用户点了按钮再填进下拉框。</p>
     */
    public static FieldSpec probeableText(String name, String label, boolean required,
                                          String description, String placeholder) {
        return new FieldSpec(name, label, FieldValueType.STRING, false, required,
                description, placeholder, List.of(), true);
    }

    // 数值字段（非敏感）
    public static FieldSpec number(String name, String label, boolean required) {
        return new FieldSpec(name, label, FieldValueType.NUMBER, false, required,
                "", "", List.of(), false);
    }

    // 数值字段（非敏感，带说明与格式示例）
    public static FieldSpec number(String name, String label, boolean required,
                                   String description, String placeholder) {
        return new FieldSpec(name, label, FieldValueType.NUMBER, false, required,
                description, placeholder, List.of(), false);
    }

    // 布尔字段（非敏感）：前端渲染为开关，后端由 DataSourceConfig#getBoolean 读取
    public static FieldSpec bool(String name, String label, boolean required) {
        return new FieldSpec(name, label, FieldValueType.BOOLEAN, false, required,
                "", "", List.of(), false);
    }

    // 布尔字段（非敏感，带说明）
    public static FieldSpec bool(String name, String label, boolean required, String description) {
        return new FieldSpec(name, label, FieldValueType.BOOLEAN, false, required,
                description, "", List.of(), false);
    }

    // 敏感字段：值类型仍是 STRING，敏感性独立成轴
    public static FieldSpec secret(String name, String label, boolean required) {
        return new FieldSpec(name, label, FieldValueType.STRING, true, required,
                "", "", List.of(), false);
    }

    // 敏感字段（带说明与格式示例）
    public static FieldSpec secret(String name, String label, boolean required,
                                   String description, String placeholder) {
        return new FieldSpec(name, label, FieldValueType.STRING, true, required,
                description, placeholder, List.of(), false);
    }

    // 枚举字段
    public static FieldSpec select(String name, String label, boolean required, List<String> options) {
        return new FieldSpec(name, label, FieldValueType.ENUM, false, required,
                "", "", options, false);
    }

    // 枚举字段（带说明）
    public static FieldSpec select(String name, String label, boolean required,
                                   String description, List<String> options) {
        return new FieldSpec(name, label, FieldValueType.ENUM, false, required,
                description, "", options, false);
    }
}
