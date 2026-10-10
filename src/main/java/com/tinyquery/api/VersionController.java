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
 * <h2>为什么不并进 /api/ping</h2>
 *
 * <p>{@code /api/ping} 是心跳，前端每 2 秒问一次。构建标识在一次运行里<b>是常量</b>，
 * 跟着心跳每 2 秒回一遍没有意义，还会让那个接口的响应从"一个固定小 JSON"
 * 变成有内容的东西 —— 心跳越小越稳定越好。</p>
 *
 * <h2>为什么不需要单独鉴权</h2>
 *
 * <p>它和别的接口一样受 {@code TokenFilter} 保护，页面已经通过 index.html
 * 注入拿到令牌。构建标识不含任何敏感信息（版本号与时间本来就是公开的），
 * 即便泄露也无影响。</p>
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
     * <p>取不到的字段是<b>空串而不是 null</b>：前端要据此决定省略哪一段，
     * 空串比 null 少一层判空，且 JSON 里也不会出现 null 与 "" 两种"没有值"。</p>
     *
     * @param version 版本号，如 {@code 1.0.0}；未知时为空串
     * @param commit  git 短哈希，如 {@code e0dce58}；取不到时为空串
     * @param time    构建时间，如 {@code 2026-10-10 20:54}；未知时为空串
     * @param display 拼好的整行，如 {@code 1.0.0 · e0dce58 · 2026-10-10 20:54}；
     *                全都没有时为空串。前端直接显示这个即可，不必自己拼分隔符
     */
    public record BuildInfoDto(String version, String commit, String time, String display) {
    }
}
