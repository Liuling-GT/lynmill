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
 * <h2>这个类存在的唯一理由：一个坏数据源不能拖垮整个程序</h2>
 *
 * <p>没有它的时候，用户重启程序会看到什么？配置读回来了，但其中一个 CSV 被删了 ——
 * 加载抛异常，Spring 启动失败，程序退出。用户看到的是「双击没反应」，
 * <b>而且他连能删掉那个坏数据源的界面都进不去</b>。这是个死结：
 * 修问题需要界面，界面需要启动成功，启动成功需要问题先被修掉。</p>
 *
 * <p>所以这里的原则很硬：<b>逐个恢复，坏的就跳过</b>，
 * 把状态记进 {@link DataSourceManager}，让界面去告诉用户哪一条坏了、为什么坏。</p>
 *
 * <h2>为什么是 ApplicationRunner（同步）而不是异步</h2>
 *
 * <p>异步能让界面更早出现，但会带来一个新的、更难懂的现象：
 * 用户一进界面就查询，却被告知「数据源不存在」—— 因为后台还没加载完。
 * 相比之下「启动时多等一会儿」是用户完全能理解的。
 * 当前数据量级下，这个取舍明显偏向简单可预测。</p>
 *
 * <p>另有一个刻意的决定：<b>不做懒加载</b>（启动只读配置、查询时才加载数据）。
 * 那需要把「注册即可用」这个契约拆开，牵动 get / getByTableName / refresh 及其全部测试，
 * 为的是一个当前量级下不存在的问题。将来真要优化，方向是加快加载速度，
 * 而不是改变加载时机。</p>
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
            // 失败清单在这里打全：用户看到界面上的提示后，多半会来翻日志找细节
            log.warn("已恢复 {} 个数据源，{} 个暂不可用（详见界面数据源列表）", ok, failed);
            for (DataSourceInfo info : manager.list()) {
                if (info.status() != LoadStatus.OK) {
                    log.warn("  不可用：{}（{}）—— {}", info.name(), info.status(), info.failureReason());
                }
            }
        }
    }
}
