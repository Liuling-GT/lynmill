#!/usr/bin/env bash
#
# 从 src/main/resources/icons/ 的 PNG 生成 macOS 的 .icns。
#
# 用法（在项目根目录执行）：
#   bash packaging/make-icns.sh
#
# 产物：packaging/tinyquery.icns
#
# ⚠️ 当前 512 与 1024 是由 256 放大出来的占位图，会发糊。
#    现有素材最大只有 256×256（与 Windows 端的情况一致），而 macOS 的
#    .icns 规范要求有 512 和 1024 —— 这是本项目已知的素材缺口。
#    正式发布前应当用原始设计稿重新导出，或重新设计。
#    这里先把流程跑通，免得因为缺图标卡住打包验证。
#
# 为什么用 sips 而不是别的方式放大：macOS 自带，不需要引入任何依赖
#    （项目有「不引入新的大型依赖」的硬约束，图标工具也一样）。

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$root/src/main/resources/icons"
work="$(mktemp -d)"
iconset="$work/TinyQuery.iconset"
out="$root/packaging/tinyquery.icns"

trap 'rm -rf "$work"' EXIT

mkdir -p "$iconset"

# ---------- 直接可用的尺寸 ----------
# 命名必须严格符合 iconutil 的约定：icon_<边长>x<边长>[@2x].png
cp "$src/tinyquery-16.png"  "$iconset/icon_16x16.png"
cp "$src/tinyquery-32.png"  "$iconset/icon_16x16@2x.png"
cp "$src/tinyquery-32.png"  "$iconset/icon_32x32.png"
cp "$src/tinyquery-64.png"  "$iconset/icon_32x32@2x.png"
cp "$src/tinyquery-128.png" "$iconset/icon_128x128.png"
cp "$src/tinyquery-256.png" "$iconset/icon_128x128@2x.png"
cp "$src/tinyquery-256.png" "$iconset/icon_256x256.png"

# ---------- 缺的尺寸：从 256 放大（占位，见文件头说明） ----------
sips -z 512  512  "$src/tinyquery-256.png" --out "$iconset/icon_256x256@2x.png" >/dev/null
sips -z 512  512  "$src/tinyquery-256.png" --out "$iconset/icon_512x512.png"    >/dev/null
sips -z 1024 1024 "$src/tinyquery-256.png" --out "$iconset/icon_512x512@2x.png" >/dev/null

# ---------- 生成 .icns ----------
iconutil -c icns "$iconset" -o "$out"

echo "已生成 $out"
echo "iconset 内的尺寸与来源："
for f in "$iconset"/*.png; do
    name="$(basename "$f")"
    px="$(sips -g pixelWidth -g pixelHeight "$f" 2>/dev/null \
          | awk '/pixelWidth/{w=$2} /pixelHeight/{h=$2} END{print w"x"h}')"
    case "$name" in
        icon_256x256@2x.png|icon_512x512.png|icon_512x512@2x.png)
            note="  ← 由 256 放大，占位、会糊" ;;
        *)
            note="  ← 原始素材" ;;
    esac
    printf '  %-24s %s%s\n' "$name" "$px" "$note"
done
