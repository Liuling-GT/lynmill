package com.tinyquery.datasource;

import java.util.List;

/**
 * 数据源类型描述，供前端动态渲染配置表单。
 *
 * <p>只下发「这个类型需要哪些字段、字段是什么值类型、是否敏感、是否必填」，
 * 不下发控件类型、宽度、校验规则等 UI 细节，那些由前端通用渲染器自行决定。</p>
 *
 * @param type   类型标识，与 DataLoader#type() 一致
 * @param label  类型的展示名称
 * @param fields 该类型需要的配置项清单
 */
public record DataSourceTypeInfo(String type, String label, List<FieldSpec> fields) {

    public DataSourceTypeInfo {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}