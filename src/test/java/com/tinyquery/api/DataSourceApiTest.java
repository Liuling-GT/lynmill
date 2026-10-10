package com.tinyquery.api;

import com.tinyquery.config.SecurityToken;
import com.tinyquery.config.TokenFilter;
import com.tinyquery.credential.NoOpCredentialStore;
import com.tinyquery.datasource.DataSourceManager;
import com.tinyquery.datasource.DataSourceStore;
import com.tinyquery.datasource.loader.CSVLoader;
import com.tinyquery.datasource.loader.PostgreSQLLoader;
import com.tinyquery.datasource.loader.SQLiteLoader;
import com.tinyquery.datasource.spec.ConnectionTestResult;
import com.tinyquery.datasource.spec.DataLoader;
import com.tinyquery.datasource.spec.FieldSpec;
import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceInfo;
import com.tinyquery.executor.ColumnStore;
import com.tinyquery.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * API 层的真实覆盖：两个数据源相关 Controller + 全局异常处理。
 *
 * <p><b>为什么这里必须走 MockMvc 而不是直接调方法</b>：
 * 这一层的逻辑大半不在方法体里，而在框架装配上 —— 路由、请求体反序列化、
 * 异常到响应的映射、以及令牌过滤器有没有真的拦在前面。
 * 直接调 {@code controller.list()} 这些一个都测不到。
 * （对比：{@code PreferencesControllerTest} 就选择直接调，因为那边没有这些。
 *  取舍看这一层到底有什么可测。）
 *
 * <p><b>令牌</b>：{@link TokenFilter} 拦下所有 {@code /api/**} 请求，
 * 不带 {@code X-TinyQuery-Token} 一律 401。所以每个用例都要带上真实令牌 ——
 * 用 {@link SecurityToken#value()} 而不是造假值，这样连过滤器一起走通了。
 */
@DisplayName("API 层（DataSourceController / GlobalExceptionHandler）")
class DataSourceApiTest {

    @TempDir
    Path tempDir;

    private MockMvc mvc;
    private DataSourceManager manager;
    private String token;

    /**
     * 造一个把配置写到临时目录的 Store。
     *
     * <p>{@code DataSourceStore(Path)} 是包级可见的（有意如此：外部只该用
     * 无参构造去默认位置），本测试在 api 包，只能反射拿到它。
     * 用反射而不是把测试挪进 datasource 包，是为了让这个测试文件与它测的
     * Controller 待在同一个包里 —— 将来 Controller 改名时容易一起发现。
     */
    private static DataSourceStore storeAt(Path file) {
        try {
            Constructor<DataSourceStore> c = DataSourceStore.class.getDeclaredConstructor(Path.class);
            c.setAccessible(true);
            return c.newInstance(file);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法构造指向临时目录的 DataSourceStore", e);
        }
    }

    @BeforeEach
    void setUp() {
        List<DataLoader> loaders = List.of(
                new CSVLoader(), new SQLiteLoader(), new PostgreSQLLoader());
        DataSourceStore store = storeAt(tempDir.resolve("datasources.json"));
        // 用 NoOpCredentialStore：本测试不碰系统凭据管理器，
        // 否则在 CI 上会真的去写钥匙串/凭据库
        manager = new DataSourceManager(loaders, store, new NoOpCredentialStore("测试中不存密码"));

        // 装上真正的 TokenFilter：否则测的是一套「没有安全层」的假环境，
        // 令牌相关的回归（比如某天过滤器路径匹配写错）就永远发现不了
        mvc = MockMvcBuilders
                .standaloneSetup(new DataSourceController(manager))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TokenFilter())
                .build();

        token = SecurityToken.value();
    }

    /**
     * 带令牌的请求头，供各用例复用。
     *
     * <p><b>还要显式带 Host</b>：{@link TokenFilter} 的第一道关是 Host 校验
     * （防 DNS rebinding），MockMvc 默认发的 Host 过不了那一关，会直接 403 ——
     * 而且它挡在令牌校验之前，所以「只带令牌」的写法会得到一个 403 而不是 200，
     * 排查时容易误以为是令牌不对。
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withToken(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        return b.header(SecurityToken.HEADER_NAME, token)
                .header("Host", "localhost:8080");
    }

    private Path writeCsv(String name, String content) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    // ==================== 令牌过滤：先确认安全层真的在这条链上 ====================

    @Test
    @DisplayName("不带令牌访问 /api/** 被 401 拦下")
    void missingTokenIsRejected() throws Exception {
        // Host 要带上：否则会先被 403 挡掉，测试就变成「因为错的原因通过」，
        // 将来令牌校验坏掉了它也照样绿
        mvc.perform(get("/api/datasource").header("Host", "localhost:8080"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("令牌错误同样被 401 拦下")
    void wrongTokenIsRejected() throws Exception {
        mvc.perform(get("/api/datasource")
                        .header("Host", "localhost:8080")
                        .header(SecurityToken.HEADER_NAME, "not-the-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("带对令牌能正常访问")
    void correctTokenIsAccepted() throws Exception {
        // 这条是上两条的对照：证明 401 确实是令牌造成的，
        // 而不是路由写错、Controller 没装上之类的其它原因
        mvc.perform(withToken(get("/api/datasource")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ==================== list / types ====================

    @Test
    @DisplayName("刚启动时 list 返回空数组而不是 null")
    void listIsEmptyArrayInitially() throws Exception {
        mvc.perform(withToken(get("/api/datasource")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 返回 null 会让前端 data.map 直接崩，空数组才是对的
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("types 返回已注册的加载器，且不含敏感信息")
    void typesListsLoaders() throws Exception {
        mvc.perform(withToken(get("/api/datasource/types")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.type=='csv')]").exists());
    }

    // ==================== 注册 ====================

    @Test
    @DisplayName("注册 CSV 成功后能被 list 查到，且名字正确")
    void registerThenList() throws Exception {
        Path csv = writeCsv("t.csv", "name,age\n张三,30\n李四,40\n");

        mvc.perform(withToken(post("/api/datasource"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mydata\",\"type\":\"csv\",\"properties\":{\"path\":\""
                                + csv.toString().replace("\\", "\\\\") + "\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(withToken(get("/api/datasource")))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("mydata"));
    }

    @Test
    @DisplayName("注册时路径不存在 → 业务失败，code=1 且带可读原因")
    void registerBadPathFails() throws Exception {
        mvc.perform(withToken(post("/api/datasource"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"type\":\"csv\","
                                + "\"properties\":{\"path\":\"D:/definitely/not/here.csv\"}}"))
                .andExpect(status().isOk())
                // 业务失败仍然 HTTP 200，靠 code 区分 —— 前端拦截器依赖这个约定
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ==================== 异常处理器 ====================

    @Test
    @DisplayName("类型不支持 → 走异常处理器，code=1，不吐堆栈")
    void unknownTypeHandled() throws Exception {
        mvc.perform(withToken(post("/api/datasource"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"nosuchtype\",\"properties\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("异常处理器不把内部异常细节泄露给前端")
    void internalErrorsAreNotLeaked() throws Exception {
        // GlobalExceptionHandler 的兜底分支要返回一句通用提示，
        // 而不是 e.getMessage()（那会把类名、路径等内部信息吐出去）
        String expected = Messages.get("error.internal");

        DataSourceResponse<Void> r = new GlobalExceptionHandler()
                .handleException(new IllegalStateException("内部路径 D:/secret/x 不该出现"));

        assertEquals(expected, r.message());
        assertFalse(r.message().contains("secret"),
                "内部细节不应当出现在返回给前端的文案里：" + r.message());
    }

    @Test
    @DisplayName("业务异常（DataSourceException）的 message 直接给前端")
    void businessErrorIsShownToUser() {
        // 与上一条相反：这类是预期内的失败，文案本来就是给用户看的
        String msg = "数据源不存在：foo";
        DataSourceResponse<Void> r = new GlobalExceptionHandler()
                .handleDataSourceException(new com.tinyquery.datasource.spec.DataSourceException(msg));

        assertEquals(msg, r.message());
    }

    // ==================== 删除 / 刷新 ====================

    @Test
    @DisplayName("删除已注册的数据源后 list 变空")
    void removeWorks() throws Exception {
        Path csv = writeCsv("t2.csv", "a\n1\n");
        mvc.perform(withToken(post("/api/datasource"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"todelete\",\"type\":\"csv\",\"properties\":{\"path\":\""
                        + csv.toString().replace("\\", "\\\\") + "\"}}"));

        mvc.perform(withToken(delete("/api/datasource/todelete")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(withToken(get("/api/datasource")))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("删除不存在的数据源 → code=1，不抛 500")
    void removeMissingFailsGracefully() throws Exception {
        mvc.perform(withToken(delete("/api/datasource/nosuch")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    @DisplayName("刷新不存在的数据源 → code=1")
    void refreshMissingFailsGracefully() throws Exception {
        mvc.perform(withToken(post("/api/datasource/nosuch/refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    @DisplayName("刷新后能读到文件的新内容")
    void refreshPicksUpChanges() throws Exception {
        Path csv = writeCsv("changing.csv", "a\n1\n");
        mvc.perform(withToken(post("/api/datasource"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"chg\",\"type\":\"csv\",\"properties\":{\"path\":\""
                        + csv.toString().replace("\\", "\\\\") + "\"}}"));

        // 改文件后刷新 —— 这是「数据文件变了要同步」那条产品承诺的实现
        Files.writeString(csv, "a\n1\n2\n3\n", StandardCharsets.UTF_8);

        mvc.perform(withToken(post("/api/datasource/chg/refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertEquals(3, manager.getByTableName("chg").getColumnIndex(),
                "刷新后应当读到 3 行");
    }

    // ==================== 敏感字段脱敏 ====================

    /**
     * 一个带敏感字段、且总是加载成功的假数据源。
     *
     * <p><b>为什么要造它</b>：脱敏只对 loader 用 {@code FieldSpec.secret}
     * 声明过的字段生效。真实项目里只有 MySQL / PostgreSQL 声明了 {@code password}，
     * 而这两个源没有服务器、注册会失败 —— 于是列表是空的，
     * 「密码没泄露」这种断言在空列表上照样成立，是个假绿（对照实验实测确认）。
     * CSV 又没有 password 字段，拿它测同样测不到脱敏。
     *
     * <p>所以造这个 loader：字段声明与 MySQL 一致（有一个 secret 的 password），
     * 但 load() 直接返回空 store，不碰网络。这样才真正走到脱敏那条分支。
     */
    private static final class SecretLoader implements DataLoader {
        @Override
        public String type() {
            return "withsecret";
        }

        @Override
        public String label() {
            return "With Secret";
        }

        @Override
        public List<FieldSpec> fields() {
            return List.of(
                    FieldSpec.text("path", "路径", true, "描述", "x"),
                    FieldSpec.secret("password", "密码", true, "描述", ""));
        }

        @Override
        public ConnectionTestResult test(DataSourceConfig config) {
            return ConnectionTestResult.ok("ok");
        }

        @Override
        public ColumnStore load(DataSourceConfig config) {
            return new ColumnStore();
        }
    }

    /** 用指定的 loader 重建 manager 与 MockMvc（脱敏测试需要带 secret 字段的 loader） */
    private void rebuildMvcWith(DataLoader loader) {
        manager = new DataSourceManager(List.of(loader),
                storeAt(tempDir.resolve("datasources2.json")),
                new NoOpCredentialStore("测试中不存密码"));
        mvc = MockMvcBuilders
                .standaloneSetup(new DataSourceController(manager))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TokenFilter())
                .build();
    }

    @Test
    @DisplayName("list 不返回真实密码 —— 只给掩码")
    void listMasksPassword() throws Exception {
        rebuildMvcWith(new SecretLoader());

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", "x");
        props.put("password", "super-secret-pw");
        manager.register(new DataSourceConfig("masked", "withsecret", props));

        // 先确认它真的在列表里 —— 否则下面的断言又是空集上成立
        List<DataSourceInfo> info = manager.list();
        assertFalse(info.isEmpty(), "夹具失效：列表为空，掩码断言没有意义");
        assertTrue(info.stream().anyMatch(i -> "masked".equals(i.name())),
                "夹具失效：目标数据源不在列表里");

        String body = mvc.perform(withToken(get("/api/datasource")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 密码绝不能原样出现在列表接口里 —— 那等于把凭据回传给任何一个前端
        assertFalse(body.contains("super-secret-pw"),
                "列表接口泄露了密码明文：" + body);
    }

    @Test
    @DisplayName("掩码逻辑本身：敏感字段被换成 ******，而不是整个丢掉")
    void maskingActuallyReplacesValue() throws Exception {
        // 上一条测「没泄露」，这一条测「确实掩了」——
        // 两者都必要：只测前者的话，把字段整个删掉也算通过
        rebuildMvcWith(new SecretLoader());

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", "x");
        props.put("password", "super-secret-pw");
        manager.register(new DataSourceConfig("m2", "withsecret", props));

        String body = mvc.perform(withToken(get("/api/datasource")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.contains("******"),
                "敏感字段应当被替换成掩码，而不是整个丢掉：" + body);
        assertFalse(body.contains("super-secret-pw"),
                "掩码之后不该还有明文：" + body);
    }


    // ==================== credential-status ====================

    @Test
    @DisplayName("credential-status 返回可用性与原因，前端据此决定勾选框")
    void credentialStatusShape() throws Exception {
        mvc.perform(withToken(get("/api/datasource/credential-status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.available").exists())
                .andExpect(jsonPath("$.data.reason").exists());
    }
}
