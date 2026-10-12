package com.tinyquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 构建标识。
 *
 * <p>这一类东西的价值全在"取不到时怎么办"上：正常路径（打包后有值）几乎不会出错，
 * 出错的是开发态直接跑、没有 git、没提交过这些情况 —— 那时候如果显示
 * 一串 {@code @project.version@} 或者一个空的「·」，比不显示更像程序坏了。</p>
 */
@DisplayName("构建标识：取不到时要干净地省略，而不是显示占位符")
class BuildInfoTest {

    @Nested
    @DisplayName("display 的拼接")
    class Display {

        @Test
        @DisplayName("三样都有时用「 · 」连接")
        void allPresent() {
            assertEquals("1.0.0 · e0dce58 · 2026-10-10 20:54",
                    BuildInfo.display(
                            "1.0.0", "e0dce58", "2026-10-10 20:54"),
                    "拼出来要能直接给用户看");
        }

        @Test
        @DisplayName("缺 git 哈希时不留空档")
        void withoutCommit() {
            assertEquals("1.0.0 · 2026-10-10 20:54",
                    BuildInfo.display("1.0.0", "", "2026-10-10 20:54"),
                    "哈希取不到时不能变成「1.0.0 ·  · 2026-10-10」");
        }

        @Test
        @DisplayName("只有版本号时也能用")
        void versionOnly() {
            assertEquals("1.0.0", BuildInfo.display("1.0.0", "", ""));
        }

        @Test
        @DisplayName("全都没有时返回空串 —— 调用方据此决定整行不显示")
        void allEmpty() {
            assertEquals("", BuildInfo.display("", "", ""));
        }

        @Test
        @DisplayName("缺版本号时不会以「 · 」开头")
        void noLeadingSeparator() {
            String out = BuildInfo.display("", "e0dce58", "2026-10-10 20:54");
            assertEquals("e0dce58 · 2026-10-10 20:54", out);
            assertFalse(out.startsWith(" · "), "不能留下开头的分隔符");
        }
    }

    @Nested
    @DisplayName("占位符识别")
    class Placeholders {

        @Test
        @DisplayName("@project.version@ 这种没被替换的形态要当成「未知」")
        void unresolvedMavenPlaceholder() {
            // 开发态直接跑（mvn spring-boot:run / IDE）时的真实形态
            assertEquals("", BuildInfo.display("@project.version@", "@build.commit@", "@build.time@"),
                    "把 @...@ 显示给用户，看起来就是程序坏了");
        }

        @Test
        @DisplayName("只认完整的 @...@，正常值里带 @ 不受影响")
        void notTooEager() {
            // 邮箱、坐标之类含 @ 的值不该被误判成占位符
            assertEquals("v1.0@beta", BuildInfo.display("v1.0@beta", "", ""),
                    "只有首尾都是 @ 才算占位符");
        }

        @Test
        @DisplayName("单独一个 @ 不算占位符")
        void loneAtSign() {
            assertEquals("@", BuildInfo.display("@", "", ""));
        }
    }

    @Nested
    @DisplayName("真实运行时的取值")
    class RealValues {

        @Test
        @DisplayName("测试环境读取到的是构建产物里的值，不是占位符")
        void readsResolvedValues() {
            // 测试跑在 target/classes 上，那里是 antrun 生成的文件 ——
            // 如果生成逻辑坏了（比如又写回占位符），这条会红
            assertEquals("1.1.0", BuildInfo.version(),
                    "版本号应当来自 pom 的 project.version");
            assertFalse(BuildInfo.version().startsWith("@"),
                    "占位符没被替换 —— 检查 pom 里 generate-build-info 的执行");
        }

        @Test
        @DisplayName("display 与三个字段一致")
        void displayMatchesFields() {
            String expected = BuildInfo.display(
                    BuildInfo.version(), BuildInfo.commit(), BuildInfo.time());
            assertEquals(expected, BuildInfo.display(),
                    "两个重载必须给出一致的结果，否则界面与接口会对不上");
        }
    }
}
