# -*- coding: utf-8 -*-
"""
用 jlink 生成精简 runtime，再用 jpackage 打包。

【为什么不能用 jpackage 的 --jlink-options 减模块】
jpackage 手册写明：非模块化应用（--main-jar）一律使用「默认模块集」，
--add-modules 只能加不能减。所以必须先用 jlink 自己产出 runtime，
再用 --runtime-image 喂给 jpackage。

【模块清单是怎么定出来的 —— 靠实测，不是靠猜】
jdeps --print-module-deps 只给出 4 个（java.base, java.desktop, java.management, java.sql），
但那是因为 Spring Boot 的自动配置是条件化 + 反射的，jdeps 看不见。
用 `java --show-module-resolution` 跑一遍真实应用，看它到底 binds 了哪些模块，
才是可靠依据。实测踩过：只按 jdeps 的结果裁，启动时报

    NoClassDefFoundError: org/ietf/jgss/GSSException

——缺的是 java.security.jgss，jdeps 完全没提。而且顶层错误信息是
"Unable to start web server"，根因埋在几层 Caused by 里，
不解包日志根本看不出是模块问题。
"""
import argparse
import os
import shutil
import stat
import subprocess
import sys
import time
from pathlib import Path

def _find_jdk():
    """定位 JDK。优先 JAVA_HOME，找不到就按平台猜常见位置。

    <p>写死一个 Windows 路径是不行的 —— 这个脚本被三个平台的打包脚本共用
    （package.ps1 / package.sh / package-linux.sh）。</p>
    """
    env = os.environ.get("JAVA_HOME")
    if env and (Path(env) / "bin").is_dir():
        return Path(env)
    candidates = {
        "nt": [r"D:\JDK\jdk-17", r"C:\Program Files\Java\jdk-17",
               r"C:\Program Files\Eclipse Adoptium\jdk-17"],
        "posix": ["/usr/lib/jvm/java-17-openjdk-amd64", "/usr/lib/jvm/java-17-openjdk",
                  "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home",
                  "/opt/homebrew/opt/openjdk@17"],
    }.get("nt" if os.name == "nt" else "posix", [])
    for c in candidates:
        if (Path(c) / "bin").is_dir():
            return Path(c)
    raise SystemExit(
        "  找不到 JDK 17。请设置 JAVA_HOME，或把 JDK 装到常见位置。\n"
        "  （Windows 上还要注意：请用 D:\\JDK\\jdk-17 这类真实路径，"
        "不要用 PowerShell 的 Get-Command java 结果 —— 那是 .exe 不是目录）")


JDK = _find_jdk()
_EXT = ".exe" if os.name == "nt" else ""
JLINK = JDK / "bin" / ("jlink" + _EXT)
JPACKAGE = JDK / "bin" / ("jpackage" + _EXT)

# 模块清单分三段，每段都注明了依据 —— 将来 JDK 升级或加依赖时，
# 能一眼看出该动哪一段，而不是面对一长串看不出所以然的模块名
MODULES = [
    # ---- 第一段：jdeps 能看出来的硬依赖 ----
    "java.base",            # 一切的基础
    "java.desktop",         # 桌面外壳（Swing 托盘 + 控制窗口）。headless 环境也省不掉，
                            # 因为 TinyQueryApplication 显式把 headless 设成 false
    "java.management",      # JMX / 运行时管理
    "java.sql",             # JDBC。MySQL / PostgreSQL / SQLite 三种数据源都要它

    # ---- 第二段：--show-module-resolution 实测到的 binds ----
    # 这些是「条件加载 / ServiceLoader / 反射」才会用到、jdeps 看不见的。
    # 少了任何一个都不会在启动时报「找不到模块」，而是在第一次用到那条路径时
    # 抛 NoClassDefFoundError —— 也就是「双击了没反应」那一类
    "java.security.jgss",   # ★ 实测必需：Tomcat 建默认 Realm 时加载 org.ietf.jgss.GSSException
    "java.naming",          # JNDI。Tomcat 与 JDBC 驱动都会走
    "java.xml",             # XML 解析
    "java.logging",         # java.util.logging。JDBC 驱动大量使用
    "java.datatransfer",    # AWT 剪贴板
    "java.prefs",           # 偏好设置
    "java.instrument",      # javaagent。Spring Boot 的加载器会探测
    "java.net.http",        # HttpClient
    "java.compiler",        # javax.annotation.processing。Lombok / 校验器会碰
    "java.scripting",       # 脚本引擎接口（SQLite 的部分扩展会探测）

    # ---- 第三段：加密 / 编码 / 本地化 ----
    # 裁剪时最容易「看起来没问题、换台机器就炸」的一组
    "jdk.crypto.ec",        # ★ 椭圆曲线 TLS。连 MySQL/PostgreSQL 的 TLS 握手要它。
                            # 没有它时明文连接仍然能通，所以【只有在 TLS 场景才暴露】
    "jdk.crypto.cryptoki",  # PKCS#11（Windows 证书存储 / 智能卡）
    "jdk.crypto.mscapi",    # Windows 原生加密 API
    "jdk.charsets",         # ★ 非 UTF-8 字符集（GBK 等）。读 GBK 编码的 CSV 要它
    "jdk.localedata",       # ★ 本地化数据。中文日期/月份名/数字分组要它。
                            # 默认含全部语言（很大），下面用 --include-locales 收窄
    "jdk.zipfs",            # zip 文件系统。读 xlsx 的 zip 容器要用
    "jdk.unsupported",      # sun.misc.Unsafe。Netty / Spring 大量使用
    "jdk.unsupported.desktop",
    "jdk.naming.dns",       # DNS 解析（JNDI 的 DNS provider）
    "jdk.naming.rmi",
    "java.security.sasl",   # SASL 认证
    "jdk.security.auth",    # JAAS
    "jdk.security.jgss",
]

# 语言数据只保留中英 —— jdk.localedata 默认含全部语言，是个大头
LOCALES = "zh,en"


def run(cmd, **kw):
    print("  $ " + " ".join(str(c) for c in cmd))
    r = subprocess.run([str(c) for c in cmd], **kw)
    if r.returncode != 0:
        raise SystemExit("  命令失败，退出码 %s" % r.returncode)
    return r


def dir_size(p):
    p = Path(p)
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file())


def rmtree_retry(p, tries=20, wait=2.5):
    """删目录，遇到只读文件先清掉只读位，遇到占用再重试。

    <p>【根因，别被 WinError 5 骗了】jpackage 产出的 {@code TinyQuery.exe}
    **自带只读属性**（实测 mode 0o555）。Python 的 {@code shutil.rmtree}
    在 Windows 上不会自动清只读位，删这种文件直接抛 WinError 5「拒绝访问」。
    而 PowerShell 的 {@code Remove-Item -Force} 会先清属性再删 —— 所以会出现
    「PowerShell 删得掉、python 删不掉」这种看着像权限问题的怪现象。</p>

    <p>实测：连带重试 50 秒都没用，因为只读属性不会自己消失；
    改成先 chmod 之后一次就过。重试只用来兜住真正的瞬时占用。</p>
    """
    p = Path(p)
    if not p.exists():
        return

    def on_error(func, path, exc):
        # 只读导致删不掉时清掉只读位再重试
        try:
            os.chmod(path, stat.S_IWRITE | stat.S_IREAD)
            func(path)
        except Exception:
            raise

    last = None
    for _ in range(tries):
        try:
            shutil.rmtree(p, onerror=on_error)
            return
        except Exception as e:
            last = e
            # 整体重试前，先把树里所有只读文件清一遍
            for f in p.rglob("*"):
                try:
                    if not f.is_dir():
                        os.chmod(f, stat.S_IWRITE | stat.S_IREAD)
                except Exception:
                    pass
            time.sleep(wait)
    raise SystemExit(
        "  删不掉 %s（%s）。\n"
        "  通常是上一次打包出来的 TinyQuery 还在运行。先关掉它，或执行：\n"
        "      Get-Process TinyQuery -ErrorAction SilentlyContinue | Stop-Process -Force"
        % (p, last))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default=None)
    ap.add_argument("--version", default="1.1.0")
    ap.add_argument("--out", default=None, help="输出根目录")
    ap.add_argument("--keep-runtime", action="store_true",
                    help="保留中间产物 runtime（默认保留，便于排查）")
    a = ap.parse_args()

    root = Path(__file__).resolve().parent.parent
    jar = Path(a.jar) if a.jar else root / "target" / ("tinyquery-%s.jar" % a.version)
    if not jar.exists():
        raise SystemExit("  找不到 %s —— 先执行 mvn clean package" % jar)

    out_root = Path(a.out) if a.out else root
    runtime_dir = out_root / "tinyquery-runtime"
    in_dir = out_root / "target" / "jpackage-input"
    dest = out_root / "jpackage-out"

    print("=" * 74)
    print("  精简 runtime + 打包")
    print("=" * 74)
    print()

    # ---- 1. jlink ----
    if runtime_dir.exists():
        rmtree_retry(runtime_dir)
    print("  [1/3] jlink 生成精简 runtime（%d 个模块，语言 %s）" % (len(MODULES), LOCALES))
    run([JLINK,
         "--add-modules", ",".join(MODULES),
         "--include-locales", LOCALES,
         "--output", runtime_dir,
         "--strip-debug",        # 去掉调试信息（-g 产生的那些）
         "--no-header-files",    # 去掉 C 头文件（编译期才用）
         "--no-man-pages",       # 去掉 man 手册
         "--compress=2",         # JDK 17 的写法；zip-6 是 JDK 21+ 才有的语法
         ])
    rt_size = dir_size(runtime_dir)
    print("        runtime: %.1f MB" % (rt_size / 1024 / 1024))
    print()

    # ---- 2. 准备输入目录 ----
    if in_dir.exists():
        rmtree_retry(in_dir)
    in_dir.mkdir(parents=True)
    shutil.copy2(jar, in_dir)

    # ---- 3. jpackage ----
    app = dest / "TinyQuery"
    if app.exists():
        rmtree_retry(app)
    print("  [2/3] jpackage 打包（--runtime-image 指向刚生成的 runtime）")
    args = [JPACKAGE,
            "--type", "app-image",
            "--input", in_dir,
            "--name", "TinyQuery",
            "--main-jar", jar.name,
            "--app-version", a.version,
            "--vendor", "Liuling",
            "--runtime-image", runtime_dir,
            "--dest", dest,
            "--java-options", "-XX:MaxRAMPercentage=50"]
    icon = root / "packaging" / "tinyquery.ico"
    if icon.exists():
        args += ["--icon", icon]
    run(args)

    total = dir_size(app)
    print()
    print("  [3/3] 完成")
    print("        产物   : %s" % app)
    print("        总体积 : %.1f MB" % (total / 1024 / 1024))
    print("        其中 runtime %.1f MB / app %.1f MB"
          % (dir_size(app / "runtime") / 1024 / 1024,
             dir_size(app / "app") / 1024 / 1024))
    print()
    print("  下一步：python packaging/verify-package.py --app \"%s\"" % app)


if __name__ == "__main__":
    if os.name == "nt":
        import io
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    main()
