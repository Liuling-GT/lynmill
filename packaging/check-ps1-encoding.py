# -*- coding: utf-8 -*-
"""
检查所有 .ps1 脚本的编码是否安全。

【为什么需要这个】
Windows PowerShell 5.1 读 .ps1 时，没有 BOM 就按系统 ANSI 代码页解码
（简体中文 Windows 上是 GBK）。这几个脚本里全是中文注释，按 GBK 解出来是乱码，
其中的字符会破坏引号配对，导致整个脚本连解析都过不去。

而且这个问题**必然复发**：绝大多数编辑器（包括本项目的 AI 助手所用的编辑工具）
默认写「UTF-8 无 BOM」。实测：加完 BOM 后紧接着做一次编辑，BOM 立刻又没了。

所以不能靠"记住要加 BOM"，要有一个能在提交前跑出来的检查。

用法：
    python packaging/check-ps1-encoding.py
退出码 0 = 全部安全，1 = 有问题（并打印怎么修）。
"""
import sys
import io
from pathlib import Path

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
UTF8_BOM = b"\xef\xbb\xbf"

bad = []
ok = []
for p in sorted(ROOT.rglob("*.ps1")):
    if any(part in ("node_modules", "target", "jpackage-out", ".git") for part in p.parts):
        continue
    raw = p.read_bytes()
    rel = p.relative_to(ROOT)
    if not raw:
        continue

    if raw[:3] == UTF8_BOM:
        ok.append((rel, "UTF-8 带 BOM"))
        continue
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):
        # 带 BOM 的 UTF-16 也能被正确识别，虽然不如 UTF-8 通用
        ok.append((rel, "UTF-16 带 BOM（可用，但建议统一成 UTF-8）"))
        continue

    # 无 BOM：判断它是否含非 ASCII 字符 —— 纯 ASCII 的脚本无 BOM 也安全
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        bad.append((rel, "不是合法 UTF-8，且无 BOM"))
        continue
    non_ascii = [c for c in text if ord(c) > 127]
    if non_ascii:
        bad.append((rel, "UTF-8 无 BOM，且含 %d 个非 ASCII 字符（会被按 GBK 误读）"
                    % len(non_ascii)))
    else:
        ok.append((rel, "纯 ASCII，无 BOM 也安全"))

print("=" * 74)
print("  PowerShell 脚本编码检查")
print("=" * 74)
print()
for rel, why in ok:
    print("  %-42s %s" % (rel, why))
for rel, why in bad:
    print("  %-42s %s" % (rel, why))
print()

if bad:
    print("  修法（在项目根目录执行）：")
    for rel, _ in bad:
        print('    python -c "p=r\'%s\';'
              'd=open(p,\'rb\').read();open(p,\'wb\').write(b\'\\xef\\xbb\\xbf\'+d)"' % rel)
    print()
    print("  注意：加完 BOM 后再编辑该文件，多数编辑器会把 BOM 又去掉 ——")
    print("  所以改完 .ps1 都要重跑本检查。")
    sys.exit(1)

print("  全部安全。")
sys.exit(0)
