package com.tinyquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把 NOTES.md「限制 1.6：没有 application.properties，配置写在启动类里」钉住。
 *
 * <p>为什么要专门立一个类：NOTES.md 第四节承诺「上面每一条都有对应的测试」。
 * 1.6 这条讲的是启动配置，不属于解析器，放不进 {@code DocumentedLimitationsTest}。</p>
 *
 * <p>它防的是什么：将来有人看到没有 {@code application.properties}，
 * 以为是漏了，顺手补一个空的，或者把 {@code server.port} 这类
 * 运行时才能算出来的值写死进去。那会让「端口被占用时自动回退」
 * 这类能力静默失效 —— 而且不会有任何测试因此变红。</p>
 *
 * <p>所以这条限制值得一个断言：静态配置文件里不该出现运行时才能决定的值。</p>
 */
class StartupConfigurationTest {

    /** 静态配置文件的候选路径。Spring Boot 认这几个名字 */
    private static final String[] STATIC_CONFIG_FILES = {
            "src/main/resources/application.properties",
            "src/main/resources/application.yml",
            "src/main/resources/application.yaml",
    };

    /**
     * 运行时才能决定的值，绝不能出现在静态配置文件里。
     *
     * <p>这三个值都依赖启动时的环境：数据目录依操作系统而定，
     * 端口要探测空闲，绑定地址有优先级链。写死在文件里就是错的。</p>
     */
    private static final String[] RUNTIME_ONLY_KEYS = {
            "logging.file.name",
            "server.port",
            "server.address",
    };

    @Test
    @DisplayName("限制 1.6：resources 下不该有 application.properties —— 配置写在启动类里")
    void noStaticApplicationProperties() {
        // 如果哪天真加了配置文件，这条会红 —— 那正是提醒：请先确认里面装的
        // 都是「静态」配置，并回来更新 NOTES.md 1.6，不要顺手就加
        for (String candidate : STATIC_CONFIG_FILES) {
            Path p = Path.of(candidate);
            assertFalse(Files.exists(p),
                    "出现了静态配置文件 " + candidate + "。"
                            + "若确有必要，请先确认其中不含运行时才能决定的值，"
                            + "并同步更新 NOTES.md 第 1.6 条");
        }
    }

    @Test
    @DisplayName("限制 1.6：那些运行时才能算的值，启动类确实在用 System.setProperty 设置")
    void runtimeValuesAreSetFromCode() throws Exception {
        // 反向确认：这些值不是"没人管"，而是由代码设置。
        // 只断言"文件里没有"是不够的 —— 那样把设置代码删掉也照样绿
        String source = Files.readString(
                Path.of("src/main/java/com/tinyquery/TinyQueryApplication.java"));

        // 断言必须匹配到【真正的赋值调用】，不能只匹配 key 名字。
        // 吃过一次亏：只断言源码含 "server.address"，把 setProperty 那行删掉后
        // 仍然绿 —— 因为同一个名字在 if 判断里还出现过（System.getProperty("server.address")）。
        // 所以这里匹配 setProperty("key", ...) 的完整调用前缀。
        assertAll(
                () -> assertTrue(source.contains("System.setProperty(\"logging.file.name\""),
                        "启动类应当调用 setProperty 设置 logging.file.name（值来自用户数据目录）"),
                () -> assertTrue(source.contains("System.setProperty(\"server.port\""),
                        "启动类应当调用 setProperty 设置 server.port（值来自空闲端口探测）"),
                () -> assertTrue(source.contains("System.setProperty(\"server.address\""),
                        "启动类应当调用 setProperty 设置 server.address（值来自优先级链）"));
    }

    @Test
    @DisplayName("日志文件编码被显式定为 UTF-8（不设就跟着平台走，中文 Windows 上是 GBK）")
    void logCharsetIsPinnedToUtf8() throws Exception {
        // 这条曾经漏掉：把 setProperty("logging.charset.file", ...) 删掉后，
        // 上面那条断言照样绿 —— 它只检查了另外三个键。
        //
        // 为什么要钉住：不设时 Logback 用平台默认编码，中文 Windows 上是 GBK，
        // 而程序日志里有中文，别人用 VS Code（默认按 UTF-8 打开）看到的是乱码。
        // 实测确认过：带 -Dfile.encoding=UTF-8 启动是 UTF-8，
        // 不带参数启动（用户从 exe 双击的真实情形）就是 GBK
        String source = Files.readString(
                Path.of("src/main/java/com/tinyquery/TinyQueryApplication.java"));

        assertAll(
                () -> assertTrue(source.contains("System.setProperty(\"logging.charset.file\""),
                        "必须显式设置 logging.charset.file，否则日志编码跟着平台走"),
                () -> assertTrue(source.contains("\"UTF-8\""),
                        "日志编码必须是 UTF-8"));
    }

    @Test
    @DisplayName("限制 1.6：数据目录是运行时解析的，不是写死的常量")
    void dataDirIsResolvedAtRuntime() {
        // 这一条是 1.6 的根据：值确实随环境而变。
        // 用纯函数入口喂不同平台，确认同一份代码给出不同结果 ——
        // 若哪天有人把数据目录写死成常量，这条会红
        Path win = PathResolver.resolveDataDir(
                "Windows 11", null, "C:/Users/x/AppData/Roaming", null, "C:/Users/x");
        Path mac = PathResolver.resolveDataDir(
                "Mac OS X", null, null, null, "/Users/x");
        Path linux = PathResolver.resolveDataDir(
                "Linux", null, null, "/home/x/.local/share", "/home/x");

        assertAll(
                () -> assertEqualsIgnoringSeparators(
                        "C:/Users/x/AppData/Roaming/LynMill", win,
                        "Windows 落到 APPDATA"),
                () -> assertEqualsIgnoringSeparators(
                        "/Users/x/Library/Application Support/LynMill", mac,
                        "macOS 落到 Application Support"),
                () -> assertEqualsIgnoringSeparators(
                        "/home/x/.local/share/LynMill", linux,
                        "Linux 落到 XDG_DATA_HOME"));
    }

    @Test
    @DisplayName("限制 1.6：LYNMILL_HOME 直接整体覆盖，且不再追加应用目录名")
    void overrideWinsWithoutAppendingAppName() {
        Path overridden = PathResolver.resolveDataDir(
                "Windows 11", "D:/portable/tq", "C:/Users/x/AppData/Roaming", null, "C:/Users/x");

        // 便携部署时用户指定的目录就是数据目录本身，不该再套一层 LynMill
        assertEqualsIgnoringSeparators("D:/portable/tq", overridden, "覆盖值应原样采用");

        // 与上面 Windows 分支的结果做对照：同一个环境变量存在时，
        // 平台差异被完全绕过（这正是"整体覆盖"的含义）
        assertFalse(overridden.toString().replace('\\', '/').endsWith("/LynMill"),
                "覆盖模式下不应追加应用目录名：" + overridden);
    }

    /** 比较路径时忽略分隔符差异（Windows 上两者混用是正常的） */
    private static void assertEqualsIgnoringSeparators(String expected, Path actual, String message) {
        String a = actual.toString().replace('\\', '/');
        String e = expected.replace('\\', '/');
        assertTrue(a.equalsIgnoreCase(e), message + "：期望 " + e + "，实际 " + a);
    }
}
