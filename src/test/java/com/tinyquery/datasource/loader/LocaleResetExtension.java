package com.tinyquery.datasource.loader;

import com.tinyquery.i18n.DesktopLocale;
import com.tinyquery.i18n.Messages;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 每条用例前后把界面语言复位成中文。
 *
 * <p>{@code Messages} 的语言是进程级静态状态，而本地化用例会把它改成英文。
 * JUnit 不保证执行顺序，一旦本地化用例先跑，后面所有断言中文文案的用例都会失败，
 * 报错形如「应为中文，实际：CSV File」。这种失败极具误导性 ——
 * 会让人去查文案，而真正的原因是测试之间的状态污染。</p>
 *
 * <p>写成扩展而不是 {@code @BeforeEach} 的原因有两条：写在外部测试类的
 * {@code @BeforeEach} 里不作用于 {@code @Nested} 内部类，于是内部类的用例
 * 完全没有复位、英文一路泄漏；把扩展写成测试类的静态内部类再
 * {@code @ExtendWith} 引用同样不生效。所以做成独立的顶层类，
 * 由每个 {@code @Nested} 类各自声明。这一点不写下来，
 * 下次还会有人把它「整理」回内部类。</p>
 */
class LocaleResetExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        reset();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        reset();
    }

    private void reset() {
        // 只设 Messages，不要调 DesktopLocale.apply()。
        //
        // apply() 的语义是「从环境变量/配置文件解析出语言并覆盖 Messages」——
        // 方向恰好相反。设完中文再 apply()，会被文件里的 en 立刻改回去，
        // 于是复位看起来完全没生效（实测就卡在这里：断言一直报英文）。
        // 测试要的是直接控制 Messages，不碰真实环境。
        Messages.setDesktopLocale(Messages.ZH);
    }
}
