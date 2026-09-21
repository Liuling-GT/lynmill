package com.tinyquery.api;

import com.tinyquery.datasource.*;

import com.tinyquery.datasource.spec.ConnectionTestResult;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceInfo;
import com.tinyquery.datasource.spec.DataSourceTypeInfo;
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

    // 测试数据源配置是否可用（不注册、不加载数据）
    @Operation(summary = "Test Data Source", description = "Probe the connection and target object without loading data")
    @PostMapping("/test")
    public DataSourceResponse<ConnectionTestResult> test(@RequestBody DataSourceConfig config) {
        return DataSourceResponse.success(manager.test(config));
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

    // 重新加载数据源（用于数据文件或数据库内容变化后同步）
    @Operation(summary = "Refresh Data Source", description = "Reload a registered data source to pick up changes in the underlying file or table")
    @PostMapping("/{name}/refresh")
    public DataSourceResponse<String> refresh(@PathVariable String name) {
        manager.refresh(name);
        return DataSourceResponse.success(name);
    }

    // 删除数据源
    @Operation(summary = "Remove Data Source", description = "Remove a data source by name")
    @DeleteMapping("/{name}")
    public DataSourceResponse<String> remove(@PathVariable String name) {
        manager.remove(name);
        return DataSourceResponse.success(name);
    }
}