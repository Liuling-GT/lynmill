package com.tinyquery.datasource;

import com.tinyquery.executor.ColumnStore;

import java.util.List;

/**
 * 采用注册表模式
 * 数据源加载器。每支持一种数据源，实现一个本接口的 Spring Bean 即可
 */
public interface DataLoader {
    String type();
    String label();
    List<FieldSpec> fields();
    ColumnStore load(DataSourceConfig config);
}