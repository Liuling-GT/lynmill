package com.tinyquery.datasource.spec;

import java.util.List;

/**
 * 数据源类型描述，供前端动态渲染配置表单
 * @param type   类型标识，与 DataLoader#type() 一致
 * @param label  类型的展示名称
 * @param fields 该类型需要的配置项清单
 */
public record DataSourceTypeInfo(String type, String label, List<FieldSpec> fields) {

    public DataSourceTypeInfo {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}