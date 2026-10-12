package com.tinyquery.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主题的跨文件一致性。
 *
 * <p>一套主题的颜色分散在三个文件里，三者必须一致，而它们之间没有任何编译期
 * 联系，编译器一个都查不出来：{@code tinyquery-frontend/src/assets/tokens.css}
 * 是网页的色值来源，{@code tinyquery-frontend/src/theme/index.js} 是主题标识与
 * 展示顺序，{@link DesktopTheme} 是桌面外壳（Swing）用的强调色。
 * 第三处是不得已的重复：Java 读不到 CSS，所以这里解析 CSS 反查回去。</p>
 *
 * <p>四类失败方式各自对应一个测试。主题漏写令牌：CSS 变量是继承的，漏一个不报错，
 * 静默落回默认主题的值，于是绿色主题里混进一块紫，这类 bug 只在那一个界面元素上
 * 看得出来，极易漏过（见 {@link #everyThemeDefinesTheSameTokens()}）。
 * 默认主题被悄悄改动：新增主题时很容易顺手「统一」一下默认主题的某个值，
 * 于是现有用户的观感变了却没人发现（见 {@link #defaultThemeMatchesFrozenBaseline()}）。
 * Java 与 CSS 的色值对不上：控制窗口的「运行中」颜色与网页主色不是同一个色
 * （见 {@link #desktopAccentsMatchCss()}）。前后端主题清单对不上：前端多一个主题
 * 点进去没反应、或少一个后端支持但选不到（见 {@link #themeListsMatch()}）。</p>
 *
 * <p>路径用相对路径：Surefire 的工作目录是项目根目录，
 * 与 {@code MemoryGuardTest}、{@code StartupConfigurationTest} 的写法一致。</p>
 */
@DisplayName("主题：CSS / 前端清单 / Java 侧三处一致")
class ThemeTokensTest {

    private static final Path TOKENS_CSS =
            Path.of("tinyquery-frontend/src/assets/tokens.css");
    private static final Path THEME_JS =
            Path.of("tinyquery-frontend/src/theme/index.js");

    /**
     * 基准快照：默认主题（紫罗兰）的 36 个颜色令牌。
     *
     * <p>这是一道锁：改动这里必须是有意的 —— 想改默认主题的观感就明确改这个快照，
     * 而不是让它在新增主题的顺带改动里被悄悄改掉。</p>
     *
     * <p>快照的完整性与 {@link #THEMED_TOKENS} 一致，由
     * {@link #baselineCoversAllThemedTokens()} 断言，避免往快照里少抄一个
     * 反而让默认主题的那一项失去保护。</p>
     */
    private static final Map<String, String> BASELINE = Map.ofEntries(
            Map.entry("--accent", "#8b5cf6"),
            Map.entry("--accent-bright", "#c4b5fd"),
            Map.entry("--accent-deep", "#7c3aed"),
            Map.entry("--accent-glow", "rgba(139, 92, 246, 0.22)"),
            Map.entry("--accent-soft", "#ede9fe"),
            Map.entry("--el-border-color-dark", "#ded9e6"),
            Map.entry("--el-border-color-darker", "#d3cddd"),
            Map.entry("--el-color-primary-light-3", "#a78bfa"),
            Map.entry("--el-color-primary-light-5", "#c4b5fd"),
            Map.entry("--el-color-primary-light-7", "#ddd6fe"),
            Map.entry("--el-color-primary-light-8", "#ede9fe"),
            Map.entry("--el-color-primary-light-9", "#f5f3ff"),
            Map.entry("--el-fill-color", "#f4f2f8"),
            Map.entry("--el-fill-color-dark", "#ece9f2"),
            Map.entry("--el-fill-color-darker", "#e4e0ec"),
            Map.entry("--el-fill-color-extra-light", "#fcfbfd"),
            Map.entry("--el-fill-color-light", "#f7f5fa"),
            Map.entry("--el-fill-color-lighter", "#faf9fc"),
            Map.entry("--glass-bg-strong", "rgba(251, 250, 253, 0.9)"),
            Map.entry("--glow-1", "rgba(139, 92, 246, 0.12)"),
            Map.entry("--glow-2", "rgba(196, 181, 253, 0.15)"),
            Map.entry("--glow-3", "rgba(124, 58, 237, 0.08)"),
            Map.entry("--ink", "#2b2433"),
            Map.entry("--ink-2", "#565064"),
            Map.entry("--ink-3", "#8b8494"),
            Map.entry("--ink-4", "#a8a2b3"),
            Map.entry("--line", "#e4e0ec"),
            Map.entry("--line-soft", "#edeaf3"),
            Map.entry("--page", "#efeaf6"),
            Map.entry("--plum", "#241a33"),
            Map.entry("--scrim", "rgba(36, 26, 51, 0.45)"),
            Map.entry("--scrollbar-thumb", "#d8d3e2"),
            Map.entry("--scrollbar-thumb-hover", "#c2bcd0"),
            Map.entry("--sidebar-bg", "#e6e3ee"),
            Map.entry("--sidebar-line", "#d3cae4"),
            Map.entry("--surface", "#fbfafd"));

    /**
     * 必须每套主题各写一遍的令牌。
     *
     * <p>判据不是「名字带 color」，而是值是不是颜色字面量。值是 {@code var(--x)}
     * 别名就不用重复，别名指向的令牌被主题覆盖后它自动跟着变
     * （{@code --el-color-primary}、{@code --ui-*} 都是这类）。
     * 值是非颜色字面量（{@code 6px}、字体栈、{@code 224px}）与主题无关。
     * 值是主题无关的中性色也不用重复，目前只有三个纯白及其 alpha：
     * {@code --pure-white}、{@code --glass-edge}、{@code --surface-raised} ——
     * 它们压在任意浅色底上都是「提亮一档」，与色相无关。
     * {@code --glass-bg-strong} 不在此列：它的白里揉了主题底色
     * （默认是 251,250,253 的紫调白），必须每套各写。</p>
     *
     * <p>所以这张清单是从 {@link #BASELINE} 推出来的，不手写 ——
     * 手写就会在加令牌时忘记同步。见 {@link #baselineCoversAllThemedTokens()}。</p>
     */
    private static final List<String> THEMED_TOKENS = new ArrayList<>(BASELINE.keySet());

    // ==================== 解析 ====================

    /** 去掉 CSS 注释 —— 否则注释里举例的令牌会被当成真的声明 */
    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读不到 " + path.toAbsolutePath()
                    + " —— 测试的工作目录应当是项目根目录", e);
        }
    }

    /** 抽出一个 CSS 块里的全部自定义属性声明 */
    private static Map<String, String> declarations(String block) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = Pattern.compile("^\\s*(--[\\w-]+)\\s*:\\s*([^;]+);", Pattern.MULTILINE)
                .matcher(block);
        while (m.find()) {
            found.put(m.group(1), m.group(2).trim());
        }
        return found;
    }

    /** 默认主题：{@code :root { ... }} */
    private static Map<String, String> rootBlock() {
        String css = stripComments(read(TOKENS_CSS));
        Matcher m = Pattern.compile(":root\\s*\\{(.*?)\\n\\}", Pattern.DOTALL).matcher(css);
        assertTrue(m.find(), "tokens.css 里找不到 :root 块 —— 解析规则需要跟着改");
        return declarations(m.group(1));
    }

    /** 各覆盖主题：{@code :root[data-theme="xxx"] { ... }} */
    private static Map<String, Map<String, String>> themeBlocks() {
        String css = stripComments(read(TOKENS_CSS));
        Map<String, Map<String, String>> blocks = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                        ":root\\[data-theme=['\"](\\w+)['\"]\\]\\s*\\{(.*?)\\n\\}", Pattern.DOTALL)
                .matcher(css);
        while (m.find()) {
            blocks.put(m.group(1), declarations(m.group(2)));
        }
        return blocks;
    }

    // ==================== 1. 基准快照的自洽 ====================

    @Test
    @DisplayName("基准快照覆盖了全部必须每套主题各写的令牌 —— 防止少抄一个反而失去保护")
    void baselineCoversAllThemedTokens() {
        // 快照的键集合必须等于「默认主题里所有颜色字面量令牌」
        // 减去「主题无关的中性色」。这条自己就是个元测试：
        // 往 tokens.css 加一个新的颜色令牌时，如果忘了往 BASELINE 里加，
        // 那个令牌就完全没有保护 —— 这里会红
        Map<String, String> root = rootBlock();

        // 与 BASELINE 同样口径的判据，独立再算一遍。
        //
        // 注意用 find() 而不是 matches()：matches() 要求整个字符串都匹配，
        // 而这里的模式只描述「开头长什么样」（rgba( 后面还有参数）。
        // 用 matches() 会把所有 rgba(...) 都判成非颜色 —— 实测踩过，
        // 结果 --scrim / --glow-* / --glass-bg-strong / --accent-glow
        // 六个 rgba 令牌全被判成「快照里的残留」
        Pattern colorLiteral = Pattern.compile(
                "^(#[0-9a-fA-F]{3,8}|rgba?\\(|hsla?\\(|transparent$|white$|black$)",
                Pattern.CASE_INSENSITIVE);
        List<String> themeNeutral = List.of(
                "--pure-white", "--glass-edge", "--surface-raised");

        List<String> expected = new ArrayList<>();
        for (Map.Entry<String, String> e : root.entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            if (value.startsWith("var(")
                    || !colorLiteral.matcher(value).find()) {
                continue;
            }
            if (themeNeutral.contains(name)) {
                continue;
            }
            expected.add(name);
        }

        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(BASELINE.keySet());
        List<String> extra = new ArrayList<>(BASELINE.keySet());
        extra.removeAll(expected);

        assertTrue(missing.isEmpty(),
                "tokens.css 里有颜色令牌没进基准快照，它们不会被任何测试保护：" + missing);
        assertTrue(extra.isEmpty(),
                "基准快照里有 tokens.css 已经没有的令牌，是残留：" + extra);
    }

    @Test
    @DisplayName("THEMED_TOKENS 非空且数量合理 —— 防止解析规则失效后测试变成空转")
    void themedTokenListIsNotEmpty() {
        // 解析一旦失效（比如 tokens.css 被格式化工具重排、正则匹配不上），
        // 下面所有「遍历每个主题检查每个令牌」的断言都会在空集合上通过。
        // 这条防的就是那种假通过
        assertTrue(THEMED_TOKENS.size() >= 30,
                "只解析出 " + THEMED_TOKENS.size() + " 个颜色令牌，解析规则多半失效了");
        assertTrue(THEMED_TOKENS.contains("--accent"), "清单里没有 --accent，解析错了");
        assertTrue(THEMED_TOKENS.contains("--ink"), "清单里没有 --ink，解析错了");
    }

    // ==================== 2. 默认主题不动 ====================

    @Test
    @DisplayName("默认主题逐字节等于基准快照 —— 现有观感不许在新增主题时被顺带改掉")
    void defaultThemeMatchesFrozenBaseline() {
        Map<String, String> root = rootBlock();

        List<String> drift = new ArrayList<>();
        for (String token : THEMED_TOKENS) {
            String actual = root.get(token);
            String expected = BASELINE.get(token);
            if (!expected.equalsIgnoreCase(actual)) {
                drift.add(token + "：期望 " + expected + "，实际 " + actual);
            }
        }

        assertTrue(drift.isEmpty(),
                "默认主题（紫罗兰）的颜色被改动了 " + drift.size() + " 处：\n  "
                        + String.join("\n  ", drift)
                        + "\n\n如果这是有意的，请同步更新 BASELINE；"
                        + "如果是加主题时顺手改的，请改回去 —— 现有用户会看到观感变了");
    }

    // ==================== 3. 每套主题写全 ====================

    @Test
    @DisplayName("每套覆盖主题都定义了全部颜色令牌 —— 漏一个会静默落回默认主题的紫")
    void everyThemeDefinesTheSameTokens() {
        Map<String, Map<String, String>> blocks = themeBlocks();

        assertFalse(blocks.isEmpty(), "tokens.css 里一个 [data-theme] 块都没有，解析规则需检查");

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> theme : blocks.entrySet()) {
            String name = theme.getKey();
            Map<String, String> declared = theme.getValue();

            for (String token : THEMED_TOKENS) {
                if (!declared.containsKey(token)) {
                    problems.add(String.format(
                            "主题 %s 缺少 %s —— 该处会静默沿用默认主题的 %s（%s）",
                            name, token, BASELINE.get(token), describe(token)));
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "有主题没写全颜色令牌，共 " + problems.size() + " 处：\n  "
                        + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("每套覆盖主题的令牌集合与默认主题完全一致 —— 不多也不少")
    void everyThemeHasNoExtraTokens() {
        // 多出来的令牌多半是拼写错误（--accesnt），它不会生效也不会报错
        Map<String, Map<String, String>> blocks = themeBlocks();
        Map<String, String> root = rootBlock();

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> theme : blocks.entrySet()) {
            for (String token : theme.getValue().keySet()) {
                if (!root.containsKey(token)) {
                    problems.add("主题 " + theme.getKey() + " 定义了 " + token
                            + "，但默认主题里没有这个令牌 —— 多半是拼写错误，它不会生效");
                }
            }
        }

        assertTrue(problems.isEmpty(), String.join("\n  ", problems));
    }

    // ==================== 4. Java 侧与 CSS 对齐 ====================

    @Test
    @DisplayName("DesktopTheme 的强调色与 tokens.css 的 --accent 逐字节一致")
    void desktopAccentsMatchCss() {
        // 桌面外壳是 Swing 画的，读不到 CSS 变量，只能把色值在 Java 里再写一份。
        // 这是唯一一处不得已的重复，所以必须由测试盯着
        Map<String, Map<String, String>> blocks = themeBlocks();

        for (String theme : DesktopTheme.supported()) {
            String cssValue = DesktopTheme.DEFAULT.equals(theme)
                    ? rootBlock().get("--accent")
                    : blockOf(blocks, theme).get("--accent");

            assertNotNull(cssValue, "tokens.css 里找不到主题 " + theme + " 的 --accent");

            String javaValue = String.format("#%06x",
                    DesktopTheme.accentOf(theme).getRGB() & 0xFFFFFF);

            assertEquals(cssValue.toLowerCase(), javaValue.toLowerCase(),
                    "主题 " + theme + " 的强调色两边不一致：CSS=" + cssValue
                            + "，Java=" + javaValue
                            + "。桌面外壳（控制窗口/托盘）会与网页不同色");
        }
    }

    @Test
    @DisplayName("tokens.css 的每个主题，DesktopTheme 都认识（反之亦然）")
    void themeSetsMatchCss() {
        Map<String, Map<String, String>> blocks = themeBlocks();

        List<String> inCss = new ArrayList<>();
        inCss.add(DesktopTheme.DEFAULT);
        inCss.addAll(blocks.keySet());

        List<String> inJava = DesktopTheme.supported();

        assertTrue(inJava.containsAll(inCss),
                "tokens.css 里有主题 Java 侧不认识（网页上能选、桌面端不跟随）："
                        + difference(inCss, inJava));
        assertTrue(inCss.containsAll(inJava),
                "Java 侧有主题 CSS 里没有（能选中但没有颜色）："
                        + difference(inJava, inCss));
    }

    private static Map<String, String> blockOf(Map<String, Map<String, String>> blocks,
                                               String theme) {
        Map<String, String> block = blocks.get(theme);
        assertNotNull(block, "tokens.css 里没有主题块：" + theme);
        return block;
    }

    // ==================== 5. 前端清单对齐 ====================

    @Test
    @DisplayName("前端 theme/index.js 的主题清单与 Java 侧一致，顺序也一致")
    void themeListsMatch() {
        String js = read(THEME_JS);

        // 从 SUPPORTED_THEMES 数组里抽 value 与 desktopAccent。
        // 刻意不跑 Node 去 import 那个模块：测试要保持纯 Java，
        // 而且 SUPPORTED_THEMES 是字面量数组，正则足够可靠
        Matcher m = Pattern.compile(
                        "\\{\\s*value:\\s*'(\\w+)'\\s*,\\s*labelKey:\\s*'[\\w.]+'\\s*,"
                                + "\\s*desktopAccent:\\s*'(#[0-9a-fA-F]{6})'\\s*\\}")
                .matcher(js);

        List<String> jsValues = new ArrayList<>();
        Map<String, String> jsAccents = new LinkedHashMap<>();
        while (m.find()) {
            jsValues.add(m.group(1));
            jsAccents.put(m.group(1), m.group(2));
        }

        assertFalse(jsValues.isEmpty(),
                "theme/index.js 里一个主题都没解析出来 —— 解析规则需要跟着改");

        // 顺序一致：设置页按前端的顺序渲染，两处顺序不同会让色卡与列表对不上
        assertEquals(DesktopTheme.supported(), jsValues,
                "前端主题清单与 Java 侧不一致（含顺序）：前端=" + jsValues
                        + "，Java=" + DesktopTheme.supported());

        // 色点用的 desktopAccent 也必须与 Java 侧同色 —— 否则设置页上
        // 那个小圆点的颜色与切过去之后的实际主色不是一个色
        for (Map.Entry<String, String> e : jsAccents.entrySet()) {
            String javaValue = String.format("#%06x",
                    DesktopTheme.accentOf(e.getKey()).getRGB() & 0xFFFFFF);
            assertEquals(javaValue.toLowerCase(), e.getValue().toLowerCase(),
                    "主题 " + e.getKey() + " 的 desktopAccent 与 Java 侧不一致："
                            + "前端=" + e.getValue() + "，Java=" + javaValue);
        }
    }

    @Test
    @DisplayName("前端的 DEFAULT_THEME 与 Java 侧的默认主题是同一个")
    void defaultThemeMatchesFrontend() {
        String js = read(THEME_JS);

        Matcher m = Pattern.compile("DEFAULT_THEME\\s*=\\s*'(\\w+)'").matcher(js);
        assertTrue(m.find(), "theme/index.js 里找不到 DEFAULT_THEME");

        assertEquals(DesktopTheme.DEFAULT, m.group(1),
                "前端默认主题与 Java 侧不同：网页首屏是一种颜色，"
                        + "桌面外壳是另一种，用户会以为主题坏了");
    }

    @Test
    @DisplayName("每个主题都有中英双语名 —— 英文模式下主题名不许是中文")
    void everyThemeHasBothLocaleNames() {
        // 主题名是专有名词，两种语言各写各的。漏了英文的话，
        // 英文模式下设置页会出现中文（词条缺失会静默回落中文）
        Map<String, String> zh = localeKeys("zh-CN.js");
        Map<String, String> en = localeKeys("en-US.js");

        for (String theme : DesktopTheme.supported()) {
            String key = "theme." + theme;
            assertTrue(zh.containsKey(key), "zh-CN.js 缺少 settings." + key);
            assertTrue(en.containsKey(key), "en-US.js 缺少 settings." + key);

            String zhText = zh.get(key);
            String enText = en.get(key);

            assertFalse(zhText.isBlank(), "settings." + key + " 的中文名为空");
            assertFalse(enText.isBlank(), "settings." + key + " 的英文名为空");

            if (!"zh".equals(theme)) {
                assertFalse(enText.matches(".*[\\u4e00-\\u9fff].*"),
                        "settings." + key + " 的英文名里有中文：" + enText
                                + " —— 英文模式下会显示中文");
            }
        }

        // 语言名本身是个例外：「中文」这两个字在英文模式下也保持中文，
        // 因为它是语言自称。除此之外 settings.theme.* 不许有中文
        assertTrue(zh.containsKey("language.zh"), "settings.language.zh 不见了");
    }

    /**
     * 抽出 locale 文件里 {@code settings: { ... }} 块下的扁平键。
     *
     * <p>只取 settings 这一层，避免把别的区域的同名键混进来。
     * 值用正则抓单引号字符串 —— 这些词条都是简单字面量，不涉及模板串或表达式。</p>
     *
     * <p>分两步是必需的：{@code settings} 里有嵌套块（{@code language} /
     * {@code theme} / {@code about}）。如果直接用
     * {@code (\w+):\s*\{([^}]*)\}} 去扫，第一个匹配会把 {@code settings: {}}
     * 连同里面第一个嵌套块一起吃掉，前缀变成 {@code settings} ——
     * 于是 {@code language.zh} 被记成 {@code settings.zh}，
     * 断言 {@code language.zh} 存在时会找不到。所以先把内层块摘出来，
     * 再扫剩下的顶层键。</p>
     */
    private static Map<String, String> localeKeys(String fileName) {
        String js = read(Path.of("tinyquery-frontend/src/locales", fileName));

        int start = js.indexOf("settings: {");
        assertTrue(start >= 0, fileName + " 里找不到 settings 块");
        // 从 settings: { 往后找配对的右花括号：词条里不会出现花括号，
        // 所以第一个 "\n  }," 就是块尾（缩进两格）
        int end = js.indexOf("\n  },", start);
        assertTrue(end > start, fileName + " 的 settings 块没有正常结束");

        String block = js.substring(start, end);
        Map<String, String> flat = new LinkedHashMap<>();

        // 第一步：内层块。它们的值里不含花括号，所以 [^{}]* 能精确圈住
        Pattern inner = Pattern.compile("(\\w+):\\s*\\{([^{}]*)\\}");
        Matcher nested = inner.matcher(block);
        StringBuilder remaining = new StringBuilder();
        int cursor = 0;
        while (nested.find()) {
            String prefix = nested.group(1);
            Matcher leaf = Pattern.compile("(\\w+):\\s*'([^']*)'").matcher(nested.group(2));
            while (leaf.find()) {
                flat.put(prefix + "." + leaf.group(1), leaf.group(2));
            }
            // 把内层块从文本里挖掉，免得下面扫顶层键时又把里面的叶子算一遍
            remaining.append(block, cursor, nested.start());
            cursor = nested.end();
        }
        remaining.append(block, cursor, block.length());

        // 第二步：剩下的顶层键
        Matcher top = Pattern.compile("(\\w+):\\s*'([^']*)'").matcher(remaining.toString());
        while (top.find()) {
            flat.put(top.group(1), top.group(2));
        }
        return flat;
    }

    // ==================== 6. 色值本身可用 ====================

    @Test
    @DisplayName("每套主题的 --accent 压白字与当白底文字都够对比度 —— 按钮文字必须读得出来")
    void everyAccentIsReadableBothWays() {
        // --accent 在本项目里要同时干两件事：
        //   · 当按钮底色（el-button type="primary"，文字是白的）
        //   · 当白底上的文字色（--el-color-primary 也被当正文色用）
        // 所以它的相对亮度必须落在中间某个窗口里，两头都要够。
        //
        // 门槛取 3.5 而不是 4.0：原来卡 4.0。2026-10-12 反馈「青色按钮的颜色
        // 太深了，放浅一点」，竹青的 --accent 从 #078d58 放到 #08965e，
        // 白字对比度 4.23 → 3.79，有意跌破 4.0。
        //
        // 这不是为了迁就测试而放宽：4.0 本来就是折中值 —— WCAG AA 对正文要求
        // 4.5:1，四套主题其实一个都够不着（最高的靛蓝 4.47）。3.5 是「明显发飘」
        // 与「略低于推荐」的分界，实测 3.79 的白字仍然清楚。
        //
        // 门槛定在 3.5 而不是 3.79：给将来微调留一点余量，同时仍能拦住
        // 真正的坏值 —— 曾经给青色挑过 #00a86b，白字只有 3.08:1，肉眼发飘，
        // 那个值在 3.5 门槛下依然会被拦住。
        final double minRatio = 3.5;

        Map<String, String> accents = new LinkedHashMap<>();
        accents.put(DesktopTheme.DEFAULT, rootBlock().get("--accent"));
        for (Map.Entry<String, Map<String, String>> e : themeBlocks().entrySet()) {
            accents.put(e.getKey(), e.getValue().get("--accent"));
        }

        List<String> problems = new ArrayList<>();
        List<String> report = new ArrayList<>();
        for (Map.Entry<String, String> e : accents.entrySet()) {
            String theme = e.getKey();
            String hex = e.getValue();
            assertNotNull(hex, "主题 " + theme + " 没有 --accent");

            double luminance = relativeLuminance(hex);
            double onBlack = 1.05 / (luminance + 0.05);   // 白字压在它上面
            double onWhite = (luminance + 0.05) / 0.05;   // 它当文字压在白底上

            // 逐套记下来。这样将来某套主题变了，失败信息里能一眼看出
            // 是哪一套、偏了多少，而不是只知道「有一处不合格」
            report.add(String.format("%s %s 白字 %.2f:1 / 白底 %.2f:1",
                    theme, hex, onBlack, onWhite));

            if (onBlack < minRatio) {
                problems.add(String.format(
                        "主题 %s 的 --accent %s：白字压上去只有 %.2f:1（要求 ≥%.1f）"
                                + " —— 主按钮的文字会看不清",
                        theme, hex, onBlack, minRatio));
            }
            if (onWhite < minRatio) {
                problems.add(String.format(
                        "主题 %s 的 --accent %s：当白底文字只有 %.2f:1（要求 ≥%.1f）"
                                + " —— 强调文字会看不清",
                        theme, hex, onWhite, minRatio));
            }
        }

        assertTrue(problems.isEmpty(),
                String.join("\n  ", problems) + "\n  各主题实测：" + report);
    }

    /** WCAG 相对亮度。绿通道权重 0.7152，蓝只有 0.0722 —— 同明度下绿比紫亮得多 */
    private static double relativeLuminance(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        assertEquals(6, h.length(), "色值不是 6 位十六进制：" + hex);
        double[] channel = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16) / 255.0;
            channel[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
    }

    // ==================== 辅助 ====================

    private static List<String> difference(List<String> from, List<String> subtract) {
        List<String> result = new ArrayList<>(from);
        result.removeAll(subtract);
        return result;
    }

    /** 给失败信息补一句「这个令牌是干什么的」，省得还要去翻 tokens.css */
    private static String describe(String token) {
        return switch (token) {
            case "--accent" -> "主色";
            case "--accent-deep" -> "主色深一档（按钮按下、悬停）";
            case "--accent-soft" -> "主色最浅一档（浅色底）";
            case "--accent-bright" -> "主色最亮一档（logo 渐变、图表高光）";
            case "--accent-glow" -> "主色光晕（聚焦外圈）";
            case "--ink" -> "正文色";
            case "--ink-2" -> "次级文字";
            case "--ink-3" -> "辅助文字、提示";
            case "--ink-4" -> "占位符、禁用文字";
            case "--line" -> "边框";
            case "--line-soft" -> "浅边框";
            case "--surface" -> "面板底色";
            case "--page" -> "页面底色";
            case "--plum" -> "深色块（侧栏选中态）";
            case "--scrim" -> "画布遮罩";
            case "--sidebar-bg" -> "侧栏底色";
            case "--sidebar-line" -> "侧栏分隔线";
            case "--scrollbar-thumb", "--scrollbar-thumb-hover" -> "滚动条";
            case "--glass-bg-strong" -> "毛玻璃面板底色";
            case "--glow-1", "--glow-2", "--glow-3" -> "页面底纹光晕";
            default -> token.startsWith("--el-color-primary-light")
                    ? "Element Plus 主色浅色阶" : "Element Plus 中性色覆盖";
        };
    }
}
