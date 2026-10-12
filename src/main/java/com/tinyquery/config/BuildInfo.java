package com.tinyquery.config;

import java.io.InputStream;
import java.util.Properties;

/**
 * 构建标识：版本号、构建时间、git 短哈希。
 *
 * <p>用途是能一眼回答「我看到的到底是哪一版」。踩过的坑：改了源码重新打包，
 * 浏览器加载的却还是旧 bundle，看起来「改动完全没效果」，白白排查了很久。
 * 那次的根因是前端旧产物没被清掉（已在 pom 里修），但当时没有任何地方
 * 能看出前端是哪一版，只能靠猜。现在打包时会写进 {@code build-info.properties}，
 * 界面上能直接看到。</p>
 *
 * <p>不比对 jar 内时间戳：jar 是 zip，里面每个条目的时间戳都是写入 jar 的时刻，
 * 不是文件原本的修改时间。所以「jar 内 index.html 的时间」恒等于打包时刻，
 * 比不出「前端是否比 jar 旧」。这个思路行不通，只能打包时写死一份元数据。</p>
 *
 * <p>取不到值时，开发态直接跑（{@code mvn spring-boot:run} / IDE）不过滤这个文件，
 * 占位符会保持 {@code @project.version@} 这种原样。这种情况识别为未知并省略，
 * 而不是把一串 {@code @...@} 显示给用户 —— 那比不显示更像坏了。</p>
 *
 * <p>git 哈希同理：没装 git、不是仓库、或者还没提交过，都取不到，
 * 此时只显示「版本 + 构建时间」。</p>
 */
public final class BuildInfo {

    /** 资源文件名。放在 resources 根下，打包时被过滤替换 */
    private static final String RESOURCE = "build-info.properties";

    /** 未替换的占位符形态：以 @ 开头结尾。识别出来就当作没有这个值 */
    private static final String PLACEHOLDER_PREFIX = "@";

    private static final String UNKNOWN = "";

    private static final String VERSION;
    private static final String TIME;
    private static final String COMMIT;

    static {
        Properties props = load();
        VERSION = clean(props.getProperty("build.version"));
        TIME = clean(props.getProperty("build.time"));
        COMMIT = clean(props.getProperty("build.commit"));
    }

    private BuildInfo() {
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = BuildInfo.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception e) {
            // 读不到就当作全部未知：这只是一个展示用的标识，不能因为它而让程序起不来
        }
        return props;
    }

    /**
     * 把占位符与空值统一成空串。
     *
     * <p>{@code @project.version@} 这种没被 Maven 替换的形态要识别出来：
     * 直接显示给用户会被当成程序坏了。</p>
     */
    private static String clean(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return UNKNOWN;
        }
        if (v.startsWith(PLACEHOLDER_PREFIX) && v.endsWith(PLACEHOLDER_PREFIX) && v.length() > 1) {
            return UNKNOWN;
        }
        return v;
    }

    /** 版本号，如 {@code 1.1.0}；未知时为空串 */
    public static String version() {
        return VERSION;
    }

    /** 构建时间，如 {@code 2026-10-10 20:54}；未知时为空串 */
    public static String time() {
        return TIME;
    }

    /** git 短哈希，如 {@code e0dce58}；取不到时为空串 */
    public static String commit() {
        return COMMIT;
    }

    /**
     * 一行可读的构建标识，如 {@code 1.1.0 · e0dce58 · 2026-10-10 20:54}。
     *
     * <p>缺的部分直接省略（哈希取不到就是 {@code 1.1.0 · 2026-10-10 20:54}），
     * 不会留一个空的「·」给用户看。全都没有时返回空串。</p>     */
    public static String display() {
        return display(VERSION, COMMIT, TIME);
    }

    /**
     * 按给定三段拼一行。包可见，供测试直接验证拼接规则 ——
     * 静态字段来自资源文件，测试没法在不改环境的情况下覆盖「缺某一段」的场景。
     *
     * <p>{@code commit} 为空时自动省略，不留空档。</p>
     */
    static String display(String version, String commit, String time) {
        StringBuilder sb = new StringBuilder();
        append(sb, clean(version));
        append(sb, clean(commit));
        append(sb, clean(time));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(part);
    }
}
