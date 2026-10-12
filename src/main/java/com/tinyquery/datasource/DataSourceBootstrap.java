package com.tinyquery.datasource;

import com.tinyquery.datasource.spec.DataSourceConfig;
import com.tinyquery.datasource.spec.DataSourceInfo;
import com.tinyquery.datasource.spec.LoadStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时恢复已保存的数据源。
 *
 * <p>逐个恢复，坏的就跳过：某个数据源加载失败（例如对应的 CSV 已被删除）不能让
 * Spring 启动失败。否则用户看到的是「双击没反应」，而且连能删掉那个坏数据源的
 * 界面都进不去 —— 修问题需要界面，界面需要启动成功，启动成功又需要问题先被修掉。
 * 失败状态记进 {@link DataSourceManager}，由界面告诉用户哪一条坏了、为什么坏。</p>
 *
 * <p>用同步的 ApplicationRunner 而不是异步：异步能让界面更早出现，但用户一进界面
 * 就查询时，后台可能还没加载完，得到的是「数据源不存在」。相比之下「启动时多等
 * 一会儿」更好理解。当前数据量级下这个取舍偏向简单可预测。</p>
 *
 * <p>不做懒加载（启动只读配置、查询时才加载数据）：那需要把「注册即可用」这个
 * 契约拆开，牵动 get / getByTableName / refresh 及其全部测试，为的是一个当前
 * 量级下不存在的问题。真要优化，方向是加快加载速度，而不是改变加载时机。</p>
 */
@Component
public class DataSourceBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSourceBootstrap.class);

    private final DataSourceManager manager;
    private final DataSourceStore store;

    public DataSourceBootstrap(DataSourceManager manager, DataSourceStore store) {
        this.manager = manager;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<DataSourceConfig> saved = store.load();

        if (saved.isEmpty()) {
            log.info("没有已保存的数据源，跳过恢复");
            return;
        }

        int ok = 0;
        int failed = 0;
        for (DataSourceConfig config : saved) {
            // restore 内部已经把所有异常吞掉并归类，这里不会抛
            if (manager.restore(config)) {
                ok++;
            } else {
                failed++;
            }
        }

        if (failed == 0) {
            log.info("已恢复 {} 个数据源", ok);
        } else {
            // 失败清单在这里打全：界面上的提示之外，用户多半会来翻日志找细节
            log.warn("已恢复 {} 个数据源，{} 个暂不可用（详见界面数据源列表）", ok, failed);
            for (DataSourceInfo info : manager.list()) {
                if (info.status() != LoadStatus.OK) {
                    log.warn("  不可用：{}（{}）—— {}", info.name(), info.status(), info.failureReason());
                }
            }
        }
    }
}
