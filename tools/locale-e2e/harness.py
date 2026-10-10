# -*- coding: utf-8 -*-
"""语言一致性回归：可复用的驱动层。

【这个目录为什么存在】

网页/服务端两份语言设置的对齐规则（`reconcileLocale`）以及"切语言要重取类型元数据"
这类行为，**依赖三样普通单测给不了的东西**：

  · localStorage 的真实状态（要不要写、写没写）
  · 真实的启动时序（reconcile 在挂载之前跑完）
  · 真实的 HTTP 往返与 Vue 的响应式刷新

用 jsdom mock 掉，测的就是 mock 而不是产品。本项目已经踩过一次
「夹具不能区分实现 → 假绿」的坑（编号 25），所以这里用**真浏览器**。

【怎么跑】见同目录 README.md。一句话：

    python tools/locale-e2e/run_all.py

【依赖】JDK 17、Node 18+（用其内置 WebSocket）、Edge 或 Chrome。
不依赖第三方 Python 包。
"""
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# ---------------------------------------------------------------- 工具位置
# 全部可用环境变量覆盖，便于换机器 / 接 CI。
# 默认值是本机实测可用的位置（Windows 开发机）。
JAVA = os.environ.get("TINYQUERY_JAVA", r"D:\JDK\jdk-17\bin\java.exe")
NODE = os.environ.get("TINYQUERY_NODE", r"D:\Node.js\node.exe")
ROOT = Path(os.environ.get("TINYQUERY_ROOT", Path(__file__).resolve().parents[2]))

# 浏览器候选：优先 Edge，其次 Chrome。找到一个可用的即可
BROWSER_CANDIDATES = [
    os.environ.get("TINYQUERY_BROWSER", ""),
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
]

JAR = ROOT / "target" / "tinyquery-1.0.0.jar"

# 工作目录。默认放系统临时目录，退出时清理
WORK = Path(os.environ.get("TINYQUERY_E2E_WORK", Path(os.environ.get("TEMP", "/tmp")) / "tq-locale-e2e"))


def free_port():
    """要一个当前空闲的端口。

    <p>【为什么不用固定端口】本项目一直用 180xx 段，但**固定端口会被残留页面污染**：
    实测 18063 被一个早先会话留下的浏览器标签页占着，那个页面每几秒重放一次
    {@code POST /api/preferences/locale {"locale":"en"}}，把新起的服务端语言改掉。
    症状看起来像"服务端自己把 zh 变成了 en"，排查了很久。</p>

    <p>动态分配 + 每次运行都不同，就不会撞上任何遗留页面。</p>

    <p>绑 0 让操作系统分配，随即释放 —— 拿到的端口在本次运行中实际上不会被别人抢占。
    真正的占用检测交给 {@link #wait_up}：起不来就是起不来，会明确报错。</p>
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def free_ports(n):
    """一次要 n 个互不相同的端口。

    <p>一次要完再释放，避免"释放一个、要下一个"时拿到刚释放的同一个端口。</p>
    """
    socks = []
    ports = []
    try:
        for _ in range(n):
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.bind(("127.0.0.1", 0))
            socks.append(s)
            ports.append(s.getsockname()[1])
    finally:
        for s in socks:
            s.close()
    return ports


class HarnessError(RuntimeError):
    """环境不满足（缺 jar、缺浏览器等）—— 与"产品有缺陷"区分开。"""


def find_browser():
    for path in BROWSER_CANDIDATES:
        if path and Path(path).exists():
            return path
    raise HarnessError(
        "找不到浏览器。设 TINYQUERY_BROWSER 指向 msedge.exe / chrome.exe，"
        "或安装 Edge / Chrome。")


def require_jar():
    if not JAR.exists():
        raise HarnessError(
            "找不到 %s。先打包：mvn package -DskipTests" % JAR)


# ---------------------------------------------------------------- CDP 驱动
# 一段 Node 脚本，用 CDP 驱动浏览器。写成文件而不是内联字符串，
# 便于阅读与单独调试。Node 18+ 自带 WebSocket，不需要装 ws。
CDP_DRIVER = r"""
// 用 CDP 驱动浏览器完成一个场景：
//   1. 打开应用页面
//   2. （可选）预置/清除 localStorage
//   3. （可选）重新加载，让启动逻辑在预置状态下再跑一遍
//   4. （可选）在页面里切换语言（点界面上的语言按钮）
//   5. 读回结果
//
// 参数（argv）：cdpPort appPort action seed
//   action: "load"     只加载并读结果
//           "seed"     加载后用 seed 写 localStorage，reload 再读
//           "switch"   加载后切到 seed 指定的语言，等刷新完成再读
//           "evaluate" 在**当前页面**上求 seed 里的 JS 表达式（不导航）。
//                      给一次性探查用，例如量计算样式；不属于固定场景
const http = require('http');

const CDP_PORT = parseInt(process.argv[2], 10);
const APP_PORT = process.argv[3];
const ACTION = process.argv[4] || 'load';
const SEED = process.argv[5] || '';

function httpJson(path) {
  return new Promise((res, rej) => {
    http.get({ host: '127.0.0.1', port: CDP_PORT, path }, r => {
      let d = ''; r.on('data', c => d += c); r.on('end', () => res(JSON.parse(d)));
    }).on('error', rej);
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

// 读页面状态。这是所有场景共用的"观测窗口"。
//
// 刻意多读几样东西：只读 documentElement.lang 会漏掉"外壳变了、
// 但后端下发的类型元数据没跟着变"这种半新半旧的状态 ——
// 那正是编号 26 那个真实缺陷的形态。
const READ_EXPR = `JSON.stringify({
  lang: document.documentElement.lang,
  stored: localStorage.getItem('tinyquery.locale'),
  bodyHasZh: /数据源|查询|结果|设置/.test(document.body.innerText),
  bodyHasEn: /Data Source|Query|Result|Settings/.test(document.body.innerText),
  // 设置页打开后才有；未打开时为空数组
  buildId: (document.querySelector('.build-id') || {}).textContent || '',
  // 类型下拉里的选项文字：这是【后端下发】的，切语言最容易漏刷新的就是它
  typeOptions: Array.from(document.querySelectorAll('.el-select-dropdown__item'))
                    .map(e => e.textContent.trim()),
  // 面板标题。外壳文案与后端文案混在一起的典型区域
  panelTitles: Array.from(document.querySelectorAll('.panel-title'))
                    .map(e => e.textContent.trim()),
  datasourceGroups: Array.from(document.querySelectorAll('.ds-group-title, .group-title'))
                    .map(e => e.textContent.trim()),
})`;

(async () => {
  const list = await httpJson('/json/list');
  const page = list.find(t => t.type === 'page');
  if (!page) { console.log('ERR no page target'); process.exit(3); }

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 0; const pend = new Map();
  ws.onmessage = e => {
    const m = JSON.parse(e.data);
    if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); }
  };
  const send = (method, params) => new Promise(r => {
    const i = ++id; pend.set(i, r);
    ws.send(JSON.stringify({ id: i, method, params }));
  });
  await new Promise(r => ws.onopen = r);

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Page.navigate', { url: `http://127.0.0.1:${APP_PORT}/` });
  await sleep(4000);

  const evalJs = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
    return r.result && r.result.result && r.result.result.value;
  };

  if (ACTION === 'seed') {
    const w = SEED === '__CLEAR__'
      ? await evalJs("localStorage.removeItem('tinyquery.locale'); String(localStorage.getItem('tinyquery.locale'))")
      : await evalJs(`localStorage.setItem('tinyquery.locale', ${JSON.stringify(SEED)}); localStorage.getItem('tinyquery.locale')`);
    console.log('SEED ' + JSON.stringify(w));
    await send('Page.reload', { ignoreCache: true });
    await sleep(4000);
  }

  if (ACTION === 'switch') {
    // 走到设置页，点语言单选按钮。
    // 用真实点击而不是直接改 i18n：要验证的正是"点了之后"的连带效果
    await evalJs(`
      (() => {
        const tabs = Array.from(document.querySelectorAll('.el-menu-item, .nav-item, button, a'));
        const t = tabs.find(e => /设置|Settings/.test(e.textContent));
        if (t) t.click();
        return true;
      })()
    `);
    await sleep(1200);
    const clicked = await evalJs(`
      (() => {
        const labels = Array.from(document.querySelectorAll('.el-radio-button'));
        const want = ${JSON.stringify(SEED)};
        const btn = labels.find(e => {
          const txt = e.textContent.trim();
          if (want === 'en') return txt === 'English';
          return txt === '中文';
        });
        if (!btn) return 'NOT_FOUND:' + labels.map(e => e.textContent.trim()).join('|');
        btn.querySelector('input,.el-radio-button__inner')?.click();
        btn.click();
        return 'CLICKED';
      })()
    `);
    console.log('SWITCH ' + clicked);
    await sleep(3000);
  }

  // 在当前页面上求一个任意表达式，不做导航。
  // 用于一次性探查（例如量计算样式），不参与固定场景
  if (ACTION === 'evaluate') {
    // 表达式整段用 JSON.stringify 传进来，避免与外层模板字符串的转义打架。
    // 【踩过】写成 evalJs(`JSON.stringify((() => (${SEED}))())`) 时，
    // 表达式里的反引号与 ${} 会和外层模板串冲突，得到 undefined
    const expr = JSON.parse(SEED);
    let value = null;
    try {
      const raw = await evalJs('(() => { try { return JSON.stringify(' + expr + '); }'
        + ' catch (e) { return JSON.stringify({__err: String(e && e.message || e)}); } })()');
      const parsed = JSON.parse(raw);
      if (parsed && parsed.__err) {
        throw new Error(parsed.__err);
      }
      value = parsed;
    } catch (e) {
      console.log('RESULT=' + JSON.stringify({ error: String(e && e.message || e) }));
      ws.close();
      process.exit(0);
    }
    console.log('RESULT=' + JSON.stringify({ value }));
    ws.close();
    process.exit(0);
  }

  const out = await evalJs(READ_EXPR);
  console.log('RESULT=' + out);
  ws.close();
  process.exit(0);
})().catch(e => { console.log('ERR ' + e.message); process.exit(4); });
"""


def _kill_by_profile(profile_dir):
    """杀掉所有以 {@code profile_dir} 为数据目录的浏览器进程（含子进程）。

    <p>只按 profile 路径匹配，不按进程名杀 —— 后者会把用户自己开着的
    Edge / Chrome 一并关掉，那是个不可接受的副作用。</p>

    <p>Windows 用 WMIC/PowerShell 查命令行；其它平台用 {@code pkill -f}。
    都失败时静默放行：清理失败不该让测试结果变成"失败"，
    但会在下次运行时由 {@code fresh_workdir} 清掉工作目录。</p>
    """
    target = str(profile_dir)
    try:
        if os.name == "nt":
            # WMIC 在新系统上已弃用，改用 PowerShell 的 CIM 查询。
            # 顺带数一下杀了几个，"一个都没杀到"与"杀了三个"含义不同
            ps = (
                "$p = @(Get-CimInstance Win32_Process | "
                "Where-Object { $_.CommandLine -and $_.CommandLine.Contains('%s') }); "
                "$p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force "
                "-ErrorAction SilentlyContinue }; $p.Count" % target.replace("'", "''")
            )
            r = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                               capture_output=True, text=True, timeout=60)
            try:
                return int((r.stdout or "0").strip().splitlines()[-1])
            except (ValueError, IndexError):
                return 0
        else:
            r = subprocess.run(["pkill", "-f", target], capture_output=True, timeout=30)
            # pkill 成功返回 0，没匹配到返回 1；数不出具体个数，用真假代替
            return 1 if r.returncode == 0 else 0
    except Exception:
        return 0


class AppServer:
    """一个跑在指定端口上的 TinyQuery 实例，带独立的 TINYQUERY_HOME。

    独立 HOME 是为了让 `locale.txt`（服务端语言）可控 —— 场景要靠它
    分别构造"服务端是中文/英文"两种起点。
    """

    def __init__(self, port, locale=None, tag="srv"):
        self.port = port
        self.home = WORK / ("home-%s-%d" % (tag, port))
        shutil.rmtree(self.home, ignore_errors=True)
        self.home.mkdir(parents=True, exist_ok=True)
        if locale:
            (self.home / "locale.txt").write_text(locale, encoding="utf-8")

        env = dict(os.environ)
        env["TINYQUERY_HOME"] = str(self.home)
        # 本机若设了 JAVA_TOOL_OPTIONS，会在每次 java 启动时打印干扰信息，
        # 甚至在部分环境让进程挂住。这里显式清掉
        env["JAVA_TOOL_OPTIONS"] = ""
        # 环境变量 TINYQUERY_LOCALE 的优先级【高于】locale.txt（见 DesktopLocale.resolve），
        # 本机若设了它，场景通过 locale.txt 指定的语言就会被无视。
        # 移除它，保证"服务端语言"只由 locale.txt 决定
        env.pop("TINYQUERY_LOCALE", None)

        self.proc = subprocess.Popen(
            [JAVA, "-Dfile.encoding=UTF-8", "-jar", str(JAR),
             "--server.port=%d" % port, "--server.address=127.0.0.1",
             "--spring.main.banner-mode=off"],
            env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def wait_up(self, limit=60):
        for _ in range(limit):
            time.sleep(1)
            try:
                urllib.request.urlopen("http://127.0.0.1:%d/" % self.port, timeout=3)
                return True
            except Exception:
                continue
        return False

    def assert_locale_settled(self, expected):
        """确认服务端语言确实停在 {@code expected}，而不是"曾经是"。

        <p>【为什么要单独确认】踩过一次：上一轮测试残留的浏览器进程一直对同一端口
        重放 {@code POST /api/preferences/locale}，服务端启动后一两秒内语言就被
        改掉了。于是"刚起来时读到 zh、稍后读到 en"，看起来像服务端自己变了语言。</p>

        <p>所以任何依赖服务端语言的场景，**在使用前**都要先确认它还是预期值。
        不满足就明确报错，而不是让后续断言给出误导性的结果。</p>
        """
        for _ in range(10):
            actual = self.server_locale()
            if actual == expected:
                # 再等一拍，确认不是"刚好在这一刻是对的"
                time.sleep(0.6)
                if self.server_locale() == expected:
                    return
            time.sleep(0.5)
        raise HarnessError(
            "服务端语言不是预期的 %r（当前 %r）。"
            "常见原因：有残留的浏览器进程在向后端重放切语言请求 —— "
            "先把 msedge/chrome 都关掉再跑。" % (expected, self.server_locale()))

    def _token(self):
        idx = urllib.request.urlopen(
            "http://127.0.0.1:%d/" % self.port, timeout=10).read().decode("utf-8")
        return re.search(r"__TINYQUERY_TOKEN__='([^']+)'", idx).group(1)

    def server_locale(self):
        req = urllib.request.Request(
            "http://127.0.0.1:%d/api/preferences/locale" % self.port,
            headers={"X-TinyQuery-Token": self._token()})
        body = json.loads(urllib.request.urlopen(req, timeout=10).read().decode("utf-8"))
        return body["data"]["locale"]

    def set_server_locale(self, locale):
        req = urllib.request.Request(
            "http://127.0.0.1:%d/api/preferences/locale" % self.port,
            data=json.dumps({"locale": locale}).encode("utf-8"),
            headers={"Content-Type": "application/json",
                     "X-TinyQuery-Token": self._token()})
        urllib.request.urlopen(req, timeout=10).read()

    def stop(self):
        self.proc.terminate()
        try:
            self.proc.wait(timeout=20)
        except Exception:
            self.proc.kill()


def kill_all_stray():
    """开跑之前清掉可能残留的浏览器进程。

    <p>残留进程会带着旧页面继续向后端发请求（包括会改状态的切语言），
    让新场景读到莫名其妙的状态。开跑前清一次，比事后排查便宜得多。</p>

    <p>只清"以本工具的工作目录为 profile"的进程 —— 不动用户自己的浏览器。</p>

    @return 被清掉的进程数（无法计数时返回 0）
    """
    return _kill_by_profile(WORK)


class Browser:
    """一次性的无头浏览器实例，带独立 profile（localStorage 随之隔离）。"""

    def __init__(self, cdp_port, profile_dir, executable):
        self.cdp_port = cdp_port
        self.profile = Path(profile_dir)
        shutil.rmtree(self.profile, ignore_errors=True)
        self.profile.mkdir(parents=True, exist_ok=True)
        self.proc = subprocess.Popen(
            [executable, "--headless=new", "--disable-gpu", "--no-first-run",
             "--no-default-browser-check",
             "--user-data-dir=" + str(self.profile),
             "--remote-debugging-port=%d" % cdp_port,
             "about:blank"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def wait_ready(self, limit=40):
        for _ in range(limit):
            time.sleep(0.5)
            try:
                urllib.request.urlopen(
                    "http://127.0.0.1:%d/json/list" % self.cdp_port, timeout=2)
                return True
            except Exception:
                continue
        return False

    def drive(self, driver_js, app_port, action="load", seed="", timeout=180):
        """跑一次 CDP 驱动，返回解析后的页面状态。"""
        r = subprocess.run(
            [NODE, str(driver_js), str(self.cdp_port), str(app_port), action, seed],
            capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=timeout)
        out = r.stdout or ""
        m = re.search(r"RESULT=(\{.*\})", out)
        if not m:
            raise HarnessError("驱动没返回结果。原始输出：\n" + out + "\n" + (r.stderr or ""))
        state = json.loads(m.group(1))
        state["_raw"] = out.strip()
        return state

    def evaluate(self, driver_js, app_port, expression, timeout=120):
        """在**已打开的页面**上求一个 JS 表达式的值。

        <p>{@code drive} 只跑固定的三个动作（load/seed/switch）。要在页面上量
        计算样式之类的一次性探查，用这个 —— 它不重新导航，就在当前页面求值。</p>

        <p>用途举例：验证样式令牌迁移后浏览器算出来的颜色确实没变
        （构建成功、单测通过都不能说明颜色没变）。</p>

        @param expression 一段 JS 表达式，结果需可 JSON 序列化
        """
        # 表达式经 JSON 编码后放进 seed 参数位，由驱动再解码 ——
        # 这样里面的引号、反引号、${} 都不会与外层转义打架
        r = subprocess.run(
            [NODE, str(driver_js), str(self.cdp_port), str(app_port),
             "evaluate", json.dumps(expression)],
            capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=timeout)
        out = r.stdout or ""
        m = re.search(r"RESULT=(\{.*\})", out)
        if not m:
            raise HarnessError("求值失败。原始输出：\n" + out + "\n" + (r.stderr or ""))
        state = json.loads(m.group(1))
        if "error" in state:
            raise HarnessError("页面里的 JS 报错：" + str(state["error"]))
        return state.get("value")

    def stop(self):
        """关掉浏览器。

        【为什么要连子进程一起杀】
        Edge / Chrome 是多进程的：主进程下面挂着 renderer、gpu、utility 等一堆子进程。
        只 terminate 主进程的话，**子进程会活下来**，而且里面的页面还在跑 ——
        带着应用页面的残留标签页会继续向后端发请求，包括"切语言"这种会改状态的。

        这不是理论：实测时上一个场景残留的浏览器一直对 18063 端口重放
        POST /api/preferences/locale {"locale":"en"}，把后续场景的服务端语言
        偷偷改掉，症状看起来像"服务端自己把 zh 变成了 en"。
        排查花了很久，教训是**测试工具必须自己干净**，否则会制造出假的缺陷。
        """
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except Exception:
                pass

        # 杀掉以本场景 profile 为数据目录的所有进程。
        # 用 profile 路径匹配而不是按名字杀 msedge —— 后者会把用户自己
        # 开着的浏览器一起关掉
        _kill_by_profile(self.profile)


def write_driver():
    WORK.mkdir(parents=True, exist_ok=True)
    path = WORK / "cdp_driver.js"
    path.write_text(CDP_DRIVER, encoding="utf-8")
    return path


def fresh_workdir():
    shutil.rmtree(WORK, ignore_errors=True)
    WORK.mkdir(parents=True, exist_ok=True)
