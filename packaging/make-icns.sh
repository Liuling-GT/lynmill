#!/usr/bin/env bash
#
# 从 src/main/resources/icons/ 的 PNG 生成 macOS 的 .icns。
#
# 用法（在项目根目录执行）：
#   bash packaging/make-icns.sh
#
# 产物：packaging/tinyquery.icns
#
# 素材说明（2026-10-03 更新）：
#   本项目最初只有 16~256 六档 PNG，而 macOS 的 .icns 规范要求到 512 和 1024。
#   当时是用 sips 把 256 放大充数（会糊）。现在 512 / 1024 已经补齐：
#   它们由 tinyquery-256.png 重建而来 —— 先把压缩噪点拍平到品牌调色板
#   （#F8F8F8 / #DBD2FD / #6D4AFF 三色），再双三次放大并收紧边缘斜坡。
#   实测重建后缩回 256 与原图平均色差 1.05/255（肉眼不可辨），
#   1024 尺寸下杂色像素比直接放大少约 8 倍。
#
#   注意：这是「从 256 重建」，不是重新设计了高分辨率原稿。
#   字标笔画在 256 下只有约 8 像素高，1024 下是放大出来的细节，
#   不会比原稿更清晰。若将来拿到高分辨率设计稿，直接替换
#   src/main/resources/icons/tinyquery-{512,1024}.png 即可，本脚本无需改动。
#
# 为什么用 iconutil 而不是手写 .icns：macOS 自带，不需要引入任何依赖
#   （项目有「不引入新的大型依赖」的硬约束，图标工具也一样）。

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$root/src/main/resources/icons"
work="$(mktemp -d)"
iconset="$work/LynMill.iconset"
out="$root/packaging/tinyquery.icns"

trap 'rm -rf "$work"' EXIT

mkdir -p "$iconset"

# ---------- 全部尺寸都由原始素材直接提供 ----------
# 命名必须严格符合 iconutil 的约定：icon_<边长>x<边长>[@2x].png
cp "$src/tinyquery-16.png"   "$iconset/icon_16x16.png"
cp "$src/tinyquery-32.png"   "$iconset/icon_16x16@2x.png"
cp "$src/tinyquery-32.png"   "$iconset/icon_32x32.png"
cp "$src/tinyquery-64.png"   "$iconset/icon_32x32@2x.png"
cp "$src/tinyquery-128.png"  "$iconset/icon_128x128.png"
cp "$src/tinyquery-256.png"  "$iconset/icon_128x128@2x.png"
cp "$src/tinyquery-256.png"  "$iconset/icon_256x256.png"
cp "$src/tinyquery-512.png"  "$iconset/icon_256x256@2x.png"
cp "$src/tinyquery-512.png"  "$iconset/icon_512x512.png"
cp "$src/tinyquery-1024.png" "$iconset/icon_512x512@2x.png"

# ---------- 缺文件要早失败，不能等 iconutil 报一句看不懂的错 ----------
for f in tinyquery-16 tinyquery-32 tinyquery-64 tinyquery-128 tinyquery-256 \
         tinyquery-512 tinyquery-1024; do
    if [ ! -f "$src/$f.png" ]; then
        echo "缺少素材：$src/$f.png" >&2
        exit 1
    fi
done

# ---------- 生成 .icns ----------
iconutil -c icns "$iconset" -o "$out"

echo "已生成 $out"
echo "iconset 内的尺寸与来源："
for f in "$iconset"/*.png; do
    name="$(basename "$f")"
    px="$(sips -g pixelWidth -g pixelHeight "$f" 2>/dev/null \
          | awk '/pixelWidth/{w=$2} /pixelHeight/{h=$2} END{print w"x"h}')"
    printf '  %-24s %s\n' "$name" "$px"
done
