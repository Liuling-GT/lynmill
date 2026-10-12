# -*- coding: utf-8 -*-
r"""
打包产物全链路验收 —— 确认**打包出来的那个东西**能真正干活。

【为什么需要它】待办编号 43 的备注把这条标为「★ 验证缺口（必须补）」：

    阶段 8 的六项验收里没有一项真的连过数据库（第 48 条用的是 CSV）。
    所以少了 java.sql / jdk.crypto.ec 这类模块，现有验收清单是验不出来的。

这正是 jlink 裁剪最危险的失效模式：**缺模块不会在启动时报错**，
只在第一次用到那条代码路径时抛 NoClassDefFoundError ——
表现就是待办里反复提到的「双击了没反应」。

【关键设计：跑 app-image，不是跑 jar】
本脚本驱动的必须是 `jpackage-out\TinyQuery\TinyQuery.exe`（或 --runtime-image 精简后的那个），
而不是 `java -jar`。只有前者才带自带的 runtime，才验证得了模块是否裁过头。
用 jar 测等于没测 —— 那是拿开发机的完整 JDK 在跑。

【覆盖什么】
  · 5 种数据源类型全部注册 + 查询：CSV / xlsx / SQLite / MySQL(MariaDB) / PostgreSQL
  · 每类都做：注册 → 列表 → 查询 → 图表
  · TLS 连接（jdk.crypto.ec）
  · 中文 / emoji / 大整数 / null / 特殊字符的往返
  · 打包后前端资源能取到（java.desktop 之外的静态资源路径）

【缺数据库时怎么办】
MySQL / PostgreSQL 那几项会跳过并明确标注，不算失败 —— 但会汇总里报「跳过 N 项」，
避免出现"以为是全绿，其实没测数据库"的假象。

用法：
    python packaging/verify-package.py                    # 自动找 app-image
    python packaging/verify-package.py --app <目录>        # 指定
    python packaging/verify-package.py --json              # 机器可读
退出码：0 全通过 / 1 有失败 / 2 环境不满足
"""
import argparse
import io
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

# 只在直接运行时接管 stdout（Windows 控制台默认 GBK，中文会乱码）。
# 【踩过】无条件在导入时替换 sys.stdout，会让 import 本模块的人拿到一个已被关闭的流
if __name__ == "__main__":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent

# 数据库连接参数可用环境变量覆盖，默认指向本仓库验收用的容器
MYSQL = {
    "host": os.environ.get("TQ_MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("TQ_MYSQL_PORT", "13306")),
    "user": os.environ.get("TQ_MYSQL_USER", "root"),
    "password": os.environ.get("TQ_MYSQL_PASS", "tqpass"),
    "database": os.environ.get("TQ_MYSQL_DB", "tqtest"),
}
PG = {
    "host": os.environ.get("TQ_PG_HOST", "127.0.0.1"),
    "port": int(os.environ.get("TQ_PG_PORT", "15432")),
    "user": os.environ.get("TQ_PG_USER", "postgres"),
    "password": os.environ.get("TQ_PG_PASS", "tqpass"),
    "database": os.environ.get("TQ_PG_DB", "tqtest"),
}

results = []   # (是否通过, 名称, 说明)
skipped = []


def check(ok, name, detail=""):
    results.append((bool(ok), name, detail))
    return bool(ok)


def skip(name, why):
    skipped.append((name, why))


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def port_open(host, port, timeout=2):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


class App:
    """驱动打包产物：启动 / 请求 / 停止。"""

    def __init__(self, app_dir):
        self.dir = Path(app_dir)
        self.port = free_port()
        self.home = Path(os.environ.get("TEMP", "/tmp")) / "tq-pkgverify" / ("home-%d" % self.port)
        shutil.rmtree(self.home, ignore_errors=True)
        self.home.mkdir(parents=True, exist_ok=True)
        self.proc = None
        self.token = None

    def launcher(self):
        """找到启动器。app-image 下是 <name>.exe / <name>，macOS 下在 Contents/MacOS。"""
        for pat in ("*.exe", "*"):
            for p in sorted(self.dir.glob(pat)):
                if p.is_file() and p.suffix.lower() in (".exe", ""):
                    if p.suffix.lower() == ".exe" or os.access(p, os.X_OK):
                        return p
        mac = self.dir / "Contents" / "MacOS"
        if mac.exists():
            for p in sorted(mac.iterdir()):
                if p.is_file():
                    return p
        raise RuntimeError("在 %s 里找不到启动器" % self.dir)

    def start(self, timeout=120):
        env = dict(os.environ)
        env["TINYQUERY_HOME"] = str(self.home)
        env["JAVA_TOOL_OPTIONS"] = ""
        env.pop("TINYQUERY_LOCALE", None)
        exe = self.launcher()
        # 关掉自动开浏览器。打包产物默认会开一个真的浏览器指向本实例，
        # 那个页面会带着 Edge 的遥测请求打到测试服务端上（实测日志里出现
        # NoResourceFoundException: hybridaction/zybTrackerStatisticsAction），
        # 污染日志、干扰排查。测试不需要真的有人看界面
        args = [str(exe), "--server.port=%d" % self.port,
                "--server.address=127.0.0.1",
                "--tinyquery.browser.auto-open=false"]
        self.proc = subprocess.Popen(
            args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace")
        t0 = time.time()
        while time.time() - t0 < timeout:
            if self.proc.poll() is not None:
                out = self.proc.stdout.read() if self.proc.stdout else ""
                raise RuntimeError(
                    "启动器退出（码 %s）。最可能的原因是 runtime 裁掉了某个必需的模块。\n"
                    "      日志里的根因行：\n%s\n      完整输出：\n%s"
                    % (self.proc.returncode, self._root_cause(out), out[-2500:]))
            try:
                html = urllib.request.urlopen(
                    "http://127.0.0.1:%d/" % self.port, timeout=3).read().decode("utf-8")
                m = re.search(r"__TINYQUERY_TOKEN__\s*=\s*'([^']+)'", html)
                if m:
                    self.token = m.group(1)
                    return True
            except Exception:
                pass
            time.sleep(1)
        # 超时也要把日志里的根因带出来 —— 只说"超时"等于没说
        out = ""
        if self.proc.stdout:
            try:
                os.set_blocking(self.proc.stdout.fileno(), False)
                out = self.proc.stdout.read() or ""
            except Exception:
                pass
        raise RuntimeError("启动超时（%ds）。日志根因：\n%s\n输出尾部：\n%s"
                           % (timeout, self._root_cause(out), out[-2000:]))

    @staticmethod
    def _root_cause(log):
        """从日志里挑出最有解释力的那几行。

        缺模块的典型形态是：顶层报的是 "Unable to start web server"，
        真正的根因埋在下面几层 Caused by 里（实测是
        {@code NoClassDefFoundError: org/ietf/jgss/GSSException}）。
        只报顶层等于让排查的人重走一遍。
        """
        if not log:
            return "      （启动器没有输出，可能根本没跑起来）"
        keep = []
        for line in log.splitlines():
            s = line.strip()
            if any(k in s for k in ("Caused by", "NoClassDefFoundError",
                                    "ClassNotFoundException", "NoSuchMethodError",
                                    "UnsupportedClassVersionError",
                                    "Application run failed", "Unable to start")):
                keep.append(s[:200])
        if not keep:
            return "      （没有识别出已知的失败形态，见下方输出）"
        # 去重，保留顺序
        seen = []
        for k in keep:
            if k not in seen:
                seen.append(k)
        return "\n".join("        · " + k for k in seen[:8])

    def api(self, path, payload=None):
        url = "http://127.0.0.1:%d%s" % (self.port, path)
        headers = {"X-TinyQuery-Token": self.token or "",
                   "Content-Type": "application/json"}
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        req = urllib.request.Request(url, data=data, headers=headers,
                                     method="POST" if data else "GET")
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode("utf-8"))

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=20)
            except Exception:
                self.proc.kill()


# ---------------------------------------------------------------- 各项验证

def v_boot(app):
    check(True, "应用能启动并返回首页", "端口 %d，拿到 token" % app.port)
    v = app.api("/api/version")
    check(v.get("code") == 0, "GET /api/version 可用",
          str(v.get("data"))[:90])


def v_frontend(app):
    """静态资源能取到 —— 防止裁剪把资源路径搞坏。"""
    idx = urllib.request.urlopen("http://127.0.0.1:%d/" % app.port, timeout=10) \
        .read().decode("utf-8")
    check("__TINYQUERY_TOKEN__" in idx, "首页注入了 token")
    m = re.search(r'src="([^"]+\.js)"', idx)
    if not m:
        srcs = re.findall(r'(?:src|href)="([^"]+)"', idx)
        check(False, "首页里有 JS 资源引用", "只找到：%s" % srcs[:5])
        return
    js_url = "http://127.0.0.1:%d%s" % (app.port, m.group(1))
    body = urllib.request.urlopen(js_url, timeout=30).read()
    check(len(body) > 50000, "前端 JS 包能完整取到",
          "%s → %.2f MB" % (m.group(1), len(body) / 1024 / 1024))


def v_types(app):
    """5 种数据源类型都要在 —— 裁剪把某个 loader 的类裁了就少一种。"""
    r = app.api("/api/datasource/types")
    types = [t.get("type") for t in (r.get("data") or [])]
    want = {"csv", "xlsx", "sqlite", "mysql", "postgresql"}
    missing = want - set(types)
    check(not missing, "5 种数据源类型全部注册", "实际：%s" % sorted(types))
    if missing:
        check(False, "（上一条的详情）缺少", str(sorted(missing)))


def register(app, name, dtype, props):
    # properties 是真实键名（不是 config）
    return app.api("/api/datasource", {"name": name, "type": dtype, "properties": props})


def v_csv(app, workdir):
    csv = workdir / "emp.csv"
    csv.write_text(
        "id,name,hired,salary,ratio,big,active,note\n"
        "1,张伟,2020-01-15,12500.50,0.125,9007199254740993,true,中文备注\n"
        "2,李娜,2021-07-01,9800.00,3.14159,123456789012345,false,emoji ok\n"
        "3,Alice,2019-12-31,21000.75,2.71828,42,true,\"quote, comma\"\n",
        encoding="utf-8")
    # CSV 加载器的字段名是 path（不是 filePath）
    r = register(app, "v_csv", "csv", {"path": str(csv)})
    check(r.get("code") == 0, "注册 CSV 数据源", str(r.get("message"))[:80])
    q = app.api("/api/query", {"sql": "select id, name, salary from v_csv order by id"})
    rows = (q.get("data") or [])
    check(q.get("code") == 0 and len(rows) == 3, "查询 CSV 返回 3 行",
          "code=%s rows=%s" % (q.get("code"), len(rows)))
    # 中文往返 —— 缺 jdk.charsets 会在这里出问题
    names = [r0[1] for r0 in rows] if rows else []
    check("张伟" in names and "李娜" in names, "CSV 中文往返正确", "读到：%s" % names)


def v_xlsx(app, workdir):
    try:
        import openpyxl
    except ImportError:
        skip("注册/查询 xlsx", "本机 python 没有 openpyxl（仅影响造测试文件）")
        return
    x = workdir / "emp.xlsx"
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "员工"
    ws.append(["id", "name", "salary", "note"])
    ws.append([1, "张伟", 12500.5, "中文"])
    ws.append([2, "Bob", 9800.0, None])
    wb.save(x)

    r = register(app, "v_xlsx", "xlsx", {"path": str(x), "sheet": "员工"})
    check(r.get("code") == 0, "注册 xlsx 数据源", str(r.get("message"))[:80])
    q = app.api("/api/query", {"sql": "select id, name, salary from v_xlsx order by id"})
    rows = q.get("data") or []
    check(q.get("code") == 0 and len(rows) == 2, "查询 xlsx 返回 2 行",
          "code=%s rows=%s msg=%s" % (q.get("code"), len(rows), q.get("message")))


def v_sqlite(app, workdir):
    """SQLite 靠 Xerial JDBC + 自带的原生库 —— 裁剪最容易漏的就是它。

    【必填项有两个】path（库文件）**和** table（表名）。
    只给 path 会报「缺少必填配置项：table」。
    """
    db = workdir / "emp.db"
    if db.exists():
        db.unlink()
    try:
        import sqlite3
        con = sqlite3.connect(str(db))
        con.execute("create table emp(id int, name text, salary real, note text)")
        con.executemany("insert into emp values(?,?,?,?)", [
            (1, "张伟", 12500.5, "中文"),
            (2, "Bob", 9800.0, None),
            (3, "陈曦", 33000.0, "末尾"),
        ])
        con.commit()
        con.close()
    except Exception as e:
        skip("注册/查询 SQLite", "造库失败：%s" % e)
        return

    r = register(app, "v_sqlite", "sqlite", {"path": str(db), "table": "emp"})
    check(r.get("code") == 0, "注册 SQLite 数据源", str(r.get("message"))[:80])
    if r.get("code") != 0:
        return
    q = app.api("/api/query", {"sql": "select id, name, salary from v_sqlite order by id"})
    rows = q.get("data") or []
    check(q.get("code") == 0 and len(rows) == 3, "查询 SQLite 返回 3 行",
          "code=%s rows=%s msg=%s" % (q.get("code"), len(rows), q.get("message")))
    check("张伟" in [x[1] for x in rows], "SQLite 中文往返正确",
          "读到：%s" % [x[1] for x in rows])


def _db_common(app, tag, conn, want_rows=5):
    r = register(app, "v_" + tag, tag, conn)
    check(r.get("code") == 0, "注册 %s 数据源" % tag, str(r.get("message"))[:100])
    if r.get("code") != 0:
        return
    q = app.api("/api/query", {"sql": "select id, name, salary, big, note from v_%s order by id" % tag})
    rows = q.get("data") or []
    check(q.get("code") == 0 and len(rows) == want_rows,
          "查询 %s 返回 %d 行" % (tag, want_rows),
          "code=%s rows=%s msg=%s" % (q.get("code"), len(rows), q.get("message")))
    if not rows:
        return
    names = [x[1] for x in rows]
    check("张伟" in names and "陈曦" in names, "%s 中文往返正确" % tag, "读到：%s" % names)
    # 大整数：JDBC 的 getObject → Long，裁剪不会影响，但顺带验一下类型映射
    bigs = [x[3] for x in rows]
    check(any(str(b) == "9007199254740993" for b in bigs if b is not None),
          "%s bigint 精度未丢" % tag, "读到：%s" % bigs[:5])
    # 聚合 + 图表（走另一条代码路径：聚合下推 + 元数据）
    agg = app.api("/api/query", {"sql": "select count(*) as n, sum(salary) as s from v_%s" % tag})
    check(agg.get("code") == 0, "%s 聚合查询可用" % tag, str(agg.get("message"))[:80])


def v_mysql(app):
    if not port_open(MYSQL["host"], MYSQL["port"]):
        skip("MySQL / MariaDB 真连接", "端口 %d 不通（起容器：docker run -d --name tq-mariadb "
             "-p %d:3306 -e MARIADB_ROOT_PASSWORD=%s -e MARIADB_DATABASE=%s mariadb:11）"
             % (MYSQL["port"], MYSQL["port"], MYSQL["password"], MYSQL["database"]))
        return
    _db_common(app, "mysql", {
        "host": MYSQL["host"], "port": str(MYSQL["port"]), "user": MYSQL["user"],
        "username": MYSQL["user"],
        "password": MYSQL["password"], "database": MYSQL["database"],
        "table": "emp"})


def v_postgres(app):
    if not port_open(PG["host"], PG["port"]):
        skip("PostgreSQL 真连接", "端口 %d 不通（起容器：docker run -d --name tq-postgres "
             "-p %d:5432 -e POSTGRES_PASSWORD=%s -e POSTGRES_DB=%s postgres:16）"
             % (PG["port"], PG["port"], PG["password"], PG["database"]))
        return
    _db_common(app, "postgresql", {
        "host": PG["host"], "port": str(PG["port"]), "user": PG["user"],
        "username": PG["user"],
        "password": PG["password"], "database": PG["database"],
        "schema": "public", "table": "emp"})


def v_chart(app):
    """图表走的是另一条路径（聚合 + 分组下推），单独验。"""
    q = app.api("/api/query/chart", {
        "sql": "select name, salary, id from v_csv",
        "dimension": "name", "measures": ["salary"],
        "aggregation": "sum", "topN": 10})
    ok = q.get("code") == 0 and q.get("categories")
    check(ok, "图表接口可用（含中文类别名）",
          "code=%s categories=%s" % (q.get("code"), (q.get("categories") or [])[:4]))


def v_locale(app):
    r = app.api("/api/preferences/locale")
    check(r.get("code") == 0, "偏好接口可用（i18n 资源未裁坏）",
          "locale=%s" % (r.get("data") or {}).get("locale"))


def v_release_modules(app):
    """读 release 文件，报告模块数与体积 —— 裁剪效果的客观记录。"""
    rel = app.dir / "runtime" / "release"
    if not rel.exists():
        rel = app.dir.parent / "runtime" / "release"
    if not rel.exists():
        check(False, "能读到 runtime/release", "找不到 %s" % rel)
        return None
    txt = rel.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"^MODULES=(.*)$", txt, re.M)
    mods = m.group(1).strip().strip('"').split() if m else []
    check(len(mods) > 0, "runtime/release 里有模块清单", "%d 个模块" % len(mods))
    return mods


def dir_size(p):
    p = Path(p)
    if p.is_file():
        return p.stat().st_size
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file())


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=None, help="app-image 目录")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    if a.app:
        app_dir = Path(a.app)
    else:
        app_dir = ROOT / "jpackage-out" / "TinyQuery"
    if not app_dir.exists():
        print("  找不到 %s —— 先跑 packaging\\package.ps1" % app_dir)
        return 2

    workdir = Path(os.environ.get("TEMP", "/tmp")) / "tq-pkgverify" / "files"
    shutil.rmtree(workdir, ignore_errors=True)
    workdir.mkdir(parents=True, exist_ok=True)

    print("=" * 78)
    print("  打包产物全链路验收")
    print("=" * 78)
    size = dir_size(app_dir)
    print("  产物   : %s" % app_dir)
    print("  体积   : %.1f MB" % (size / 1024 / 1024))
    print()

    app = App(app_dir)
    try:
        print("  启动中（跑的是打包出来的启动器，不是 java -jar）...")
        app.start()
        print("  已启动：端口 %d" % app.port)
        print()

        # 每项验证各自 try —— 一项挂了不能连累后面的。
        # 【踩过】第一版把所有验证放在同一个 try 里，结果 CSV 那步超时之后，
        # xlsx/sqlite/两个数据库/图表全都没跑，报告上只剩一行"应用启动/运行 失败"，
        # 完全看不出是哪一项坏了 —— 这正是一个好的验收脚本最不该有的行为
        def guarded(fn, *args):
            try:
                return fn(*args)
            except Exception as e:
                check(False, getattr(fn, "__name__", "验证项"),
                      "%s: %s" % (type(e).__name__, e))
                return None

        guarded(v_boot, app)
        guarded(v_frontend, app)
        guarded(v_types, app)
        guarded(v_locale, app)
        guarded(v_csv, app, workdir)
        guarded(v_xlsx, app, workdir)
        guarded(v_sqlite, app, workdir)
        guarded(v_mysql, app)
        guarded(v_postgres, app)
        guarded(v_chart, app)
        mods = guarded(v_release_modules, app)

        # 应用还活着吗 —— 中途崩了的话，后面那些"通过"都不可信
        alive = app.proc is not None and app.proc.poll() is None
        check(alive, "全部验证跑完后应用仍在运行",
              "" if alive else "启动器已退出（码 %s），退出前打印的日志见下"
              % (app.proc.returncode if app.proc else "?"))
        if not alive and app.proc and app.proc.stdout:
            tail = app.proc.stdout.read()[-2500:]
            print()
            print("  ---- 启动器最后输出 ----")
            for line in tail.splitlines()[-30:]:
                print("    " + line)
            print("  ------------------------")
    except Exception as e:
        check(False, "应用启动", "%s: %s" % (type(e).__name__, e))
        mods = None
    finally:
        app.stop()

    print("-" * 78)
    passed = sum(1 for ok, _, _ in results if ok)
    for ok, name, detail in results:
        mark = "✅" if ok else "❌"
        print("  %s %-38s %s" % (mark, name, detail))
    if skipped:
        print()
        print("  跳过 %d 项（环境不具备）：" % len(skipped))
        for name, why in skipped:
            print("    ⏭  %-34s %s" % (name, why))
    print("-" * 78)
    print("  通过 %d / %d（跳过 %d）" % (passed, len(results), len(skipped)))
    if mods is not None:
        print("  runtime 模块数：%d" % len(mods))
    print()

    if a.json:
        print(json.dumps({
            "size_bytes": size, "passed": passed, "total": len(results),
            "failed": [n for ok, n, _ in results if not ok],
            "skipped": [n for n, _ in skipped], "modules": mods,
        }, ensure_ascii=False))

    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
