package com.tinyquery.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 普查：用户可见的报错有没有绕过 {@link Messages}。
 *
 * <p>与 {@link UserFacingMessageI18nTest} 的分工：那边靠实测，只能覆盖已知的几条；
 * 这边扫源码，能抓到「没人想到的那一条」—— 而漏网的恰恰总是那一条。</p>
 *
 * <p>为什么是测试而不是一次性脚本：脚本跑完就忘了，下次新增报错时没人会想起来跑。
 * 放进测试里，以后谁写了硬编码文案，构建当场红。</p>
 *
 * <p><b>判断标准</b>：抛出「会被展示给用户」的异常时，
 * 消息里不该出现硬编码的完整自然语言句子（中文或英文）。</p>
 *
 * <p><b>白名单</b>：确实不需要翻译的地方 —— 见 {@link #WHITELIST}。</p>
 */
class HardcodedUserMessageAuditTest {

    private static final Path SRC = Paths.get("src/main/java/com/tinyquery");

    /**
     * 白名单：这些是**故意**不翻译的。
     *
     * <p>每一条都要写清楚理由 —— 否则白名单会变成「掩盖问题的垃圾桶」。</p>
     *
     * <p>共同特征：它们都是<b>启动期或程序缺陷</b>，用户正常使用永远碰不到。
     * 翻译它们没有收益，反而会让「真正的用户可见文案」淹没在噪音里。</p>
     */
    private static final List<String> WHITELIST = List.of(
            // 程序缺陷：图标尺寸是代码里写死的常量，写错是开发期的事
            "AppIcons.java",
            // 仅 macOS：Windows 构建里到不了这段，且属于平台加载失败的技术细节
            "MacKeychain.java",
            // 启动期顺序错误：只在 WebServerInitializedEvent 之前调用才会抛，
            // 是给改代码的人看的，不是给用户看的
            "ServerPortHolder.java",
            // 启动即失败：两个 DataLoader 声明了同一个 type 标识。
            // 只有开发者新增数据源类型时才可能撞上，报的是类名，翻译无意义
            "DataSourceManager.java"
    );

    /** 会抛给用户的异常 */
    private static final Pattern THROW = Pattern.compile(
            "throw new (?:IllegalArgumentException|IllegalStateException|"
                    + "QueryHintException|UncheckedIOException|IOException|RuntimeException)");

    /** 中文句子（连续 4 个以上汉字，且不含变量拼接） */
    private static final Pattern CHINESE = Pattern.compile("[\u4e00-\u9fff]{4,}");

    /** 英文句子（三个以上单词） */
    private static final Pattern ENGLISH = Pattern.compile("\"[A-Z][a-z]+(?:\\s+[a-z]+){2,}");

    private static boolean isComment(String line) {
        String t = line.trim();
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*");
    }

    @Test
    @DisplayName("源码里没有绕过 i18n 的用户可见报错")
    void noHardcodedUserFacingMessages() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString();
                if ("Messages.java".equals(name) || WHITELIST.contains(name)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (isComment(line) || !THROW.matcher(line).find()) {
                        continue;
                    }
                    if (line.contains("Messages.get")) {
                        continue;              // 已走 i18n
                    }
                    // 异常可能跨行，把后续两行拼起来看
                    StringBuilder ctx = new StringBuilder(line);
                    for (int k = 1; k <= 2 && i + k < lines.size(); k++) {
                        ctx.append(' ').append(lines.get(i + k).trim());
                    }
                    String text = ctx.toString();
                    if (text.contains("Messages.get")) {
                        continue;
                    }
                    if (CHINESE.matcher(text).find() || ENGLISH.matcher(text).find()) {
                        offenders.add(name + ":" + (i + 1) + "  " + line.trim());
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "以下位置抛出了硬编码文案（用户可见的报错必须走 Messages）：\n  "
                        + String.join("\n  ", offenders)
                        + "\n\n改法：在 Messages 里加键（zh + en 各一条），再用 Messages.get(...)。"
                        + "\n若确实不该翻译，请加进本测试的 WHITELIST 并写明理由。");
    }
}
