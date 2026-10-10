package com.tinyquery.datasource.loader;

import com.tinyquery.i18n.DesktopLocale;
import com.tinyquery.i18n.Messages;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 每条用例前后把界面语言复位成中文。
 *
 * <h2>为什么必须有这个</h2>
 *
 * <p>{@code Messages} 的语言是<b>进程级静态状态</b>，而本地化用例会把它改成英文。
 * JUnit 不保证执行顺序，一旦本地化用例先跑，后面所有断言中文文案的用例都会失败，
 * 报错形如「应为中文，实际：CSV File」。这种失败极具误导性 ——
 * 会让人去查文案，而真正的原因是测试之间的状态污染。</p>
 *
 * <h2>为什么写成扩展而不是 {@code @BeforeEach}</h2>
 *
 * <p>踩过两次坑：</p>
 * <ol>
 *   <li>写在外部测试类的 {@code @BeforeEach} 里 —— <b>不作用于 {@code @Nested} 内部类</b>，
 *       于是内部类的用例完全没有复位，英文一路泄漏。</li>
 *   <li>把扩展写成测试类的<b>静态内部类</b>再 {@code @ExtendWith} 引用 ——
 *       同样不生效。</li>
 * </ol>
 *
 * <p>所以做成独立的顶层类，由每个 {@code @Nested} 类各自声明。
 * 这一点不写下来，下次还会有人把它"整理"回内部类。</p>
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
        // 【只设 Messages，不要调 DesktopLocale.apply()】
        //
        // apply() 的语义是"从环境变量/配置文件解析出语言并覆盖 Messages" ——
        // 方向恰好相反。设完中文再 apply()，会被文件里的 en 立刻改回去，
        // 于是复位看起来完全没生效（实测就卡在这里：断言一直报英文）。
        // 测试要的是直接控制 Messages，不碰真实环境。
        Messages.setDesktopLocale(Messages.ZH);
    }
}
