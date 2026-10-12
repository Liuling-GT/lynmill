#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
中英双文件文档的一致性检查。

【为什么需要它】
文档翻成两份之后，改一边忘一边是迟早的事 —— 这个项目已经有过 README
与实现脱节的前科（编号 22/23/42 都在治这个，还专门写了 ReadmeAudit）。
ReadmeAudit 管的是「文档说的和代码做的是否一致」，本脚本管的是另一件事：
「英文版和中文版是否还是同一份文档」。

【检查什么】
只检查「翻译本来就不该改变」的东西，所以能可靠地逐字比对：

  1. 代码块内容      —— 命令 / 路径 / SQL / 配置片段，两份必须逐字相同
  2. 章节结构        —— #/##/### 的数量与层级序列必须一一对应
  3. 链接目标        —— 所有 URL 与相对路径的集合必须相同（语言互链除外）
  4. 表格形状        —— 每个表格的行数与列数必须相同
  5. 反向检查        —— 英文版里不该出现中文（例外：语言互链里的「中文」二字）

这五条抓不住「同一句话两边译得不一样」，但能抓住「加了一节只加了一边」
「改了个命令只改了一边」这类真正会发生的漂移，并指出具体是第几个代码块。

【怎么跑】
    python tools/doc-pair-check.py
退出码 0 = 全部一致，1 = 有漂移。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# (英文文件, 中文文件)
PAIRS = [
    ("README.md", "README.zh-CN.md"),
    ("NOTES.md", "NOTES.zh-CN.md"),
]

# 语言互链那一行不参与比对（两份本来就不一样）
LANG_LINK = re.compile(r"^\s*\*{0,2}(English|中文)\*{0,2}\s*\|\s*\[(English|中文)\]\([^)]+\)\s*$")

CJK = re.compile(r"[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]")

# 英文版里允许出现中文的行。
#
# 判据是「这是给读者的说明，还是被引用的真实内容」。说明必须翻译；
# 而下列内容一旦翻译，反而会让正文引用不上、或丢掉它作为证据的意义：
#   · 语言互链里的「中文」二字
#   · **SQL 示例里的中文列名**（城市 / 姓名 / 别名 / 城市名）——
#     那是示例数据的一部分，不是说明文字；这些例子恰恰在演示
#     「中文标识符会被怎么处理」，换成英文就没在测同一件事了
#   · **配置值** `自动`（CSV 编码）与界面字样「中文」—— 存的是实际取值
#   · **演示字符串** 「城 市」—— 用来说明列名被空格切开的后果
#   · PDF 章节的 ASCII 示例 —— 真实中文 PDF 的内容，
#     下一段正文明确引用了其中的「（收」与「尾端）」，翻译了就自相矛盾
#   · **程序真实报错** —— 引用的是实际输出
#   · **真实存在的文件名** —— 路径里带中文，不是可翻译的措辞
EXEMPT_CN = [
    re.compile(r"\[中文\]\("),                    # 语言互链
    re.compile(r"城市|姓名|别名|城市名"),           # SQL 示例里的中文标识符
    re.compile(r"「中文」|「城 市」"),              # 界面字样 / 演示字符串
    re.compile(r"`自动`"),                        # 存储的配置值
    re.compile(r"阶段 0 · 代码改造"),              # PDF 示例图
    re.compile(r"尾端）端口策略改造"),              # PDF 示例图
    re.compile(r"实际得到："),                     # PDF 示例的输出行
    re.compile(r"（收"),                          # 正文引用示例中的片段
    re.compile(r"尾端）"),                         # 正文引用示例中的片段
    re.compile(r"→ 报：数据源不存在"),              # 程序真实报错
    re.compile(r"Unknown column"),                # 程序真实报错
    re.compile(r"TinyQuery-Data/[\w\-/]*[\u4e00-\u9fff]"),  # 真实文件名
]


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def strip_lang_link(text: str) -> str:
    return "\n".join(line for line in text.split("\n") if not LANG_LINK.match(line))


COMMENT_WHOLE = re.compile(r"^\s*(#|--|//|/\*)")
COMMENT_TAIL = re.compile(r"\s(#|--)\s")
# 清单说明行：`datasources.json     已注册的数据源（不含密码）`
# 这种「文件名 + 两列以上空白 + 说明」的行整行都是给人读的说明，没有可执行内容，
# 翻译它是正确的，所以按注释对待。
COMMENT_LISTING = re.compile(r"^\s*\S*(\.\w+|/\S+)\s{2,}\S")


PLACEHOLDER = re.compile(r"<[^<>]{1,80}>")


def norm_placeholder(line: str) -> str:
    """把 `<...>` 占位符的内容抹平，只留标记。

    占位符是**给读者的提示**，不是真实取值 —— `-d <临时目录>` 与 `-d <temp dir>`
    指的是同一个参数，两份文档各用各的语言是对的。
    代价：`List<String>` 与 `List<Integer>` 这类写法也会被抹平而漏检。
    文档代码块里这种写法极少，换取「占位符可翻译」这个更常见的好处，划算。
    """
    return PLACEHOLDER.sub("<>", line)


def strip_comment(line: str) -> tuple[str, str]:
    """把一行拆成 (代码部分, 注释部分)。

    代码块里的注释是**给人读的说明**，两份文档本来就该各用各的语言，
    所以不能拿注释文字去逐字比对 —— 否则每次翻译注释都会被报成漂移。
    但注释的**行数**要一致：少一行注释往往意味着漏译了一段说明。

    行尾注释（`sudo dpkg -i x.deb    # Debian / Ubuntu`）与清单说明行也要拆，
    否则命令后面的说明一翻译就会被误判成「命令不一致」。
    """
    s = line.strip()
    if not s:
        return "", ""
    if COMMENT_WHOLE.match(line):
        return "", s
    if COMMENT_LISTING.match(line):
        return "", s
    m = COMMENT_TAIL.search(line)
    if m:
        return line[:m.start()].rstrip(), line[m.start():].strip()
    return line.rstrip(), ""


def code_blocks(text: str) -> list[tuple[list[str], int]]:
    """提取围栏代码块，返回 [(代码行列表, 注释行数), ...]。"""
    out: list[tuple[list[str], int]] = []
    buf: list[str] = []
    inside = False
    for line in text.split("\n"):
        if line.lstrip().startswith("```"):
            if inside:
                code, comments = [], 0
                for ln in buf:
                    c, cm = strip_comment(ln)
                    if c:
                        code.append(c)
                    if cm:
                        comments += 1
                out.append((code, comments))
                buf, inside = [], False
            else:
                inside = True
            continue
        if inside:
            buf.append(line)
    if inside:  # 未闭合的围栏 —— 也交出去，让比对暴露问题
        code, comments = [], 0
        for ln in buf:
            c, cm = strip_comment(ln)
            if c:
                code.append(c)
            if cm:
                comments += 1
        out.append((code, comments))
    return out


def headings(text: str) -> list[tuple[int, str]]:
    return [(len(m.group(1)), m.group(2).strip())
            for m in re.finditer(r"^(#{1,6})\s+(.*)$", text, re.M)]


def heading_levels(text: str) -> list[int]:
    return [lv for lv, _ in headings(text)]


def links(text: str) -> list[str]:
    """收集链接目标，做两处规范化后用于比对。

    1. `.zh-CN.md` → `.md`：中文版链到 NOTES.zh-CN.md、英文版链到 NOTES.md，
       语义上指向「同一份文档的对应语言版本」，应视为一致。
    2. 丢掉纯锚点（`#...`）：标题语言不同，锚点必然不同，这是必然差异而非漂移。
    """
    raw = [m.group(1).strip() for m in re.finditer(r"\[[^\]]*\]\(([^)]+)\)", text)]
    raw += [m.group(0) for m in re.finditer(r"https?://[^\s)>\]`]+", text)]
    norm = []
    for t in raw:
        t = re.sub(r"\.zh-CN\.md", ".md", t)
        t = re.sub(r"\.zh\.md", ".md", t)
        if t.startswith("#"):
            continue
        norm.append(t)
    return sorted(set(norm))


def tables(text: str) -> list[tuple[int, int]]:
    """返回每个表格的 (行数, 列数) 列表。分隔行 |---|---| 不计入行数。"""
    out, rows = [], []
    for line in text.split("\n"):
        s = line.strip()
        if s.startswith("|") and s.endswith("|") and s.count("|") >= 2:
            cells = [c for c in s.split("|")[1:-1]]
            if all(re.fullmatch(r"\s*:?-{2,}:?\s*", c) for c in cells):
                continue  # 分隔行
            rows.append(len(cells))
        else:
            if rows:
                out.append((len(rows), max(rows)))
                rows = []
    if rows:
        out.append((len(rows), max(rows)))
    return out


def cjk_lines(text: str) -> list[tuple[int, str]]:
    out = []
    for i, line in enumerate(text.split("\n"), 1):
        if not CJK.search(line):
            continue
        if any(p.search(line) for p in EXEMPT_CN):
            continue
        out.append((i, line.strip()[:90]))
    return out


def check_pair(en_name: str, zh_name: str) -> list[str]:
    errs: list[str] = []
    en_p, zh_p = ROOT / en_name, ROOT / zh_name

    if not en_p.exists():
        return ["缺少英文文件：%s" % en_name]
    if not zh_p.exists():
        return ["缺少中文文件：%s" % zh_name]

    en_raw, zh_raw = read(en_p), read(zh_p)
    en = strip_lang_link(en_raw)
    zh = strip_lang_link(zh_raw)

    # 0) 语言互链：两边都要有。
    #    漏了这一步就会出盲区 —— 互链行在下面被两边一起过滤掉，
    #    「英文版能切到中文、中文版切不回英文」这种单向链接就永远查不出来。
    for name, raw in ((en_name, en_raw), (zh_name, zh_raw)):
        if not any(LANG_LINK.match(l) for l in raw.split("\n")):
            errs.append("%s 缺少顶部语言互链（**English** | [中文](...) 或反向）" % name)

    # 1) 代码块：比代码行，注释只比数量
    eb, zb = code_blocks(en), code_blocks(zh)
    if len(eb) != len(zb):
        errs.append("代码块数量不一致：英文 %d 个，中文 %d 个" % (len(eb), len(zb)))
    for i, ((ec, ecm), (zc, zcm)) in enumerate(zip(eb, zb), 1):
        ecn = [norm_placeholder(x) for x in ec]
        zcn = [norm_placeholder(x) for x in zc]
        if ecn != zcn:
            errs.append("第 %d 个代码块的**代码行**不一致（注释与占位符差异不算）" % i)
            for j in range(max(len(ec), len(zc))):
                x = ec[j] if j < len(ec) else "<缺行>"
                y = zc[j] if j < len(zc) else "<缺行>"
                if norm_placeholder(x) != norm_placeholder(y):
                    errs.append("    · 第 %d 行：英文 %r / 中文 %r" % (j + 1, x[:58], y[:58]))
                    break
        if ecm != zcm:
            errs.append("第 %d 个代码块的注释行数不一致：英文 %d 行，中文 %d 行（可能漏译了说明）"
                        % (i, ecm, zcm))

    # 2) 章节结构
    el, zl = heading_levels(en), heading_levels(zh)
    if el != zl:
        errs.append("章节层级序列不一致：英文 %s / 中文 %s" % (el, zl))

    # 3) 链接目标
    el2, zl2 = links(en), links(zh)
    only_en = sorted(set(el2) - set(zl2))
    only_zh = sorted(set(zl2) - set(el2))
    if only_en:
        errs.append("只有英文版有的链接：%s" % only_en[:6])
    if only_zh:
        errs.append("只有中文版有的链接：%s" % only_zh[:6])

    # 4) 表格形状
    et, zt = tables(en), tables(zh)
    if et != zt:
        errs.append("表格形状不一致：英文 %s / 中文 %s" % (et, zt))

    # 5) 英文版里的中文
    bad = cjk_lines(en)
    if bad:
        errs.append("英文版里有 %d 行中文（应已翻译）：" % len(bad))
        for ln, txt in bad[:5]:
            errs.append("    · %s:%d  %s" % (en_name, ln, txt))

    return errs


def main() -> int:
    print("=" * 74)
    print("  中英双文件一致性检查")
    print("=" * 74)
    total = 0
    for en_name, zh_name in PAIRS:
        errs = check_pair(en_name, zh_name)
        if errs:
            total += len(errs)
            print("\n  ❌ %s  ↔  %s" % (en_name, zh_name))
            for e in errs:
                print("     " + e)
        else:
            print("\n  ✅ %s  ↔  %s  一致" % (en_name, zh_name))
    print()
    if total:
        print("  共 %d 处漂移。改完两边再跑一次。" % total)
        return 1
    print("  全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
