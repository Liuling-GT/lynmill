package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.datasource.spec.FieldSpec;
import com.tinyquery.i18n.Messages;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * SQLite 数据源加载器
 * SQLite 是文件数据库：没有主机、端口、账号与密码，整个库就是一个文件
 * 因此配置项与其余 JDBC 源完全不同，也是本加载器最想验证的一点
 * 前端表单能否完全由后端下发的字段清单驱动，而不依赖任何具体源的假设
 */
@Component
public class SQLiteLoader extends JdbcLoader {

    @Override
    public String type() {
        return "sqlite";
    }

    @Override
    public String label() {
        return Messages.get("type.sqlite");
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
                FieldSpec.text("path", Messages.get("field.dbFilePath"), true,
                        Messages.get("field.sqlitePathDesc"),
                        "data/app.db"),
                FieldSpec.text("table", Messages.get("field.table"), true,
                        Messages.get("field.sqliteTableDesc"),
                        "users")
        );
    }

    /**
     * SQLite 是文件数据库，路径写错时驱动会静默新建一个空库
     * 直到查询报「表不存在」才发现。这里提前拦下，给出明确原因
     */
    @Override
    protected void validateExtra(DataSourceConfig config) {
        String path = config.requireString("path");

        File file = new File(path);
        if (!file.exists()) {
            throw new DataSourceException("error.sqliteFileNotFound",
                    new Object[]{file.getAbsolutePath()},
                    Messages.get("error.sqliteFileNotFound", file.getAbsolutePath())
                            + Messages.get("error.sqliteNoAutoCreate"));
        }
        if (!file.isFile()) {
            throw new DataSourceException("error.notAFile", new Object[]{file.getAbsolutePath()},
                    Messages.get("error.notAFile", file.getAbsolutePath()));
        }
        if (!file.canRead()) {
            throw new DataSourceException("error.fileNotReadable", new Object[]{file.getAbsolutePath()},
                    Messages.get("error.fileNotReadable", file.getAbsolutePath()));
        }
    }

    @Override
    protected String resolveUrl(DataSourceConfig config) {
        // 配置里没有 username，基类会走单参数 DriverManager.getConnection
        return "jdbc:sqlite:" + config.requireString("path");
    }
}
