package com.tinyquery.api;

import com.tinyquery.desktop.DesktopSyncService;
import com.tinyquery.desktop.DesktopTheme;
import com.tinyquery.i18n.DesktopLocale;
import com.tinyquery.i18n.Messages;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 界面偏好设置：语言与主题。
 *
 * <p>网页上的偏好原本只存在浏览器里（localStorage），服务端不需要知道 ——
 * 语言每个请求都带 {@code X-TinyQuery-Locale} 头，服务端照着回就行；
 * 主题是纯 CSS 变量，服务端不参与渲染。但桌面外壳（托盘菜单、控制窗口）
 * 两者都拿不到：它的文案与颜色不在请求线程上取，也没有请求头可跟，
 * 只能在启动时读一次 {@code locale.txt} / {@code theme.txt}。
 * 本接口把网页上的选择同步给服务端并落盘，使两者保持一致，重启后仍然记得。
 *
 * <p>两个偏好分成两个端点，不合并成 {@code {locale, theme}}：两者的取值时机与
 * 失败语义不同，页面加载时要分别判断「本地有没有选择」。分开的端点让前端可以
 * 各自独立对齐，一个接口失败不影响另一个；合并后一次网络抖动会让两份设置一起退回默认。
 */
@Tag(name = "Preferences", description = "UI preferences that must be shared with the desktop shell")
@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

    private static final Logger log = LoggerFactory.getLogger(PreferencesController.class);

    private final DesktopSyncService desktopSync;

    public PreferencesController(DesktopSyncService desktopSync) {
        this.desktopSync = desktopSync;
    }

    /** 请求体。用 record 而不是 Map，字段名错时能直接报错而不是静默忽略 */
    public record LocaleRequest(String locale) {
    }

    /** 主题请求体。同上 */
    public record ThemeRequest(String theme) {
    }

    /**
     * 读取当前语言。
     *
     * <p>页面加载时需要知道服务端这一份，才能判断「要不要把本地选择同步过去」——
     * 例如用户换了台机器、或手工删了 localhost 的存储，本地是默认中文
     * 而服务端已经是英文时，应当以服务端为准而不是反过来覆盖它。</p>
     */
    @Operation(summary = "Get locale",
            description = "The language currently used by the desktop shell. "
                    + "The page uses it to decide whether its local choice needs syncing.")
    @GetMapping("/locale")
    public DataSourceResponse<Map<String, String>> getLocale() {
        return DataSourceResponse.success(Map.of("locale", desktopSync.currentLocale()));
    }

    /**
     * 切换语言。同时作用于网页、服务端返回的文案、以及桌面外壳。
     *
     * <p>不校验、不报错：认不出的语言值一律当中文（{@link Messages#normalize}）。
     * 语言不是安全边界，为它专门设计一套错误码不值得。</p>
     *
     * <p>写盘失败也不报错 —— 本次切换在内存里已经生效，只是重启后会退回默认。
     * 详见 {@link DesktopLocale#switchTo(String)}。</p>
     */
    @Operation(summary = "Set locale",
            description = "Switches the language for the page, server-side messages and the desktop shell. "
                    + "Unknown values fall back to Chinese.")
    @PostMapping("/locale")
    public DataSourceResponse<Map<String, String>> setLocale(@RequestBody(required = false) LocaleRequest request) {
        String requested = request == null ? null : request.locale();
        String applied = DesktopLocale.switchTo(requested);

        // 让已经显示出来的 Swing 界面跟上新语言。托盘菜单不需要在这里处理 ——
        // 它每次弹出前自己比对版本号
        desktopSync.onPreferencesChanged();

        // 记一行：桌面端语言是个容易被「为什么没变」困惑的地方，
        // 有这条日志就能一眼看出到底是没切成功、还是界面没刷新
        log.info("界面语言已切换为：{}（请求值：{}）", applied, requested);

        return DataSourceResponse.success(Map.of("locale", applied));
    }

    /** 支持的语言列表，供页面渲染选项。返回顺序即展示顺序 */
    @Operation(summary = "List supported locales")
    @GetMapping("/locales")
    public DataSourceResponse<List<Map<String, String>>> listLocales() {
        return DataSourceResponse.success(List.of(
                Map.of("value", Messages.ZH, "label", "中文"),
                Map.of("value", Messages.EN, "label", "English")));
    }

    /**
     * 读取当前主题。
     *
     * <p>与 {@link #getLocale()} 同理：页面加载时需要知道服务端这一份，
     * 才能判断「要不要把本地选择同步过去」。</p>
     */
    @Operation(summary = "Get theme",
            description = "The theme currently used by the desktop shell. "
                    + "The page uses it to decide whether its local choice needs syncing.")
    @GetMapping("/theme")
    public DataSourceResponse<Map<String, String>> getTheme() {
        return DataSourceResponse.success(Map.of("theme", desktopSync.currentTheme()));
    }

    /**
     * 切换主题。同时作用于网页、服务端记着的那一份、以及桌面外壳。
     *
     * <p>不校验、不报错：认不出的主题值一律回落默认主题
     * （{@link DesktopTheme#normalize}）。主题不是安全边界，
     * 为它专门设计一套错误码不值得。</p>
     *
     * <p>写盘失败也不报错 —— 本次切换在内存里已经生效，只是重启后会退回默认。
     * 详见 {@link DesktopTheme#switchTo(String)}。</p>
     */
    @Operation(summary = "Set theme",
            description = "Switches the theme for the page and the desktop shell. "
                    + "Unknown values fall back to the default theme.")
    @PostMapping("/theme")
    public DataSourceResponse<Map<String, String>> setTheme(@RequestBody(required = false) ThemeRequest request) {
        String requested = request == null ? null : request.theme();
        String applied = DesktopTheme.switchTo(requested);

        // 让已经显示出来的 Swing 界面换上新的强调色。托盘菜单不需要在这里处理 ——
        // 它每次弹出前自己比对版本号
        desktopSync.onPreferencesChanged();

        log.info("界面主题已切换为：{}（请求值：{}）", applied, requested);

        return DataSourceResponse.success(Map.of("theme", applied));
    }

    /**
     * 支持的主题列表，供页面渲染选项。返回顺序即展示顺序。
     *
     * <p>顺带把强调色一起给出去，让设置页的色点不必在前端再写一份色值 ——
     * 色值的唯一来源是 {@code tokens.css}，这里是它的 Java 侧镜像
     * （见 {@link DesktopTheme} 的类注释）。</p>
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：后者不保证迭代顺序，
     * 而这里返回的是一个有序列表。字段顺序只影响可读性，但没必要让它随机。</p>
     */
    @Operation(summary = "List supported themes")
    @GetMapping("/themes")
    public DataSourceResponse<List<Map<String, String>>> listThemes() {
        List<Map<String, String>> items = new ArrayList<>();
        for (String theme : DesktopTheme.supported()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("value", theme);
            item.put("accent", String.format("#%06x", DesktopTheme.accentOf(theme).getRGB() & 0xFFFFFF));
            items.add(item);
        }
        return DataSourceResponse.success(items);
    }
}
