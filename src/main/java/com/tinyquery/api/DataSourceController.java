package com.tinyquery.api;

import com.tinyquery.datasource.DataSourceConfig;
import com.tinyquery.datasource.DataSourceInfo;
import com.tinyquery.datasource.DataSourceManager;
import com.tinyquery.datasource.DataSourceTypeInfo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Data Source Management", description = "Register, list, and remove data sources")
@RestController
@RequestMapping("/api/datasource")
public class DataSourceController {

    private final DataSourceManager manager;

    public DataSourceController(DataSourceManager manager) {
        this.manager = manager;
    }

    // 注册数据源
    @Operation(summary = "Register Data Source", description = "Pass in the data source config and load data into memory.")
    @PostMapping
    public DataSourceResponse<String> register(@RequestBody DataSourceConfig config) {
        manager.register(config);
        return DataSourceResponse.success(config.name());
    }

    // 列出所有数据源（敏感字段已脱敏）
    @Operation(summary = "Enumerate Data Sources", description = "Return the list of registered data sources with sensitive fields masked")
    @GetMapping
    public DataSourceResponse<List<DataSourceInfo>> list() {
        return DataSourceResponse.success(manager.list());
    }

    // 列出支持的数据源类型及其配置项，供前端动态渲染表单
    @Operation(summary = "List Data Source Types", description = "Return supported types with their field specs for dynamic form rendering")
    @GetMapping("/types")
    public DataSourceResponse<List<DataSourceTypeInfo>> types() {
        return DataSourceResponse.success(manager.listTypes());
    }

    // 删除数据源
    @Operation(summary = "Remove Data Source", description = "Remove a data source by name")
    @DeleteMapping("/{name}")
    public DataSourceResponse<String> remove(@PathVariable String name) {
        manager.remove(name);
        return DataSourceResponse.success(name);
    }
}