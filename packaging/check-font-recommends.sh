#!/usr/bin/env bash
# 自检：packaging/package-linux.sh 里「给 deb 补中文字体 Recommends」那段真的有效吗？
#
# ---------- 为什么要单独有这个脚本 ----------
#
# 那段的逻辑在打包脚本里，平时不会被执行 —— 只有真正打 deb 时才跑。
# 而"真正打 deb"需要 Linux + jpackage + 一堆系统依赖，绝大多数时候没人跑。
# 于是它会【烂掉而无人察觉】：改坏了、被重构冲掉了，谁都不会发现，
# 直到某个用户装完包看到一屏方块字。
#
# 更糟的是，第一版就是坏的（用 sed 的 s||| 而 $fontPackages 里本身含 |，
# 直接报 "unknown option to `s'"）。当时脚本语法检查是通过的 ——
# bash -n 只查语法，查不出这种【运行期】错误。所以必须真跑。
#
# ---------- 做什么 ----------
#
# 从真实脚本里【截取】函数定义（不是复制一份，避免副本与正本脱节），
# 造一个最小的假 deb，调用它，断言 Recommends 真的出现了。
#
# ---------- 怎么跑 ----------
#
#   bash packaging/check-font-recommends.sh
#
# 在 Linux / WSL 上跑。Windows 上跑不了（没有 dpkg-deb），
# 会自动跳过并返回 0，不阻塞 Windows 上的构建。
#
# 退出码：0 = 通过或跳过；1 = 失败（修复被改坏了）

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
target="$script_dir/package-linux.sh"

if [ ! -f "$target" ]; then
    echo "找不到 $target" >&2
    exit 1
fi

# 没有 dpkg-deb 就没法验（Windows / 精简容器）。跳过而不是失败 ——
# 这不是"检查没通过"，是"此处不具备检查条件"
if ! command -v dpkg-deb >/dev/null 2>&1; then
    echo "跳过：本机没有 dpkg-deb（只有 Linux 上才有意义）"
    exit 0
fi

echo "正在自检「deb 补 Recommends」逻辑..."

# ---------- 从真实脚本里截取函数 ----------
# 用两个锚点定位，两个锚点都在脚本里真实存在。
# 如果哪天它们被改名，这里的 index() 会失败并报错退出 —— 这是有意的：
# 宁可自检失败，也不要静默地测了个空
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

awk '
    /^fontPackages=/ { on = 1 }
    /^# rpm：jpackage 只出/ { on = 0 }
    on { print }
' "$target" > "$tmp/snippet.sh"

if ! grep -q 'addFontRecommends_deb' "$tmp/snippet.sh"; then
    echo "FAIL：没能从 package-linux.sh 里截取到 addFontRecommends_deb" >&2
    echo "      （锚点可能被改名了，请同步更新本脚本）" >&2
    exit 1
fi

# ---------- 造一个假 deb 并调用真函数 ----------
cat > "$tmp/run.sh" <<'RUNNER'
set -uo pipefail
# shellcheck disable=SC1090
. "$1"

cd "$2" || exit 1
rm -rf ft && mkdir -p ft/DEBIAN ft/usr/share/doc/tq
cat > ft/DEBIAN/control <<'CTL'
Package: lynmill
Version: 1.1.0
Architecture: amd64
Maintainer: Liuling <x@y.com>
Depends: libx11-6, libasound2
Description: LynMill test package
CTL
echo x > ft/usr/share/doc/tq/readme
dpkg-deb -b ft fakedeb.deb >/dev/null 2>&1 || { echo "RESULT=FAIL 造不出假 deb"; exit 0; }

before_depends="$(dpkg-deb -f fakedeb.deb Depends)"
if [ -n "$(dpkg-deb -f fakedeb.deb Recommends 2>/dev/null)" ]; then
    echo "RESULT=FAIL 假 deb 一开始就带 Recommends，测不出东西"; exit 0
fi

addFontRecommends_deb "$2/fakedeb.deb" >/dev/null 2>&1

after="$(dpkg-deb -f fakedeb.deb Recommends 2>/dev/null || echo '')"
after_depends="$(dpkg-deb -f fakedeb.deb Depends)"

# --- 断言 1：Recommends 真的出现了 ---
if [ -z "$after" ]; then
    echo "RESULT=FAIL Recommends 没有被写入（修复已失效）"; exit 0
fi

# --- 断言 2：必须含中文字体包名 ---
case "$after" in
    *fonts-wqy-microhei*|*fonts-noto-cjk*) ;;
    *) echo "RESULT=FAIL Recommends 里没有中文字体包：$after"; exit 0 ;;
esac

# --- 断言 3：原有的 Depends 不能被破坏 ---
# 只加 Recommends，不该动硬依赖 —— 那会让本来能装的机器装不上
if [ "$before_depends" != "$after_depends" ]; then
    echo "RESULT=FAIL Depends 被改动了：$before_depends -> $after_depends"; exit 0
fi

# --- 断言 4：包内文件要还在（重新打包不能丢东西）---
if ! dpkg-deb -c fakedeb.deb 2>/dev/null | grep -q 'usr/share/doc'; then
    echo "RESULT=FAIL 重新打包后包内容丢失"; exit 0
fi

# --- 断言 5：幂等 —— 再跑一次不应重复写入 ---
addFontRecommends_deb "$2/fakedeb.deb" >/dev/null 2>&1
again="$(dpkg-deb -f fakedeb.deb Recommends 2>/dev/null || echo '')"
if [ "$again" != "$after" ]; then
    echo "RESULT=FAIL 不幂等：$after -> $again"; exit 0
fi

echo "RESULT=PASS Recommends=$after"
RUNNER

out="$(bash "$tmp/run.sh" "$tmp/snippet.sh" "$tmp" 2>&1)"

case "$out" in
    *RESULT=PASS*)
        echo "  通过：$(printf '%s' "$out" | grep -o 'Recommends=.*')"
        echo "     已确认：写入成功、含中文字体、Depends 未被改动、包内容完整、可重复执行"
        exit 0
        ;;
    *RESULT=FAIL*)
        echo "  失败：$(printf '%s' "$out" | grep -o 'RESULT=FAIL.*')" >&2
        echo "     这说明 package-linux.sh 里给 deb 补中文字体依赖的修复已经失效。" >&2
        echo "     用户装完 deb 后，托盘菜单的中文会显示成方块。" >&2
        exit 1
        ;;
    *)
        echo "  自检本身出错，没有拿到结果：" >&2
        printf '%s\n' "$out" | sed 's/^/     /' >&2
        exit 1
        ;;
esac
