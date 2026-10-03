#!/usr/bin/env bash
#
# TinyQuery Linux 打包脚本 —— 用 jpackage 生成免安装的 app-image，并压成 tar.gz。
#
# 用法（在项目根目录执行）：
#   bash packaging/package-linux.sh            # 只出 tar.gz（默认，最省事）
#   bash packaging/package-linux.sh --deb      # 额外出一个 .deb
#   bash packaging/package-linux.sh --rpm      # 额外出一个 .rpm
#   bash packaging/package-linux.sh --deb --rpm
#
# 前置条件：
#   1. 已执行 mvn clean package，target/ 下有 tinyquery-1.0.0.jar
#   2. 已装 JDK 17（jpackage 从 JDK 14 起随 JDK 提供）
#   3. 打 deb 需 dpkg-deb 与 fakeroot；打 rpm 需 rpmbuild
#   4. 系统必须装了中文字体（见下面「中文字体」一节）
#
# 产物（都在 jpackage-out/ 下）：
#   TinyQuery/                                 app-image 目录，解压即用
#   TinyQuery-1.0.0-linux-x86_64.tar.gz        可直接分发的压缩包
#   TinyQuery-1.0.0-1.x86_64.deb               仅在传了 --deb 时
#   TinyQuery-1.0.0-1.x86_64.rpm               仅在传了 --rpm 时
#
# ---------- 为什么默认只出 tar.gz，不出 deb/rpm（桌面化清单第 32 步）----------
# Linux 桌面环境碎片化严重：.deb 只能在 Debian/Ubuntu 系装，.rpm 只能在
# RedHat 系装，而 tar.gz 解压就能跑、覆盖所有发行版。所以先出 tar.gz ——
# 覆盖面最大、成本最低、不依赖任何打包工具链。deb/rpm 按需再加。
#
# ---------- glibc：决定兼容面的铁律（必须知道）----------
# app-image 虽然自带 JRE，但 jpackage 生成的**启动器二进制仍然动态链接系统的
# glibc**。所以在 Ubuntu 24.04（glibc 2.39）上打的包，在 Ubuntu 22.04
# （glibc 2.35）、20.04（glibc 2.31）上会报 GLIBC_x.xx not found 直接跑不起来。
# 规则：**想覆盖多老的发行版，就在多老的发行版上打包。**
# 本项目用 Ubuntu 22.04 LTS 作为构建机，产出可覆盖 22.04 及以上（含 Fedora 36+）。
#
# ---------- 平台选项必须按包类型分开放（实测踩过，别合并）----------
# jpackage 对「与该包类型无关的选项」**不是忽略，而是直接报错退出**：
#     Error: Option [--linux-package-name] is not valid with type [app-image]
# 实测 JDK 17 上，--linux-package-name / --linux-app-category / --linux-menu-group
# / --linux-deb-maintainer / --linux-rpm-license-type 全都不接受 app-image。
# 所以脚本用「公共参数数组 + 按类型追加」的写法，而不是把所有选项拼在一起。
# 同一类坑在 Windows 那边也踩过：--mac-package-identifier 传到 Windows 会直接失败。
#
# ---------- 中文字体：不打就会构建失败（实测踩过）----------
# 系统若没装任何 CJK 字体，mvn package 会失败在 TrayIconInstallerTest ——
# 那个测试验证「托盘菜单字体能否显示中文」，而最小化安装的 Linux 上
# SansSerif 会映射到无中文字形的字体，于是托盘菜单的中文显示成方块。
# 这不是测试写错了，是它正确抓到了 Linux 特有的真实风险。
# 装：apt-get install -y fonts-noto-cjk
# 注意这条同时是**产品**问题：用户机器上若没有中文字体，托盘菜单一样是方块。
#
# ---------- 为什么 --icon 用 PNG ----------
# jpackage 的 --icon 是平台特定的：Windows 只认 .ico，macOS 只认 .icns，
# Linux 只认 PNG。三者不能混用 —— 传错格式会直接报错退出。

set -euo pipefail

# 本脚本在 packaging/ 下，上一级就是项目根目录
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 必须与 pom.xml 的 <version> 一致。jpackage 的 --app-version 只接受
# 「数字和点」，写 1.0-SNAPSHOT 会被直接拒绝。
appVersion='1.0.0'
# --vendor 会写进 deb/rpm 的维护者字段与 .desktop 里
vendor='Liuling'
# 桌面环境里显示的菜单名与图标名。全小写、无空格是 Linux 的惯例
packageName='tinyquery'
# deb 的 Maintainer 字段。jpackage 的 --linux-deb-maintainer 只接受**邮箱地址**——
# 它会自己拿 --vendor 拼成「厂商 <邮箱>」。所以这里只能写邮箱：
# 写成「Liuling <noreply@example.com>」会被拼成
# 「Liuling <Liuling <noreply@example.com>>」这种畸形值（实测踩过，dpkg-deb -f 能看到）。
# 正式分发前应换成真实联系方式，example.com 只是占位。
debMaintainer='noreply@example.com'
# rpm 的 License 字段。本项目未附许可证文件时，这个字段只是占位。
rpmLicense='Apache-2.0'

jar="$root/target/tinyquery-$appVersion.jar"
icon="$root/src/main/resources/icons/tinyquery-512.png"
in="$root/target/jpackage-input"
dest="$root/jpackage-out"
app="$dest/TinyQuery"

arch="$(uname -m)"

# ---------- 解析参数 ----------
wantDeb=0
wantRpm=0
for arg in "$@"; do
    case "$arg" in
        --deb) wantDeb=1 ;;
        --rpm) wantRpm=1 ;;
        -h|--help)
            sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "未知参数：$arg（可用：--deb --rpm --help）" >&2; exit 2 ;;
    esac
done

# ---------- 1. 前置检查：早失败，别等 jpackage 报一句看不懂的错 ----------
command -v jpackage >/dev/null 2>&1 || {
    echo '找不到 jpackage，请确认 JDK 17 已安装且在 PATH 里' >&2; exit 1; }
[ -f "$jar" ] || {
    echo "找不到 $jar —— 请先执行 mvn clean package" >&2; exit 1; }
[ -f "$icon" ] || {
    echo "找不到图标 $icon" >&2; exit 1; }

if [ "$wantDeb" = 1 ]; then
    command -v dpkg-deb >/dev/null 2>&1 || {
        echo '找不到 dpkg-deb，打 deb 需要它（apt-get install -y dpkg-dev）' >&2; exit 1; }
    command -v fakeroot >/dev/null 2>&1 || {
        echo '找不到 fakeroot，jpackage 打 deb 需要它来模拟 root 属主' >&2; exit 1; }
fi
if [ "$wantRpm" = 1 ]; then
    command -v rpmbuild >/dev/null 2>&1 || {
        echo '找不到 rpmbuild，打 rpm 需要它（apt-get install -y rpm）' >&2; exit 1; }
fi

# 中文字体检查：缺字体时 mvn package 根本过不去，
# 与其让人对着测试失败发呆，不如在这里直接说清楚
if ! fc-list :lang=zh 2>/dev/null | grep -q .; then
    echo '警告：系统没有中文字体，托盘菜单的中文会显示成方块，且 mvn package 会失败在' >&2
    echo '      TrayIconInstallerTest。装法：apt-get install -y fonts-noto-cjk' >&2
fi

# ---------- 2. 准备干净的输入目录 ----------
# 不能直接 --input target：那会把 test-classes/ 等一起打进发布包。
# jpackage 只认目录不认单个 jar，所以先把 jar 复制到一个只有它的目录。
rm -rf "$in"
mkdir -p "$in"
cp "$jar" "$in"

# ---------- 3. jpackage 不覆盖已存在的输出目录 ----------
# 输出目录已存在时它会直接报错退出，而不是覆盖，所以必须先删。
rm -rf "$app"

# ---------- 4. 公共参数 ----------
# --main-jar 是必需的：不加会报「创建应用程序映像需要 --main-jar 或 --module」。
#   主类不用写死 —— jar 的 MANIFEST.MF 里已有 Main-Class，jpackage 会自己读。
# 这些参数对 app-image / deb / rpm 都有效，所以抽出来复用。
commonArgs=(
    --input "$in"
    --name TinyQuery
    --main-jar "tinyquery-$appVersion.jar"
    --app-version "$appVersion"
    --vendor "$vendor"
    --icon "$icon"
    --dest "$dest"
)

# 这些**只对 deb/rpm 有效**，放进 app-image 会直接报错（见文件头说明）
#
# --linux-menu-group 与 --linux-app-category 是两个不同的东西，别搞混（实测踩过）：
#   --linux-app-category 只写 deb 的 Section / rpm 的 Group 字段；
#   --linux-menu-group  才决定 .desktop 里的 Categories。
# 不传 menu-group 时 jpackage 会写死 Categories=Unknown，而 desktop-file-validate
# 明确报错：「Unknown」不是注册分类，应用会只出现在菜单的"其他"角落甚至不显示。
packageArgs=(
    --linux-package-name "$packageName"
    --linux-app-category Utility
    --linux-menu-group Utility
    --linux-shortcut
)

# ---------- 5. app-image ----------
echo '正在打包 app-image...'
jpackage --type app-image "${commonArgs[@]}"

# ---------- 6. 压成 tar.gz ----------
# 包内顶层就是 TinyQuery/，用户解压即可得到一个完整目录。
tarball="$dest/TinyQuery-$appVersion-linux-$arch.tar.gz"
rm -f "$tarball"
echo '正在压缩 tar.gz...'
tar -czf "$tarball" -C "$dest" TinyQuery

# ---------- 7. 可选：deb / rpm ----------
# 这两种是「安装到系统」的包，用户从应用菜单启动。
if [ "$wantDeb" = 1 ]; then
    echo '正在打 deb...'
    jpackage --type deb "${commonArgs[@]}" "${packageArgs[@]}" \
        --linux-deb-maintainer "$debMaintainer"
fi

if [ "$wantRpm" = 1 ]; then
    echo '正在打 rpm...'
    jpackage --type rpm "${commonArgs[@]}" "${packageArgs[@]}" \
        --linux-rpm-license-type "$rpmLicense"
fi

# ---------- 8. 汇报（不是只看「打包成功」，要回报实际写入了什么）----------
echo
echo "打包完成。构建设施："
printf '  %-24s %s\n' '架构' "$arch"
printf '  %-24s %s\n' 'jpackage' "$(jpackage --version 2>/dev/null || echo '?')"
printf '  %-24s %s\n' 'glibc' "$(ldd --version 2>/dev/null | head -1 | awk '{print $NF}')"
printf '  %-24s %s\n' '应用标识' "TinyQuery $appVersion / $vendor"

cfg="$app/lib/app/TinyQuery.cfg"
if [ -f "$cfg" ]; then
    printf '  %-24s %s\n' '.cfg 内 java-options' \
        "$(grep -m1 '^java-options' "$cfg" || echo '(无)')"
fi

echo
echo "产物："
printf '  %-56s %8s\n' "${app#$root/}" "$(du -sh "$app" | cut -f1)"
printf '  %-56s %8s\n' "${tarball#$root/}" "$(du -h "$tarball" | cut -f1)"
for f in "$dest"/TinyQuery-"$appVersion"-1.*.deb "$dest"/TinyQuery-"$appVersion"-1.*.rpm; do
    [ -f "$f" ] && printf '  %-56s %8s\n' "${f#$root/}" "$(du -h "$f" | cut -f1)"
done

echo
echo "分发说明："
echo "  tar.gz 解压后直接运行 TinyQuery/bin/TinyQuery（tar 会保留可执行位）"
if [ "$wantDeb" = 1 ] || [ "$wantRpm" = 1 ]; then
    echo "  deb/rpm 安装后可从应用菜单启动（--linux-shortcut 会生成 .desktop）"
fi
echo "  ⚠️ 目标机器若无中文字体，托盘菜单中文会显示成方块（apt-get install fonts-noto-cjk）"
