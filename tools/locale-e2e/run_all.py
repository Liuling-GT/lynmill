# -*- coding: utf-8 -*-
"""一条命令跑完界面偏好（语言 + 主题）的一致性回归。

    python tools/locale-e2e/run_all.py

退出码：0 = 全通过；1 = 有场景失败；2 = 环境不满足（缺 jar / 缺浏览器）。

【什么时候跑】发版前、以及改动以下任一位置之后：
  · tinyquery-frontend/src/locales/**
  · tinyquery-frontend/src/theme/**
  · tinyquery-frontend/src/assets/tokens.css 的主题块
  · tinyquery-frontend/src/App.vue 里与语言、types 相关的逻辑
  · tinyquery-frontend/src/main.js 的启动顺序
  · 后端 /api/preferences/locale、/api/preferences/theme、以及任何按
    X-LynMill-Locale 下发文案的接口
  · 后端 Messages / DesktopLocale / DesktopTheme

【为什么要手动跑而不是接 CI】真浏览器 + 真实启动时序是这套检查的全部价值所在。
接 CI 需要额外的 headless 浏览器与 JDK，收益不抵成本（见待办编号 50 的取舍）。
"""
import io
import sys
import time
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

# Windows 控制台默认不是 UTF-8，中文会变成乱码
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import harness  # noqa: E402
import scenarios  # noqa: E402

# 场景清单。加了新场景就挂到这里，run_all 会自动跑
SCENARIOS = [
    ("s1", scenarios.s1_local_empty_follows_server),
    ("s2", scenarios.s2_local_choice_wins),
    ("s3", scenarios.s3_server_change_propagates),
    ("s4", scenarios.s4_switching_refetches_server_metadata),
    ("s5", scenarios.s5_no_chinese_in_english_mode),
    ("s6", scenarios.s6_theme_reconcile),
    ("s7", scenarios.s7_theme_switch_matches_server_contract),
]

BAR = "=" * 78


def main():
    print(BAR)
    print("语言一致性回归（真浏览器 / 真 localStorage / 真启动时序）")
    print(BAR)

    try:
        harness.require_jar()
        browser = harness.find_browser()
    except harness.HarnessError as e:
        print()
        print("【环境不满足】%s" % e)
        print()
        print("这不是产品缺陷。补齐环境后重跑。")
        return 2

    print("  jar     : %s" % harness.JAR)
    print("  浏览器  : %s" % browser)
    print("  node    : %s" % harness.NODE)
    print("  工作目录: %s" % harness.WORK)
    print()

    harness.fresh_workdir()
    driver = harness.write_driver()
    env = {"browser": browser, "driver": driver, "work": harness.WORK}

    # 开跑前清掉可能残留的浏览器进程。它们的旧页面会继续向后端发请求
    # （包括会改状态的切语言），让场景读到莫名其妙的状态 —— 实测踩过
    n = harness.kill_all_stray()
    if n:
        print("  【注意】清掉了 %d 个上次残留的浏览器进程 —— "
              "它们会干扰结果，本次已清理" % n)
        print()
    time.sleep(1.0)

    results = []
    for name, fn in SCENARIOS:
        title = (fn.__doc__ or "").strip().split("\n")[0]
        print("-" * 78)
        print("[%s] %s" % (name, title))
        print("-" * 78)
        try:
            passed, summary, details = fn(env)
        except harness.HarnessError as e:
            passed, summary, details = False, "环境/驱动失败：%s" % e, []
        except Exception:
            passed, summary, details = False, "场景抛异常", traceback.format_exc().split("\n")
        results.append((name, passed, summary))
        for d in details:
            for line in str(d).split("\n"):
                if line.strip():
                    print("    " + line)
        print("  → %s  %s" % ("✅ 通过" if passed else "❌ 失败", summary))
        print()

    print(BAR)
    print("汇总")
    print(BAR)
    for name, passed, summary in results:
        print("  %-4s %-6s %s" % (name, "✅" if passed else "❌", summary))

    failed = [r for r in results if not r[1]]
    print()
    if failed:
        print("  %d / %d 个场景失败" % (len(failed), len(results)))
        return 1
    print("  全部 %d 个场景通过" % len(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
