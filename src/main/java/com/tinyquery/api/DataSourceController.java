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
import java.util.Map;

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
    public DataSourceResponse<String> register(@RequestBody RegisterRequest request) {
        // savePassword 缺省为 false：老前端（不带这个字段）的行为与从前完全一致，
        // 不会因为升级后端就突然把用户的密码写进系统凭据
        boolean savePassword = Boolean.TRUE.equals(request.savePassword());
        manager.register(request.toConfig(), savePassword);
        return DataSourceResponse.success(request.toConfig().name());
    }

    /**
     * 注册请求体。
     *
     * <p>没有直接把 {@code savePassword} 塞进 DataSourceConfig：那个 record 是
     * 「数据源本身的配置」，{@code savePassword} 是「本次操作的一个选项」，
     * 两者生命周期不同 —— 前者要落盘，后者不落盘。混在一起会让
     * {@code withoutSecrets} 那种「哪些字段该写进文件」的逻辑变得含糊。</p>
     */
    public record RegisterRequest(String name, String type,
                                  Map<String, Object> properties,
                                  Boolean savePassword) {

        DataSourceConfig toConfig() {
            return new DataSourceConfig(name, type,
                    properties == null ? Map.of() : properties);
        }
    }

    // 系统是否支持「保存密码」，以及不支持时的原因。前端据此决定勾选框是否可点
    @Operation(summary = "Credential Storage Status",
            description = "Whether the OS credential store is available for saving passwords")
    @GetMapping("/credential-status")
    public DataSourceResponse<CredentialStatus> credentialStatus() {
        boolean available = manager.isCredentialStorageAvailable();
        return DataSourceResponse.success(new CredentialStatus(
                available,
                available ? "" : manager.credentialStorageUnavailableReason()));
    }

    public record CredentialStatus(boolean available, String reason) {
    }

    // 测试数据源配置是否可用（不注册、不加载数据）
    @Operation(summary = "Test Data Source", description = "Probe the connection and target object without loading data")
    @PostMapping("/test")
    public DataSourceResponse<ConnectionTestResult> test(@RequestBody DataSourceConfig config) {
        return DataSourceResponse.success(manager.test(config));
    }

    /**
     * 探测配置项的可选值，供表单把它渲染成下拉框。
     *
     * <p>典型用途：Excel 数据源点「探测」按钮列出所有工作表名。
     * 请求体与 {@code /test} 相同（已填到一半的配置），返回
     * 「字段名 → 可选值列表」。</p>
     *
     * <p>后端不认识任何具体数据源类型 —— 由各加载器自己实现
     * {@code probeOptions}，没有可探测内容的类型返回空 map。</p>
     */
    @Operation(summary = "Probe Field Options",
            description = "Return selectable values for config fields, e.g. worksheet names for an Excel file")
    @PostMapping("/probe")
    public DataSourceResponse<Map<String, List<String>>> probe(@RequestBody DataSourceConfig config) {
        return DataSourceResponse.success(manager.probeOptions(config));
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