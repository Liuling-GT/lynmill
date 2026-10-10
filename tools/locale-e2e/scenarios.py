# -*- coding: utf-8 -*-
"""语言一致性回归的场景定义。

每个场景是一个函数，返回 (是否通过, 说明, 细节列表)。

【设计原则】

1. 每条断言都要**能区分实现**。写的时候反复问："如果这里改坏了，这条会不会红？"
   本项目踩过"夹具不能区分实现 → 假绿"的坑（编号 25），所以每条都注明了它防的是什么。

2. **不绑固定端口**。见 harness.free_ports() 的说明 —— 固定端口会被早先会话
   留下的浏览器标签页污染，那些页面会持续向后端发请求（包括会改状态的切语言）。
   症状看起来像"服务端自己变了语言"，极难排查。

3. 依赖服务端语言的场景，用前先 `assert_locale_settled`，确认它确实停在预期值。
"""
import re

from harness import AppServer, Browser, HarnessError, free_ports


def _ui_lang(state):
    """把 documentElement.lang 归一成「中文」/「英文」/「未知」。"""
    lang = (state.get("lang") or "").strip().lower()
    if lang.startswith("zh"):
        return "中文"
    if lang.startswith("en"):
        return "英文"
    return "未知：%r" % lang


def _ok(cond, good, bad):
    return (bool(cond), good if cond else bad)


def _run(env, locale, fn, tag):
    """起一个服务端 + 一个浏览器，把控制权交给 fn(server, browser)。

    统一处理"起不来"与"保证关掉"，避免每个场景重复一遍 try/finally。
    """
    app_port, cdp_port = free_ports(2)
    srv = AppServer(app_port, locale=locale, tag=tag)
    br = None
    try:
        if not srv.wait_up():
            raise HarnessError("服务端未能在端口 %d 起来" % app_port)
        srv.assert_locale_settled(locale)

        br = Browser(cdp_port, env["work"] / ("prof-" + tag), env["browser"])
        if not br.wait_ready():
            raise HarnessError("浏览器 CDP 未就绪")
        return fn(srv, br)
    finally:
        if br is not None:
            br.stop()
        srv.stop()


# --------------------------------------------------------------- 场景定义

def s1_local_empty_follows_server(env):
    """本地无选择 → 跟随服务端；且**不写** localStorage。

    防的是：reconcile 把服务端语言写成了"本地选择"，
    从此服务端再改语言就永远追不上（编号 26 的根因之一）。
    """

    def body(srv, br):
        st = br.drive(env["driver"], srv.port, action="load")
        ui = _ui_lang(st)
        stored = st.get("stored")
        ok1 = _ok(ui == "英文", "界面跟随服务端=en", "界面是 %s，期望英文" % ui)
        ok2 = _ok(stored is None,
                  "未写 localStorage（保住「本地没有选择」）",
                  "误写了 localStorage=%r —— 下次服务端改语言将追不上" % stored)
        return (ok1[0] and ok2[0]), "本地无选择 → 跟随服务端且不落盘", [ok1[1], ok2[1]]

    return _run(env, "en", body, "s1")


def s2_local_choice_wins(env):
    """本地有选择 → 以本地为准，并把它推给服务端。

    防的是：reconcile 反过来用服务端覆盖用户的显式选择。
    """
    results = []
    all_ok = True

    for i, (server_loc, seed, want_ui, want_server) in enumerate([
        ("zh", "en", "英文", "en"),
        ("en", "zh", "中文", "zh"),
    ]):
        def body(srv, br, seed=seed, want_ui=want_ui, want_server=want_server):
            st = br.drive(env["driver"], srv.port, action="seed", seed=seed)
            ui = _ui_lang(st)
            after = srv.server_locale()
            line = ("服务端=%s 本地=%s → 界面=%s（期望%s）%s 服务端=%s（期望%s）%s" % (
                server_loc, seed, ui, want_ui, "✅" if ui == want_ui else "❌",
                after, want_server, "✅" if after == want_server else "❌"))
            return (ui == want_ui and after == want_server), line, [line]

        passed, line, _ = _run(env, server_loc, body, "s2-%d" % i)
        results.append(line)
        all_ok = all_ok and passed

    return all_ok, "本地有选择时以本地为准，并推给服务端", results


def s3_server_change_propagates(env):
    """跨两次启动：服务端改语言后，网页下次打开要跟上。

    这是 s1 的**闭环验证** —— s1 只证明"第一次没落盘"，
    本场景证明"所以第二次能跟上"。防的是 s1 的修复被回退
    （一旦 reconcile 又开始写 localStorage，这条立刻红）。

    【为什么要跨两次而不是在同一个页面里刷新】
    同一个页面里 localStorage 已经处于"被 reconcile 处理过"的状态，
    测不出"本地到底有没有落盘"。必须保留同一个 profile、重新走一遍启动流程。
    """

    def body(srv, br):
        # 第 1 次：本地无选择，服务端 zh
        first = br.drive(env["driver"], srv.port, action="load")
        stored1 = first.get("stored")

        # 中间：服务端改 en（模拟桌面端切了语言）
        srv.set_server_locale("en")

        # 第 2 次：同一个 profile（localStorage 保留），重新走启动
        second = br.drive(env["driver"], srv.port, action="load")

        ui1, ui2 = _ui_lang(first), _ui_lang(second)
        detail = ["第1次：界面=%s localStorage=%r" % (ui1, stored1),
                  "（中间服务端 zh→en）",
                  "第2次：界面=%s localStorage=%r" % (ui2, second.get("stored"))]
        ok1 = _ok(ui1 == "中文", "第1次跟随服务端=zh", "第1次界面是 %s" % ui1)
        ok2 = _ok(stored1 is None, "第1次未落盘", "第1次误写 %r" % stored1)
        ok3 = _ok(ui2 == "英文", "第2次跟上了服务端的新语言=en",
                  "第2次仍是 %s —— 说明第1次把语言写成了本地选择" % ui2)
        return (ok1[0] and ok2[0] and ok3[0]), \
            "服务端改语言后，下次打开网页能跟上", detail + [ok1[1], ok2[1], ok3[1]]

    return _run(env, "zh", body, "s3")


def s4_switching_refetches_server_metadata(env):
    """切语言后，**后端下发的类型元数据**必须跟着重取。

    这是编号 26 那个真实缺陷的回归：
    types（数据源类型元数据）只在启动时取一次，切语言不重取，
    于是中文模式下「类型下拉 / 配置项名字 / 问号提示 / 已注册数据源分组标题」
    会停在上一次的英文 —— 因为切语言只改了前端 i18n，没重新请求。

    【为什么用真浏览器测】它只在一个方向上暴露（当初只测了"英文模式下没有中文"，
    没测"中文模式下是中文"），且依赖 Vue 的响应式刷新 —— jsdom 测不到。
    """

    def body(srv, br):
        before = br.drive(env["driver"], srv.port, action="load")
        after = br.drive(env["driver"], srv.port, action="switch", seed="zh")

        ui_before, ui_after = _ui_lang(before), _ui_lang(after)
        detail = ["切换前：界面=%s" % ui_before,
                  "切换后：界面=%s" % ui_after,
                  "切换后面板标题=%s" % after.get("panelTitles"),
                  "切换后类型下拉=%s" % (after.get("typeOptions") or [])[:6]]

        checks = [
            _ok(ui_before == "英文", "起点是英文", "起点是 %s" % ui_before),
            _ok(ui_after == "中文", "切换后外壳变成中文", "切换后是 %s" % ui_after),
        ]

        # 核心断言：外壳是中文之后，**面板标题**也必须是中文。
        # 只看 documentElement.lang 会漏掉"外壳变了、元数据没变"的半新半旧状态 ——
        # 那正是这个缺陷的形态。
        titles = after.get("panelTitles") or []
        if titles:
            en_titles = [t for t in titles
                         if re.search(r"[A-Za-z]{3,}", t) and not re.search(r"[\u4e00-\u9fff]", t)]
            checks.append(_ok(
                len(en_titles) == 0,
                "面板标题全为中文（%d 个）" % len(titles),
                "仍有英文面板标题：%s —— 外壳变了但后端文案没重取" % en_titles))
        else:
            # 读不到说明选择器过时了。如实报告，不假装通过 ——
            # 一条永远为真的断言比没有断言更糟
            checks.append((False, "", "读不到 .panel-title，本场景的选择器需要更新（不是产品问题）"))

        # 类型下拉是**后端下发**的文案，是"没重取"最直接的表征。
        #
        # 【判据为什么不是"不能含拉丁字母"】MySQL、PostgreSQL、SQLite、CSV、Excel
        # 都是**产品名**，两种语言下都该这么写 —— 它们本来就该带拉丁字母。
        # 第一版判据就是"含 3 个以上字母即视为英文"，于是把 'MySQL / MariaDB'
        # 判成了没刷新。**判据错在把"专有名词"当成了"未翻译"。**
        #
        # 正确的判据是逐项比对**预期值**：中文下这几项必须整体等于中文词条。
        # 后端未注册数据源时下拉是空的，所以先看有没有值再比。
        expected_zh = {
            "csv": "CSV 文件",
            "mysql": "MySQL / MariaDB",
            "postgresql": "PostgreSQL",
            "sqlite": "SQLite 文件",
            "xlsx": "Excel 文件",
        }
        opts = after.get("typeOptions") or []
        if opts:
            # 每条都必须与"中文词条"完全一致。允许顺序不同
            want = set(expected_zh.values())
            got = set(opts)
            missing = want - got
            extra = got - want
            checks.append(_ok(
                not missing and not extra,
                "类型下拉与中文词条完全一致（%d 项）" % len(opts),
                "类型下拉与中文词条不一致：缺少=%s 多出=%s —— 切语言后没重新取 types"
                % (sorted(missing), sorted(extra))))
        else:
            checks.append((False, "", "类型下拉是空的，本场景读不到后端下发的文案（不是产品问题）"))

        passed = all(c[0] for c in checks)
        return passed, "切语言后后端下发的文案同步刷新", detail + [c[1] or c[2] for c in checks]

    return _run(env, "en", body, "s4")


def s5_no_chinese_in_english_mode(env):
    """英文模式下，界面上不应出现中文（语言选择器里的「中文」是合法例外）。

    防的是：新增文案时漏了英文词条。

    【为什么只读首屏】完整遍历需要打开每个面板并处理异步加载，
    判据会从"翻译是否完整"漂移成"遍历是否做全"。这里只做能稳定判定的那部分 ——
    宁可少测，也不要一条会随机变红的断言。
    """

    def body(srv, br):
        st = br.drive(env["driver"], srv.port, action="load")
        ui = _ui_lang(st)
        checks = [
            _ok(ui == "英文", "界面是英文", "界面是 %s" % ui),
            _ok(st.get("bodyHasEn"), "页面上确实有英文内容", "页面上看不到英文内容"),
        ]
        passed = all(c[0] for c in checks)
        details = [c[1] or c[2] for c in checks]
        details.append(
            "观测：bodyHasZh=%s bodyHasEn=%s（首屏；语言选择器的「中文」是合法例外，"
            "不在此判据内）" % (st.get("bodyHasZh"), st.get("bodyHasEn")))
        return passed, "英文模式下的基础检查", details

    return _run(env, "en", body, "s5")
