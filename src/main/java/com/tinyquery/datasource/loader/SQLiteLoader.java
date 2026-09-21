package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.datasource.spec.FieldSpec;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * SQLite 数据源加载器。
 *
 * <p>SQLite 是文件数据库：没有主机、端口、账号与密码，整个库就是一个文件，
 * 因此配置项与其余 JDBC 源完全不同，也是本加载器最想验证的一点 ——
 * 前端表单能否完全由后端下发的字段清单驱动，而不依赖任何具体源的假设。</p>
 */
@Component
public class SQLiteLoader extends JdbcLoader {

    @Override
    public String type() {
        return "sqlite";
    }

    @Override
    public String label() {
        return "SQLite 文件";
    }

    @Override
    protected Integer defaultPort() {
        // SQLite 不通过网络连接，端口无意义。
        // 返回 null 让基类跳过端口校验，与 fields() 里不声明 port 字段保持一致
        return null;
    }

    @Override
    public List<FieldSpec> fields() {
        return List.of(
                // 文件路径：SQLite 的全部连接信息
                FieldSpec.text("path", "数据库文件路径", true,
                        "SQLite 数据库文件的绝对路径或相对运行目录的路径",
                        "data/app.db"),
                FieldSpec.text("table", "表名", true,
                        "该文件中的某张表。SQLite 一个文件里通常有多张表，"
                                + "每张表注册为一个数据源",
                        "users")
        );
    }

    /**
     * SQLite 是文件数据库，路径写错时驱动会静默新建一个空库，
     * 直到查询报「表不存在」才发现。这里提前拦下，给出明确原因。
     */
    @Override
    protected void validateExtra(DataSourceConfig config) {
        String path = config.requireString("path");

        File file = new File(path);
        if (!file.exists()) {
            throw new DataSourceException(
                    "数据库文件不存在：" + file.getAbsolutePath()
                            + "（SQLite 不会自动创建，请检查路径）");
        }
        if (!file.isFile()) {
            throw new DataSourceException("该路径不是文件：" + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new DataSourceException("文件不可读（请检查权限）：" + file.getAbsolutePath());
        }
    }

    @Override
    protected String resolveUrl(DataSourceConfig config) {
        // 配置里没有 username，基类会走单参数 DriverManager.getConnection
        return "jdbc:sqlite:" + config.requireString("path");
    }
}
