package com.tinyquery.datasource;

import com.tinyquery.executor.ColumnStore;

//将数据源名字，类型，配置项以及数据打包在一起
public record DataSourceEntry(DataSourceConfig config, ColumnStore store) {
}