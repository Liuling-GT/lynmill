package com.tinyquery.datasource;

import com.tinyquery.datasource.spec.DataSourceConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DataSourceStore 数据源配置落盘")
class DataSourceStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("datasources.json");
    }

    private DataSourceStore store() {
        return new DataSourceStore(file());
    }

    private static DataSourceConfig csv(String name, String path) {
        return new DataSourceConfig(name, "csv", Map.of("path", path, "encoding", "UTF-8"));
    }

    // ==================== 保存与读回 ====================

    @Test
    @DisplayName("保存后能原样读回")
    void savedConfigsComeBack() throws Exception {
        DataSourceStore store = store();
        store.save(List.of(
                new DataSourceConfig("sales", "mysql",
                        Map.of("host", "localhost", "port", "3306", "database", "shop")),
                csv("huge", "D:/data/huge.csv")));

        List<DataSourceConfig> loaded = store.load();

        assertEquals(2, loaded.size());
        DataSourceConfig sales = loaded.stream()
                .filter(c -> c.name().equals("sales")).findFirst().orElseThrow();
        assertEquals("mysql", sales.type());
        assertEquals("3306", sales.getString("port"), "值读回来应当还是字符串");
        assertEquals("shop", sales.getString("database"));
    }

    @Test
    @DisplayName("值类型不会走样 —— 数字与布尔读回来仍是原样")
    void valueTypesSurviveRoundTrip() throws Exception {
        DataSourceStore store = store();
        store.save(List.of(new DataSourceConfig("mixed", "csv", Map.of(
                "path", "D:/a.csv", "head", 5, "flag", true))));

        DataSourceConfig back = store.load().get(0);

        // 配置项统一按字符串读，因此这里验的是「写进去什么、读出来什么」
        assertEquals("5", back.getString("head"));
        assertEquals("true", back.getString("flag"));
        assertTrue(back.getBoolean("flag", false));
        assertEquals(5, back.getInt("head", 0));
    }

    @Test
    @DisplayName("多次保存相互覆盖，不会叠加")
    void saveOverwritesPreviousContent() throws Exception {
        DataSourceStore store = store();
        store.save(List.of(csv("first", "D:/first.csv")));
        store.save(List.of(csv("second", "D:/second.csv")));

        List<DataSourceConfig> loaded = store.load();

        assertEquals(1, loaded.size());
        assertEquals("second", loaded.get(0).name());
    }

    @Test
    @DisplayName("保存空列表 = 清空，读回是空而不是报错")
    void saveEmptyListClearsFile() throws Exception {
        DataSourceStore store = store();
        store.save(List.of(csv("gone", "D:/gone.csv")));
        store.save(List.of());

        assertTrue(store.load().isEmpty());
        assertTrue(Files.exists(file()), "文件应当还在，只是内容为空列表");
    }

    @Test
    @DisplayName("保存 null 当作空列表，不抛异常")
    void saveNullTreatedAsEmpty() throws Exception {
        DataSourceStore store = store();
        assertDoesNotThrow(() -> store.save(null));
        assertTrue(store.load().isEmpty());
    }

    // ==================== 文件不存在 / 内容异常（都不能抛） ====================

    @Test
    @DisplayName("文件不存在 → 返回空列表，不抛异常（首次启动就是这个状态）")
    void missingFileIsNotAnError() {
        assertFalse(Files.exists(file()));
        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("文件是空的 → 返回空列表，不抛异常")
    void blankFileIsNotAnError() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(file(), "   \n  ", StandardCharsets.UTF_8);

        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("文件损坏 → 返回空列表而不是抛异常，且坏文件被改名留档")
    void corruptFileIsQuarantinedNotLost() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(file(), "{ 这不是合法 JSON ", StandardCharsets.UTF_8);

        DataSourceStore store = store();
        assertTrue(store.load().isEmpty(), "损坏时按「没有已保存的数据源」处理");

        // 关键：原文件必须被改名保留。若留在原地，下一次 save 会直接覆盖它，
        // 用户连抢救的机会都没有
        assertFalse(Files.exists(file()), "坏文件应当已被改名");
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(n -> n.contains(".corrupt-")),
                    "应当留下一个 .corrupt- 开头的留档文件，实际：" + names);
        }
    }

    @Test
    @DisplayName("带 UTF-8 BOM 的文件能正常读回 —— 记事本保存出来就是这样")
    void fileWithBomIsStillReadable() throws Exception {
        Files.createDirectories(dir);
        // \uFEFF 是 BOM。Windows 记事本另存为 UTF-8 时可能写入它，
        // 而 Jackson 会把 BOM 当成非法字符 —— 不能因为一个看不见的字符
        // 就把用户整份配置判成损坏
        String withBom = "\uFEFF{\"version\":1,\"datasources\":"
                + "[{\"name\":\"bom\",\"type\":\"csv\",\"properties\":{\"path\":\"D:/b.csv\"}}]}";
        Files.writeString(file(), withBom, StandardCharsets.UTF_8);

        List<DataSourceConfig> loaded = store().load();

        assertEquals(1, loaded.size(), "带 BOM 的文件应当照常读出来");
        assertEquals("bom", loaded.get(0).name());
        assertTrue(Files.exists(file()), "不该被当成损坏文件隔离掉");
    }

    @Test
    @DisplayName("版本号不认识 → 返回空列表，不抛异常（为将来的迁移留位置）")
    void unknownVersionIsRejected() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(file(),
                "{\"version\": 99, \"datasources\": [{\"name\":\"x\",\"type\":\"csv\",\"properties\":{}}]}",
                StandardCharsets.UTF_8);

        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("单条残缺不影响其它条目 —— 缺名字的跳过，剩下的照常读回")
    void brokenEntryDoesNotSinkTheRest() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(file(), """
                {
                  "version": 1,
                  "datasources": [
                    { "name": "", "type": "csv", "properties": {} },
                    { "name": "good", "type": "csv", "properties": { "path": "D:/g.csv" } }
                  ]
                }
                """, StandardCharsets.UTF_8);

        List<DataSourceConfig> loaded = store().load();

        assertEquals(1, loaded.size());
        assertEquals("good", loaded.get(0).name());
    }

    // ==================== 原子写 ====================

    @Test
    @DisplayName("存盘后不留下临时文件")
    void noTempFileLeftBehind() throws Exception {
        DataSourceStore store = store();
        store.save(List.of(csv("a", "D:/a.csv")));

        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertFalse(names.stream().anyMatch(n -> n.endsWith(".tmp")),
                    "临时文件应当已被改名消耗掉，实际：" + names);
        }
    }

    @Test
    @DisplayName("目录不存在时会自动创建")
    void createsParentDirectory() throws Exception {
        Path nested = dir.resolve("a/b/c/datasources.json");
        new DataSourceStore(nested).save(List.of(csv("x", "D:/x.csv")));

        assertTrue(Files.exists(nested));
    }

    // ==================== 文件内容本身 ====================

    @Test
    @DisplayName("文件带 version 字段，且是可读的缩进 JSON")
    void fileCarriesVersionAndIsReadable() throws Exception {
        DataSourceStore store = store();
        store.save(List.of(csv("sales", "D:/sales.csv")));

        String json = Files.readString(file(), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"version\""), json);
        assertTrue(json.contains("\"datasources\""), json);
        assertTrue(json.contains("sales"), json);
        assertTrue(json.contains("\n"), "应当是缩进输出，便于用户直接打开查看");
    }

    @Test
    @DisplayName("保存顺序稳定 —— 同样的内容两次保存得到完全相同的文件")
    void outputIsStable() throws Exception {
        DataSourceStore store = store();
        List<DataSourceConfig> configs = List.of(
                csv("b", "D:/b.csv"), csv("a", "D:/a.csv"));

        store.save(configs);
        String first = Files.readString(file(), StandardCharsets.UTF_8);
        store.save(configs);
        String second = Files.readString(file(), StandardCharsets.UTF_8);

        assertEquals(first, second, "内容相同则文件字节应当相同，否则 diff 全是噪声");
    }

    @Test
    @DisplayName("写入失败会抛 IOException —— 让调用方能感知并记日志")
    void writeFailureSurfacesAsIOException() throws Exception {
        // 用一个「父路径是文件」的地址，构造必然失败的写入
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x", StandardCharsets.UTF_8);

        DataSourceStore broken = new DataSourceStore(blocker.resolve("datasources.json"));

        assertThrows(IOException.class, () -> broken.save(List.of(csv("a", "D:/a.csv"))));
    }
}
