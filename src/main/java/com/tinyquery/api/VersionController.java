package com.tinyquery.api;

import com.tinyquery.config.BuildInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 构建标识查询。界面底部会显示它，用来回答「我看到的到底是哪一版」。
 *
 * <p>不并进 {@code /api/ping}：那是心跳，前端每 2 秒问一次，而构建标识在一次运行里
 * 是常量，跟着心跳每 2 秒回一遍没有意义，还会让心跳的响应从固定小 JSON 变成有内容的
 * 东西 —— 心跳越小越稳定越好。
 *
 * <p>不需要单独鉴权：它和别的接口一样受 {@code TokenFilter} 保护，页面已通过
 * index.html 注入拿到令牌；构建标识不含敏感信息。
 */
@Tag(name = "Lifecycle", description = "Build identity shown in the UI")
@RestController
@RequestMapping("/api/version")
public class VersionController {

    @Operation(summary = "Build info",
            description = "Version, git short commit and build time. Fields are empty strings when unknown.")
    @GetMapping
    public DataSourceResponse<BuildInfoDto> version() {
        return DataSourceResponse.success(new BuildInfoDto(
                BuildInfo.version(), BuildInfo.commit(), BuildInfo.time(), BuildInfo.display()));
    }

    /**
     * 构建标识。
     *
     * <p>取不到的字段是空串而不是 null：前端要据此决定省略哪一段，
     * 空串比 null 少一层判空，且 JSON 里也不会出现 null 与 "" 两种"没有值"。</p>
     *
     * @param version 版本号，如 {@code 1.1.0}；未知时为空串
     * @param commit  git 短哈希，如 {@code e0dce58}；取不到时为空串
     * @param time    构建时间，如 {@code 2026-10-10 20:54}；未知时为空串
     * @param display 拼好的整行，如 {@code 1.1.0 · e0dce58 · 2026-10-10 20:54}；
     *                全都没有时为空串。前端直接显示这个即可，不必自己拼分隔符
     */
    public record BuildInfoDto(String version, String commit, String time, String display) {
    }
}
