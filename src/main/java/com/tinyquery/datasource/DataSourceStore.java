package com.tinyquery.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tinyquery.config.PathResolver;
import com.tinyquery.datasource.spec.DataSourceConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 数据源配置的落盘与读取。
 *
 * <p>职责收窄成「把一串配置变成文件、再从文件变回来」：不认识 DataLoader、
 * 不碰内存里的数据源表。因此可以脱离 Spring 单独测试，将来换成 SQLite
 * 之类也只需替换本类。</p>
 *
 * <pre>
 * {
 *   "version" : 1,
 *   "datasources" : [ { "name" : "sales", "type" : "mysql", "properties" : { ... } } ]
 * }
 * </pre>
 *
 * <p>{@code version} 是格式迁移的依据：格式将来变化（加字段、换结构）时靠它
 * 写迁移逻辑，没有版本号则老文件撞上新代码只能报错。</p>
 *
 * <p>{@link #save} 必须原子写：先写临时文件再改名。直接覆写时写到一半崩溃或断电
 * 会留下半个文件，下次启动读不出来，用户配过的数据源全部丢失。</p>
 *
 * <p>{@link #load} 绝不抛异常：文件损坏属于用户环境问题，读不出来就按
 * 「没有已保存的数据源」处理，程序照常启动，用户才有机会进界面重新配。</p>
 *
 * <p>解析失败时必须先改名保留原文件：{@code load()} 返回空列表后，任何一次
 * {@code save()} 都会用内存内容把原文件覆盖掉，用户配置就此消失。因此坏文件
 * 改名为 {@code datasources.json.corrupt-时间戳} 留档，并在日志里写明位置。</p>
 */
@Component
public class DataSourceStore {

    private static final Logger log = LoggerFactory.getLogger(DataSourceStore.class);

    /** 当前文件格式版本。新增/改变字段结构时递增，并在读取处写迁移分支。 */
    static final int CURRENT_VERSION = 1;

    /** 临时文件后缀。与正式文件同目录，保证 rename 在同一文件系统内（跨盘改名不是原子的）。 */
    private static final String TEMP_SUFFIX = ".tmp";

    /** 损坏文件的留档前缀。 */
    private static final String CORRUPT_SUFFIX = ".corrupt-";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path file;

    private final ObjectMapper mapper = new ObjectMapper()
            // 缩进输出：用户可能会打开这个文件，可读性比体积重要
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Spring 用的构造器：固定落在用户数据目录里。 */
    public DataSourceStore() {
        this(PathResolver.getDataSourceFile());
    }

    /** 包可见，仅供测试指定临时路径。 */
    DataSourceStore(Path file) {
        this.file = file;
    }

    /**
     * 读回已保存的数据源配置。
     *
     * <p>文件不存在、内容为空、格式损坏、版本不认识均返回空列表，不抛异常，
     * 区别只体现在日志上。</p>
     *
     * @return 配置列表，顺序与保存时一致；永不返回 null
     */
    public List<DataSourceConfig> load() {
        if (!Files.exists(file)) {
            // 首次启动的正常状态，不值得记警告
            log.debug("数据源配置文件尚不存在：{}", file);
            return List.of();
        }

        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("数据源配置文件读取失败，将按「没有已保存的数据源」处理：{}", file, e);
            return List.of();
        }

        // 剥掉 UTF-8 BOM：用 Windows 记事本编辑这个文件时保存出来可能带 BOM，
        // 而 Jackson 会把 BOM 当成非法字符开头直接报错，整份配置因此被判定为
        // 「损坏」而隔离。为一个看不见的字符丢掉用户全部数据源不划算
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
            json = json.substring(1);
            log.debug("数据源配置文件带 BOM，已忽略：{}", file);
        }

        if (json.isBlank()) {
            log.warn("数据源配置文件是空的，将按「没有已保存的数据源」处理：{}", file);
            return List.of();
        }

        try {
            DataSourceDocument document = mapper.readValue(json, DataSourceDocument.class);
            return normalize(document);
        } catch (Exception e) {
            // 解析失败必须把原文件改名，否则下一次 save 会直接覆盖它，
            // 用户没有机会抢救
            Path kept = quarantine();
            log.warn("数据源配置文件解析失败，已改名保留为 {}，本次按「没有已保存的数据源」处理",
                    kept == null ? "（改名也失败了，原文件未动）" : kept, e);
            return List.of();
        }
    }

    /**
     * 保存数据源配置，覆盖原文件。
     *
     * <p>原子写：先写同目录下的临时文件，再改名覆盖。
     * 文件名相同的 rename 在同一文件系统内是原子操作，因此读到的永远是
     * 「完整的旧文件」或「完整的新文件」。</p>
     *
     * @param configs 要保存的配置；null 视为空列表（相当于清空已保存的配置）
     * @throws IOException 写盘失败。调用方应捕获并只记日志 ——
     *                     存盘失败不该让已经成功的内存操作回滚
     */
    public void save(List<DataSourceConfig> configs) throws IOException {
        List<DataSourceConfig> safe = configs == null ? List.of() : List.copyOf(configs);
        DataSourceDocument document = new DataSourceDocument(CURRENT_VERSION, safe);

        String json;
        try {
            json = mapper.writeValueAsString(document);
        } catch (Exception e) {
            // 序列化失败属于程序缺陷，与磁盘无关，转成 IOException 让调用方统一处理
            throw new IOException("数据源配置序列化失败", e);
        }

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        moveIntoPlace(temp, file);
        restrictToOwner(file);
    }

    /**
     * 把临时文件改名到正式位置。
     *
     * <p>优先要求原子性；个别文件系统（某些网络盘、部分 FAT 实现）不支持原子改名，
     * 这时退回普通改名：有极小概率不是原子的，但仍好过直接覆写，
     * 也好过因为做不到完美而干脆失败。</p>
     */
    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("该文件系统不支持原子改名，退回普通改名：{}", target);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 尽量把文件权限收紧到「只有本人可读」。
     *
     * <p>Windows 不支持 POSIX 权限，会抛 {@code UnsupportedOperationException}：
     * 那里靠 {@code %APPDATA%} 本身的用户隔离，因此失败是正常的，只记 debug。</p>
     */
    private static void restrictToOwner(Path target) {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("本平台不支持收紧文件权限，依赖用户目录自身的隔离：{}", target, e);
        }
    }

    /** 把损坏的文件改名留档，返回新路径；改名失败返回 null。 */
    private Path quarantine() {
        String stamp = LocalDateTime.now().format(STAMP);
        Path kept = file.resolveSibling(file.getFileName() + CORRUPT_SUFFIX + stamp);
        try {
            Files.move(file, kept, StandardCopyOption.REPLACE_EXISTING);
            return kept;
        } catch (IOException e) {
            log.warn("损坏的数据源配置文件改名失败：{}", file, e);
            return null;
        }
    }

    /**
     * 把读到的文档归一成配置列表。
     *
     * <p>版本处理现在是「只认 1」：未来若出现 2，这里要加迁移分支而不是报错，
     * 老用户升上来时他们的文件还是 1。</p>
     */
    private static List<DataSourceConfig> normalize(DataSourceDocument document) {
        if (document == null || document.datasources() == null) {
            return List.of();
        }
        if (document.version() != CURRENT_VERSION) {
            log.warn("数据源配置文件版本为 {}，当前程序只认识 {}，将按「没有已保存的数据源」处理",
                    document.version(), CURRENT_VERSION);
            return List.of();
        }
        // 逐项过滤掉缺名字/缺类型的残缺条目：单条坏掉不该让整份配置作废
        return document.datasources().stream()
                .filter(DataSourceStore::isUsable)
                .toList();
    }

    private static boolean isUsable(DataSourceConfig config) {
        if (config == null || config.name() == null || config.name().isBlank()
                || config.type() == null || config.type().isBlank()) {
            log.warn("跳过一条残缺的数据源配置（缺少名称或类型）");
            return false;
        }
        return true;
    }

    /**
     * 文件里的顶层结构。
     *
     * <p>用 record 让 Jackson 直接映射，不引额外的注解依赖。
     * 缺字段时 Jackson 会填 null / 0，由 {@link #normalize} 兜住。</p>
     */
    record DataSourceDocument(int version, List<DataSourceConfig> datasources) {
    }
}
