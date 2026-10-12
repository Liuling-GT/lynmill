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
from pathlib import Path

from harness import AppServer, Browser, HarnessError, free_ports, kill_all_stray


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


def _run(env, locale, fn, tag, theme=None):
    """起一个服务端 + 一个浏览器，把控制权交给 fn(server, browser)。

    统一处理"起不来"与"保证关掉"，避免每个场景重复一遍 try/finally。
    """
    # 起新的一对之前先清一次残留浏览器。
    #
    # 【为什么每次都要清，而不是只在 run_all 开头清一次】
    # 一个场景内部可能连续起好几对（s2 两对、s6 四对），端口是动态分配的，
    # **上一对的端口很可能被下一对拿到**。Browser.stop() 杀了主进程，但
    # Edge/Chrome 的 renderer 子进程可能还活着几百毫秒，里面的页面会继续
    # 往后端发请求 —— 包括会改状态的 pushTheme。
    # 于是「上一对把主题推成 bamboo」会打到「下一对刚起来的服务端」上，
    # 表现为"服务端主题莫名其妙是 bamboo"，看起来像产品缺陷。
    #
    # 这不是理论：编号 51 加 s6 时就实测撞到过一次（基线偶发红，
    # 单独重跑又全绿）。清一次的成本是一两秒，比事后排查便宜得多。
    kill_all_stray()

    app_port, cdp_port = free_ports(2)
    srv = AppServer(app_port, locale=locale, theme=theme, tag=tag)
    br = None
    try:
        if not srv.wait_up():
            raise HarnessError("服务端未能在端口 %d 起来" % app_port)
        srv.assert_locale_settled(locale)
        if theme:
            srv.assert_theme_settled(theme)

        br = Browser(cdp_port, env["work"] / ("prof-" + tag), env["browser"])
        if not br.wait_ready():
            raise HarnessError("浏览器 CDP 未就绪")
        return fn(srv, br)
    finally:
        if br is not None:
            br.stop()
        srv.stop()


def _rgb_to_hex(css):
    """把浏览器读回来的 rgb(...) 归一成小写 #rrggbb。

    浏览器对实色一律回 rgb(...)，而服务端公布的 accent 是 #rrggbb ——
    不归一化就没法比。这一条踩过：六个色卡全被判成"不一致"，
    实际是同一个颜色。
    """
    if not css:
        return None
    css = css.strip()
    if css.startswith("#"):
        return css.lower()
    m = re.match(r"rgba?\((\d+),\s*(\d+),\s*(\d+)", css)
    return "#%02x%02x%02x" % tuple(int(m.group(i)) for i in (1, 2, 3)) if m else None


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


def s6_theme_reconcile(env):
    """主题的对齐规则与语言同构：本地无选择跟随服务端，本地有选择压过服务端。

    防的是两件事：
      1. reconcile 把服务端主题写成了"本地选择" —— 从此服务端再改主题就追不上
         （与编号 26 的根因同型，语言那边已经踩过一次）
      2. 本地有选择时不推给服务端 —— 桌面托盘/控制窗口会一直停在旧主题，
         而网页已经变了，出现"一个程序两套主题"

    【为什么颜色也要断言】主题的"值"是 CSS 变量，属性对了但变量没生效
    （选择器写错、主题块缺失）时页面看起来仍是默认色。这里同时断言
    getComputedStyle 读回来的 --accent，把"属性对了但颜色没换"挡在外面。
    """
    results = []
    all_ok = True

    # 每个用例：(服务端主题, 本地种子, 期望 data-theme, 期望 accent, 期望服务端)
    #
    # 【色值是写死的】这里刻意不向服务端问色值 —— 那正是 s7 干的事。
    # s6 要验的是"启动时以谁为准"，色值只用来确认属性真的生效了。
    # 写死的代价是改色时要同步改这里：竹青 2026-10-12 从 #078d58 放到 #08965e。
    cases = [
        ("coffee", None, "coffee", "#b06b05", "coffee"),
        ("coffee", "bamboo", "bamboo", "#08965e", "bamboo"),
        ("violet", "indigo", "indigo", "#6366f1", "indigo"),
        # 切回默认主题时 data-theme 属性应当被移除（默认主题不写属性）
        ("coffee", "violet", None, "#8b5cf6", "violet"),
    ]

    for i, (server_theme, seed, want_attr, want_accent, want_server) in enumerate(cases):
        def body(srv, br, seed=seed, want_attr=want_attr,
                 want_accent=want_accent, want_server=want_server,
                 server_theme=server_theme):
            if seed is None:
                st = br.drive(env["driver"], srv.port, action="load")
            else:
                st = br.drive(env["driver"], srv.port, action="seed-theme", seed=seed)

            attr = st.get("themeAttr")
            accent = _rgb_to_hex(st.get("accent"))
            stored = st.get("themeStored")
            after = srv.server_theme()

            line = ("服务端=%-7s 本地=%-8s → 属性=%-8s（期望%-8s）accent=%-9s（期望%-9s）"
                    " 服务端=%s（期望%s）" % (
                        server_theme, seed or "无", attr, want_attr, accent, want_accent,
                        after, want_server))
            checks = [
                _ok(attr == want_attr,
                    "", "data-theme 是 %r，期望 %r —— 主题没对齐" % (attr, want_attr)),
                _ok(accent == want_accent,
                    "", "--accent 算出来是 %s，期望 %s —— 属性对了但颜色没换"
                    % (accent, want_accent)),
                _ok(after == want_server,
                    "", "服务端主题是 %r，期望 %r —— 本地选择没推给服务端，"
                        "桌面端会停在旧主题" % (after, want_server)),
            ]
            # 本地无选择时不许落盘。落了盘就再也跟不上服务端了（同 s1）
            if seed is None:
                checks.append(_ok(
                    stored is None, "",
                    "本地无选择却写了 localStorage=%r —— 下次服务端改主题将追不上" % stored))
            else:
                checks.append(_ok(
                    stored == seed, "",
                    "localStorage 是 %r，期望 %r —— 本地选择没被记住" % (stored, seed)))
            return all(c[0] for c in checks), line, [line] + [c[1] for c in checks if not c[0]]

        passed, line, extra = _run(env, "zh", body, "s6-%d" % i, theme=server_theme)
        results.append(line)
        # body 返回的 extra 里已经含了 line 本身，这里不要再加一遍
        results.extend(extra[1:])
        all_ok = all_ok and passed

    return all_ok, "主题对齐：本地无选择跟随服务端且不落盘，本地有选择压过服务端", results


# 实心填充的期望色例外表：主题 value → 期望底色。
#
# 【为什么不能一律拿 accent 比】竹青把 --el-color-primary 单独指向了 #09a466
# （比它的 --accent #08965e 浅一档）—— 因为 --accent 同时还要当白底上的小字色
# （表单校验红字、提示条文字），一起放浅会把那些小字拖下去。所以期望值要按主题
# 查表，不能一律等于 accent。
#
# 【这张表顺带卡住两件事】没在表里的主题仍然严格要求 填充色 == accent，
# 所以哪天有人把覆盖写成全局的（不加 [data-theme='bamboo'] 限定），另外三条会
# 立刻红；反过来，要是覆盖没生效，竹青那条也会红。两个方向都被覆盖。
def _strip_css_comments(text):
    """去掉 CSS 注释再解析。

    【必须做】tokens.css 的注释里大量举例提到选择器和令牌名（例如解释这段规则时
    把 `:root[data-theme='bamboo'] .el-tabs__item.is-active` 原样写进了注释）。
    不去注释的话，正则会把这些说明文字也当成真规则解析出来 ——
    踩过：解析结果里混进了「0)，Element 自己的 :root 是 (0」这种东西。
    """
    return re.sub(r"/\*.*?\*/", "", text, flags=re.S)


def _fill_override(path):
    """从 tokens.css 的各主题块里读出显式覆盖的 --el-color-primary。

    【为什么现读而不是写死】竹青把 --el-color-primary 单独指向 #09a466，
    其余主题沿用 :root 的 var(--accent)。写死的话改一次色要同步两处 ——
    上一轮就是这么误报的（期望表里留着旧色值）。
    默认主题的 :root 写的是 `var(--accent)`，那是"等于 accent"不是覆盖，跳过。
    """
    try:
        text = _strip_css_comments(path.read_text(encoding="utf-8"))
    except OSError:
        return {}
    out = {}
    m = re.search(r":root\s*\{(.*?)\n\}", text, re.S)
    blocks = {"violet": m.group(1)} if m else {}
    for name in ("indigo", "bamboo", "coffee"):
        m = re.search(r":root\[data-theme='%s'\]\s*\{(.*?)\n\}" % name, text, re.S)
        if m:
            blocks[name] = m.group(1)
    for name, body in blocks.items():
        m = re.search(r"--el-color-primary:\s*(#[0-9a-fA-F]{3,8})", body)
        if m:
            out[name] = m.group(1).lower()
    return out


PRIMARY_BUTTON_OVERRIDE = _fill_override(
    Path(__file__).resolve().parents[2]
    / "tinyquery-frontend/src/assets/tokens.css")


# 哪些主题的实心填充**应当比自己的 --accent 浅**（只钉"哪个主题 + 方向"）。
#
# 【为什么不能只靠上面那张现读表】现读解决了"改色要同步两处"，但会瞎：
# 覆盖整条失效时（令牌名拼错、规则被删），现读到的期望值会**跟着一起变回
# --accent**，于是填充色等于 --accent 也判为通过 —— 测试永远绿。
# 注入"令牌名拼错"时就是这样漏掉的。所以这里再钉一条与色值无关的意图：
# 竹青的填充必须**严格浅于**它的 --accent。色值怎么调都不会碰这几行，
# 而覆盖一旦失效立刻红。
FILL_SHOULD_BE_LIGHTER = {"bamboo"}


def _pinned_text_selectors(path):
    """tokens.css 里"把文字色钉回 --accent"的那几条选择器。

    【为什么需要一条静态检查】标签页选中标题、勾选框选中那行小字这两处，
    只在有查询结果 / 有图表时才渲染；s7 的驱动停在设置页，取不到它们
    （浏览器里那条检查写了，但实际不会触发）。所以这里退一步：
    只证明**规则在**，且指向 --accent。证明"浏览器真的用上了"要靠
    真浏览器验收脚本 —— 那条能拿到渲染后的颜色。
    """
    try:
        text = _strip_css_comments(path.read_text(encoding="utf-8"))
    except OSError:
        return set()
    found = set()
    # 找形如 `:root[data-theme='x'] .sel { color: var(--accent); }` 的规则。
    # 注意多选择器时**每一条各自带一份 :root[...] 前缀**，而正则只吃掉了最前面
    # 那一份 —— 所以要逐条再把前缀剥掉，否则除第一条外都会被当成"没带前缀"而漏掉
    # （踩过：只认出 2 条里的 1 条）
    for m in re.finditer(
            r":root\[data-theme='[\w-]+'\]\s*([^{}]+?)\s*\{([^}]*)\}", text):
        selectors, body = m.group(1), m.group(2)
        if not re.search(r"color:\s*var\(--accent\)\s*;", body):
            continue
        for sel in selectors.split(","):
            sel = re.sub(r"^:root\[data-theme='[\w-]+'\]\s*", "", sel.strip()).strip()
            if sel:
                found.add(sel)
    return found


# 必须钉回 --accent 的文字位置。Element 把 --el-color-primary 当文字色用在这两处，
# 实心填充一放浅就会连累它们，而"填充放浅"的本意不包括文字
PINNED_TEXT_SELECTORS = {
    ".el-tabs__item.is-active",
    ".el-checkbox__input.is-checked + .el-checkbox__label",
}

_PINNED_FOUND = _pinned_text_selectors(
    Path(__file__).resolve().parents[2]
    / "tinyquery-frontend/src/assets/tokens.css")


def _lum(hex_color):
    """相对亮度。只用来比大小，不需要精确到 WCAG 的公式细节。"""
    h = (hex_color or "").lstrip("#")
    if len(h) != 6:
        return -1.0

    def lin(v):
        v = v / 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4

    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def s7_theme_switch_matches_server_contract(env):
    """逐套点过主题按钮：页面上算出来的色必须等于服务端 /themes 公布的色。

    防的是"色卡宣传一个颜色、切过去是另一个颜色"。服务端 /themes 把 accent
    一起给出来，设置页正是用它画色点的 —— 那条链上任何一环抄错色值，
    用户看到的就是「色点是绿的、切过去是蓝的」。

    【为什么必须真点】色点的颜色来自接口数据（一个 inline style），
    而实际生效的颜色来自 tokens.css 的变量块。两者是不同的来源，
    只有真点一遍、再读 getComputedStyle，才能证明它们对得上。

    顺带验证：每次点击后恰好一个选项处于选中态，且就是刚点的那个。
    """
    def body(srv, br):
        themes = srv.supported_themes()
        if not themes:
            return False, "主题清单为空", ["服务端 /api/preferences/themes 返回空"]

        checks = []
        details = []

        # 静态先卡一道：把文字色钉回 --accent 的规则必须在。
        # 这一条与页面状态无关，所以那些元素没渲染时它是唯一有效的护栏
        missing = PINNED_TEXT_SELECTORS - _PINNED_FOUND
        checks.append(_ok(
            not missing,
            "tokens.css 已把 %d 处文字色钉回 --accent" % len(_PINNED_FOUND),
            "tokens.css 里没有把文字色钉回 --accent 的规则，缺：%s —— "
            "实心填充放浅会把这几处文字一起带浅" % sorted(missing)))

        # 先加载一次，把设置页打开（switch-theme 自己会点设置页）
        st = br.drive(env["driver"], srv.port, action="load")
        opts = st.get("themeOptions") or []
        labels = [o["label"] for o in opts]
        checks.append(_ok(
            len(opts) == len(themes),
            "设置页有 %d 个主题选项，与服务端清单一致" % len(opts),
            "设置页有 %d 个主题选项，服务端公布 %d 个 —— 两边的清单不一致"
            % (len(opts), len(themes))))
        checks.append(_ok(
            all(labels), "选项标签（顺序即服务端清单顺序）：%s" % labels,
            "有主题选项读不到标签：%s" % labels))

        # 这一轮是"本地没有选择、服务端是咖啡色"（s7 用 theme="coffee" 起服务端）。
        # 设置页的选中态必须显示咖啡色 —— 若它读的是 localStorage（空的）
        # 就会显示默认主题，出现"页面是咖啡色、设置页选中紫罗兰"。
        # 用户会以为点一下咖啡色才是切换，其实颜色早就是咖啡色了
        coffee_idx = [t["value"] for t in themes].index("coffee")
        coffee_label = labels[coffee_idx] if coffee_idx < len(labels) else None
        active_now = [o for o in opts if o.get("active")]
        active_now_label = active_now[0].get("label") if active_now else None
        checks.append(_ok(
            len(active_now) == 1 and active_now_label == coffee_label,
            "本地无选择时，设置页选中态跟随服务端（%s）" % coffee_label,
            "本地无选择、服务端是咖啡色色，但设置页选中的是 %s —— "
            "设置页读的是本地偏好而不是实际生效的主题"
            % (active_now_label if active_now_label else "（无选中项）")))

        # 色点的颜色应当与服务端公布的 accent 一致。
        # 这是"服务端数据 → inline style → 实际像素"这条链的前半段
        for i, item in enumerate(themes):
            if i < len(opts):
                dot = _rgb_to_hex(opts[i].get("dot"))
                checks.append(_ok(
                    dot == item["accent"].lower(), "",
                    "第 %d 个色点是 %s，服务端公布 %s —— 设置页的色点会误导用户"
                    % (i + 1, dot, item["accent"])))

        # 逐套真点一遍。want_label 按索引取 —— 语言无关，
        # 英文模式下按中文标签找会全部落空
        for i, item in enumerate(themes):
            value = item["value"]
            want_accent = item["accent"].lower()
            want_label = labels[i] if i < len(labels) else None

            st = br.drive(env["driver"], srv.port, action="switch-theme", seed=value)
            attr = st.get("themeAttr")
            accent = _rgb_to_hex(st.get("accent"))
            btn = _rgb_to_hex(st.get("primaryButtonBg"))
            stored = st.get("themeStored")
            after = srv.server_theme()
            o = st.get("themeOptions") or []
            active = [x for x in o if x.get("active")]
            # 先取出来再拼消息：_ok(cond, good, bad) 的两个消息是**先求值再传参**的，
            # 所以 bad 里直接写 active[0] 会在"选中项不是 1 个"时抛 IndexError ——
            # 而那恰恰是最该报出原因的时候。踩过：注入"服务端 accent 抄错"时
            # s7 抛了 IndexError，把真正的原因盖掉了
            active_label = active[0].get("label") if active else None

            # 主按钮的渲染色是"颜色真的生效了"最直观的证据。令牌对了但按钮没跟上
            # （比如某条规则把背景写死了）只有这条能发现。
            # 期望值按主题查表：竹青的按钮单独覆盖成更浅的绿，其余三条仍等于 accent
            want_btn = PRIMARY_BUTTON_OVERRIDE.get(value, want_accent)

            # 默认按钮（无 type 的那些）不能被一起染绿。只覆盖实心填充，
            # 白底的默认按钮该保持白底。取不到时跳过（不拿"没找到"当失败）
            dbtn = _rgb_to_hex(st.get("defaultButtonBg")) if st.get("defaultButtonBg") else None
            # 语言切换的选中态与主按钮是同一个令牌的两个出口，必须同色。
            # 【为什么单列一条】一开始只覆盖了 .el-button--primary，主按钮变浅了
            # 而这里还是 #08965e —— 一屏两种绿。这条就是为那次漏改加的
            rbtn = _rgb_to_hex(st.get("radioButtonBg")) if st.get("radioButtonBg") else None

            details.append(
                "点 %-8s → 属性=%-8s accent=%-9s 主按钮=%-9s 本地=%-8s 服务端=%-8s 选中=%d 个" % (
                    value, attr, accent, btn, stored, after, len(active)))

            checks.append(_ok(
                accent == want_accent, "",
                "点 %s 后 --accent 是 %s，服务端公布 %s —— 颜色对不上"
                % (value, accent, want_accent)))
            if dbtn is not None:
                checks.append(_ok(
                    dbtn != btn, "",
                    "点 %s 后默认按钮（无 type）的底色是 %s，与实心填充 %s 同色 —— "
                    "覆盖写太宽了，把不该染的按钮也染了" % (value, dbtn, btn)))
            if rbtn is not None:
                # 与主按钮直接比，不经过期望值 —— 这两处是同一个令牌的两个出口，
                # 谁被单独改坏都能发现，且与色值取多少无关
                checks.append(_ok(
                    rbtn == btn, "",
                    "点 %s 后语言切换选中态的底色是 %s，主按钮是 %s —— "
                    "两者是同一个令牌的出口，应该同色（只改了其中一个组件）"
                    % (value, rbtn, btn)))
            if value in FILL_SHOULD_BE_LIGHTER:
                # 意图断言：填充必须**严格浅于** --accent。
                # 这条不读 tokens.css 的当前值，所以覆盖整条失效（令牌名拼错、
                # 规则被删）时不会跟着一起瞎 —— 那种情况下填充会退回等于 --accent
                checks.append(_ok(
                    btn != accent and _lum(btn) > _lum(accent), "",
                    "点 %s 后实心填充是 %s，--accent 是 %s —— 填充没有比强调色浅，"
                    "覆盖多半整条失效了（令牌名拼错 / 规则被删）" % (value, btn, accent)))
                declared = PRIMARY_BUTTON_OVERRIDE.get(value)
                if declared:
                    checks.append(_ok(
                        btn == declared, "",
                        "点 %s 后实心填充是 %s，tokens.css 里声明的是 %s —— "
                        "声明了但没落到浏览器上" % (value, btn, declared)))
                # 被 Element 当成文字色的那两处必须仍是 --accent。
                # 覆盖 --el-color-primary 会连带把这两处弄浅，所以 tokens.css
                # 把它们钉了回去；这条证明钉回规则真的落到了浏览器上
                # （探针元素由 harness 现造 —— 页面上本来没有这两个元素）
                at = st.get("accentTextColor") or {}
                for key, where in (("tab", "标签页选中标题"),
                                   ("checkbox", "勾选框选中那行小字")):
                    raw = at.get(key)
                    if raw is None:
                        continue
                    got_text = _rgb_to_hex(raw)
                    checks.append(_ok(
                        got_text == want_accent, "",
                        "点 %s 后%s是 %s，期望 %s（--accent）—— "
                        "实心填充的覆盖把文字色也带浅了（钉回规则没生效）"
                        % (value, where, got_text, want_accent)))
            else:
                # 没有覆盖的主题必须严格等于自己的 accent：
                # 哪天有人把覆盖写成全局的（不加 [data-theme] 限定），这三条会红
                checks.append(_ok(
                    btn == want_accent, "",
                    "点 %s 后主按钮底色是 %s，期望 %s —— 令牌生效了但按钮没跟上"
                    % (value, btn, want_accent)))
            # 点击必须落到 localStorage 与后端两处。
            # 【为什么放在这里而不是 s6】s6 走的是"预置 localStorage 再刷新"，
            # 那条路径由 reconcileTheme 推给后端，**根本不经过 setTheme**。
            # 实测过：把 setTheme 里的 pushTheme 与 persist 都去掉，s6 依然全绿。
            # 用户真实点按钮走的是 setTheme，所以这两条只能在这里卡
            checks.append(_ok(
                stored == value, "",
                "点 %s 后 localStorage 是 %r —— 本地选择没被记住，刷新就丢"
                % (value, stored)))
            checks.append(_ok(
                after == value, "",
                "点 %s 后服务端主题是 %r —— 没推给后端，桌面托盘与控制窗口不会跟着变"
                % (value, after)))
            checks.append(_ok(
                len(active) == 1, "",
                "点 %s 后有 %d 个选项处于选中态，应当恰好 1 个" % (value, len(active))))
            if len(active) == 1 and want_label is not None:
                checks.append(_ok(
                    active_label == want_label, "",
                    "点 %s 后选中的是 %r，期望 %r —— 点错了位置或选中态没跟着走"
                    % (value, active_label, want_label)))
            # 默认主题不写 data-theme 属性；其余主题要写
            if value == themes[0]["value"]:
                checks.append(_ok(
                    attr is None, "",
                    "默认主题下 data-theme 是 %r，应当是 null（默认主题不写属性）" % attr))
            else:
                checks.append(_ok(
                    attr == value, "",
                    "点 %s 后 data-theme 是 %r" % (value, attr)))

        # 【踩过】这里原本写的是 c[2]，而 _ok 返回的是二元组 (cond, message)。
        # s7 一直是绿的所以没暴露 —— 一旦真的失败，场景会在报错时抛
        # IndexError，把"哪里不对"换成一个看不懂的堆栈。
        # 一条只在失败时才崩的断言，比没有断言更难查
        failed = [c[1] for c in checks if not c[0]]
        return (not failed), "逐套点击：计算色与服务端公布色一致，选中态唯一", details + failed

    return _run(env, "zh", body, "s7", theme="coffee")
