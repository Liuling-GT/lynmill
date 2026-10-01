package com.tinyquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuntimeMode 开发/打包运行判定")
class RuntimeModeTest {

    // ==================== 开发运行 ====================

    @Test
    @DisplayName("classpath 是 target/classes 目录时判为开发运行")
    void developmentClasspathIsNotPackaged() {
        assertFalse(RuntimeMode.looksPackaged("file:/D:/Projects.java/tinyquery/target/classes/"));
    }

    @Test
    @DisplayName("路径含空格也不影响判定")
    void pathWithSpacesIsNotPackaged() {
        assertFalse(RuntimeMode.looksPackaged("file:/C:/Program%20Files/my%20project/target/classes/"));
    }

    // ==================== 打包运行 ====================

    @Test
    @DisplayName("Spring Boot 可执行 jar 的嵌套资源路径判为打包运行")
    void springBootFatJarIsPackaged() {
        // Spring Boot 3.x 的可执行 jar 里，类位于 BOOT-INF/classes，
        // 资源定位串长这样 —— 注意它并不以 .jar 结尾
        assertTrue(RuntimeMode.looksPackaged(
                "jar:nested:/D:/p/tinyquery.jar/!BOOT-INF/classes/!/"));
    }

    @Test
    @DisplayName("普通 jar 判为打包运行")
    void plainJarIsPackaged() {
        assertTrue(RuntimeMode.looksPackaged("file:/D:/p/tinyquery-1.0-SNAPSHOT.jar"));
    }

    @Test
    @DisplayName("大小写不敏感")
    void markerIsCaseInsensitive() {
        assertTrue(RuntimeMode.looksPackaged("file:/D:/p/tinyquery.JAR"));
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("拿不到加载来源时按开发处理 —— 宁可少开一次浏览器，也不要弹 404")
    void nullLocationIsNotPackaged() {
        assertFalse(RuntimeMode.looksPackaged(null));
    }

    @Test
    @DisplayName("空串判为开发运行")
    void emptyLocationIsNotPackaged() {
        assertFalse(RuntimeMode.looksPackaged(""));
    }

    @Test
    @DisplayName("真实运行环境下不抛异常，返回值可用")
    void realEnvironmentIsQueryable() {
        // 本测试运行在 target/test-classes 目录下，应判为开发运行；
        // 这里主要保证 isPackaged() 在真实 JVM 里能安全求值
        assertFalse(RuntimeMode.isPackaged(), "测试运行在 target/test-classes，应判为开发运行");
    }
}
