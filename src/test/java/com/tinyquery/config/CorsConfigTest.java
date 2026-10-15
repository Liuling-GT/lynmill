package com.tinyquery.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 暴露头的回归测试。
 *
 * <p>这里不启动 Spring 上下文，而是直接读源码断言 —— 原因：
 * 真正要防的不是「这次配置对不对」，而是「将来有人重写 CorsConfig 时漏掉这一行」。
 * 读源码能把这条约束钉死在文件本身，且跑得极快。</p>
 *
 * <p>背景：跨域响应里浏览器默认隐藏 {@code Content-Disposition}，
 * 导出功能靠它读文件名。漏了 {@code exposedHeaders} 不会报错、不会崩，
 * 只会让用户拿到一个名字不对的文件 —— 这种缺陷最难被发现，
 * 所以值得一条专门的测试守着。</p>
 */
class CorsConfigTest {

    private static final Path SOURCE =
            Path.of("src/main/java/com/tinyquery/config/CorsConfig.java");

    @Test
    void 必须暴露ContentDisposition供导出读取文件名() throws IOException {
        String src = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(src.contains("exposedHeaders"),
                "CorsConfig 缺少 exposedHeaders —— 跨域时前端读不到 Content-Disposition，"
                        + "导出会退化成 export.csv 这样的兜底文件名");

        assertTrue(src.contains("Content-Disposition"),
                "exposedHeaders 里必须包含 Content-Disposition");
    }

    @Test
    void 暴露头清单不含通配() {
        // 用 "*" 暴露全部响应头会连内部头一起交出去，没必要也不该做。
        // 导出只要一个文件名，明确列出即可。
        List<String> exposed = List.of(CorsConfig.EXPOSED_HEADERS);
        assertTrue(exposed.contains("Content-Disposition"),
                "EXPOSED_HEADERS 必须含 Content-Disposition");
        assertTrue(exposed.stream().noneMatch("*"::equals),
                "不应该用通配暴露全部响应头");
    }
}
