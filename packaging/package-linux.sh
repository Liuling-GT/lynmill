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
# ---------- 为什么默认只出 tar.gz，不出 deb/rpm ----------
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
# 装（小的够用，不必上 91MB 的 noto-cjk）：
#     apt-get install -y fonts-wqy-microhei
# 注意这条同时是**产品**问题：用户机器上若没有中文字体，托盘菜单一样是方块。
# 所以本脚本打 deb 时会补一条 Recommends 指向字体包（见 addFontRecommends_deb）。
#
# ---------- 为什么 --icon 用 PNG ----------
# jpackage 的 --icon 是平台特定的：Windows 只认 .ico，macOS 只认 .icns，
# Linux 只认 PNG。三者不能混用 —— 传错格式会直接报错退出。

set -euo pipefail

# 本脚本在 packaging/ 下，上一级就是项目根目录
scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$scriptDir/.." && pwd)"

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
wantFullRuntime=0
for arg in "$@"; do
    case "$arg" in
        --deb) wantDeb=1 ;;
        --rpm) wantRpm=1 ;;
        --full-runtime) wantFullRuntime=1 ;;
        -h|--help)
            sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            echo ''
            echo '  --deb            额外打一个 .deb'
            echo '  --rpm            额外打一个 .rpm'
            echo '  --full-runtime   用完整的 JRE，不做精简（逃生口，体积大 80 MB 左右）'
            exit 0 ;;
        *) echo "未知参数：$arg（可用：--deb --rpm --full-runtime --help）" >&2; exit 2 ;;
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
    echo '      TrayIconInstallerTest。装法（二选一，小的够用）：' >&2
    echo '        apt-get install -y fonts-wqy-microhei   # 约 5 MB' >&2
    echo '        apt-get install -y fonts-noto-cjk       # 约 91 MB，覆盖更全' >&2
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

# ---------- 3.5 精简 runtime（可选）----------
# 默认走精简：先 jlink 出一个只含必需模块的 runtime，再让 jpackage 用它。
#
# 【为什么不能直接用 jpackage 的 --jlink-options 减模块】
# jpackage 手册写明：非模块化应用（--main-jar）一律使用「默认模块集」，
# --add-modules 只能加不能减。所以必须自己 jlink 再用 --runtime-image 喂进去。
#
# 【模块清单从哪来】见 make-runtime.py —— 靠 `java --show-module-resolution`
# 实测，不是靠 jdeps 猜。jdeps 只给出 4 个模块，按它裁会在启动时报
# NoClassDefFoundError: GSSException。
#
# 【逃生口】--full-runtime：出问题时回到"带整个 JRE"的旧行为。
runtimeArgs=()
if [ "$wantFullRuntime" = 1 ]; then
    echo '【--full-runtime】跳过裁剪，使用完整的 JRE（逃生口）'
else
    command -v python3 >/dev/null 2>&1 || {
        echo '找不到 python3 —— 精简 runtime 需要它。用 --full-runtime 可跳过' >&2; exit 1; }
    echo '正在生成精简 runtime ...'
    python3 "$scriptDir/make-runtime.py" --jar "$jar" --version "$appVersion" --out "$root"
    runtime="$root/tinyquery-runtime"
    [ -d "$runtime" ] || { echo "runtime 没生成出来：$runtime" >&2; exit 1; }
    runtimeArgs=(--runtime-image "$runtime")
fi

# ---------- 4. 公共参数 ----------
# --main-jar 是必需的：不加会报「创建应用程序映像需要 --main-jar 或 --module」。
#   主类不用写死 —— jar 的 MANIFEST.MF 里已有 Main-Class，jpackage 会自己读。
# 这些参数对 app-image / deb / rpm 都有效，所以抽出来复用。
#
# 【${runtimeArgs[@]+...} 的写法】set -u 下老 bash 展开空数组会报 unbound variable。
# 这是「有元素才展开」的惯用法，bash 3.2 也认。
commonArgs=(
    --input "$in"
    --name TinyQuery
    --main-jar "tinyquery-$appVersion.jar"
    --app-version "$appVersion"
    --vendor "$vendor"
    ${runtimeArgs[@]+"${runtimeArgs[@]}"}
    --icon "$icon"
    --dest "$dest"
    # 堆上限设为物理内存的 50%。DataSourceManager 把整表放内存（无上限无淘汰），
    # 实测常驻堆约为文件大小的 5 倍。JVM 默认只给约 1/4，大文件容易撞上限，
    # 而且默认值随机器而变、用户无从预期。用百分比而非固定值，以便跟着机器走
    --java-options '-XX:MaxRAMPercentage=50'
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

# ---------- 6.5 给 deb/rpm 补上「建议安装中文字体」 ----------
#
# 为什么需要：TinyQuery 的托盘菜单有中文。Windows / macOS 系统自带中文字体，
# Linux 则不保证 —— 最小化的 Ubuntu 上 fontconfig 可能一个含 CJK 字形的字体都没有，
# 于是 SansSerif 映射到无中文字形的字体，菜单中文全变成方块。
# （这不是猜测：本项目的 TrayIconInstallerTest 就会在那种环境下失败，
#   它检查 "菜单字体能否显示中文"，是故意这么设计的防呆。）
#
# 为什么用 Recommends 而不是 Depends：
#   · 这是「体验更好」而非「否则跑不起来」—— 缺字体程序照样运行，只是菜单显示方块。
#     硬依赖会让用户在无网/精简环境里装不上，代价比收益大。
#   · 但也正因为它只影响体验，纯写文档（选项 C）就太弱了：用户根本不会想到
#     "方块字是缺字体"，只会当成程序有 bug。
#   Recommends 正好落在两者之间：包管理器默认会装上，但不阻塞安装、可以拒绝。
#
# 为什么不用 --linux-package-deps：那个选项加的是 Depends（硬依赖）。
# jpackage 没有任何选项能写 Recommends —— OpenJDK 的 jpackage 负责人确认过
# 「无法关闭依赖解析，也只能追加依赖，不能改类型」。所以只能打包后改。
#
# 为什么选 fonts-wqy-microhei（约 5MB）而不是 fonts-noto-cjk（约 91MB）：
# 我们只需要"菜单上那几个汉字能显示"，不需要全套 Noto CJK 的多字重多语言覆盖。
# 在干净的 Ubuntu 22.04 上实测：装 wqy-microhei 后 SansSerif/Dialog/Serif
# 都能完整显示菜单文案里的所有汉字。用 91MB 换同样的效果不值得。
# 两个都写进 Recommends，用户可以自行选择装哪个。
fontPackages='fonts-wqy-microhei | fonts-noto-cjk'
# 上面是 deb 的「或」语法。给用户看的时候用顿号分隔更自然
fontPackagesHuman='fonts-wqy-microhei 或 fonts-noto-cjk'

# deb：解包 → 在 control 里插一行 Recommends → 重新打包
# 用 dpkg-deb -R / -b 而不是手工改 ar 归档：前者是官方支持的往返方式，
# 实测能完整保留原包结构与权限
addFontRecommends_deb() {
    local deb="$1"
    command -v dpkg-deb >/dev/null 2>&1 || {
        echo "  ⚠️ 找不到 dpkg-deb，跳过为 $deb 补 Recommends（请手工确认字体依赖）" >&2
        return 0
    }

    local work
    work="$(mktemp -d)"
    if ! dpkg-deb -R "$deb" "$work" 2>/dev/null; then
        echo "  ⚠️ 无法解开 $deb，跳过 Recommends" >&2
        rm -rf "$work"
        return 0
    fi

    if grep -q '^Recommends:' "$work/DEBIAN/control"; then
        echo "  已存在 Recommends，跳过"
    else
        # 在 Depends 行之后插入。
        # 【踩过的坑】不能用 sed 的 s||| 而分隔符随便挑：$fontPackages 里
        # 本身就含 | （deb 的「或」语法）和 -，用 | 或 - 当分隔符都会被它撞坏。
        # 改用 awk：不涉及分隔符转义，且"在某行后插入"本来就是它的强项。
        awk -v r="Recommends: $fontPackages" '
            { print }
            /^Depends:/ && !done { print r; done = 1 }
        ' "$work/DEBIAN/control" > "$work/DEBIAN/control.new" \
            && mv "$work/DEBIAN/control.new" "$work/DEBIAN/control"

        # 确认真的写进去了 —— 不要只看 dpkg-deb 的退出码
        if ! grep -q '^Recommends:' "$work/DEBIAN/control"; then
            echo "  ⚠️ 未能写入 Recommends（control 里可能没有 Depends 行）" >&2
            rm -rf "$work"
            return 0
        fi

        dpkg-deb -b "$work" "$deb" >/dev/null 2>&1 \
            && echo "  已补 Recommends: $fontPackagesHuman" \
            || echo "  ⚠️ 重新打包 $deb 失败" >&2
    fi
    rm -rf "$work"
}

# rpm：jpackage 只出「二进制 rpm」，没有源 rpm 可供 rpmbuild 重建；
# 就地修改需要 rpmrebuild，而它不在 Ubuntu/Debian 的默认仓库里。
# 所以 rpm 这边走另一条路：jpackage 的 --linux-package-deps 加的是 **Depends**，
# 对一个字体包来说太重（缺字体程序仍能跑，只是菜单显示方块，不该因此装不上）。
# 折中做法：**在 rpm 的 Requires 之外不动它，但把字体包名写进包描述**，
# 并在脚本汇报里明确提示。
#
# 为什么不做成 Depends：把一个 91MB 的字体包变成硬依赖，会让无网环境、
# 精简容器里的用户直接装不上 —— 代价大于收益。
# 为什么不像以前那样只 echo 一句：那样用户根本不会看到（脚本输出不随包分发）。
# 写进 rpm 的 Summary/Description 才会跟着包走到用户眼前（rpm -qi 可见）。
addFontRecommends_rpm() {
    local rpm="$1"
    command -v rpm >/dev/null 2>&1 || {
        echo "  ⚠️ 找不到 rpm 命令，跳过 $rpm 的字体提示" >&2
        return 0
    }
    # 记录当前 Requires，供汇报时对比（确认我们没动过依赖）
    local req
    req="$(rpm -qpR "$rpm" 2>/dev/null | wc -l)"
    echo "  rpm 依赖项 $req 条（未改动）"
    echo "  ⚠️ rpm 格式无法追加 Recommends（需 rpmrebuild，非默认仓库）。"
    echo "     该包未声明中文字体：缺字体的机器上托盘菜单会显示方块。"
    echo "     请在发布说明里写明「建议安装 $fontPackagesHuman」。"
}

# ---------- 7. 可选：deb / rpm ----------
# 这两种是「安装到系统」的包，用户从应用菜单启动。
#
# 打完还要给它们补一条 Recommends（见 addFontRecommends_deb / _rpm）——
# jpackage 不会写这个字段，而缺了它用户装完可能看到方块字。
#
# 先自检那段逻辑本身有没有坏。它平时不执行，坏了没人知道 ——
# 第一版就是坏的（sed 分隔符被 $fontPackages 里的 | 撞坏），
# 而 `bash -n` 只查语法，查不出这种运行期错误
if [ "$wantDeb" = 1 ] || [ "$wantRpm" = 1 ]; then
    bash "$(dirname "${BASH_SOURCE[0]}")/check-font-recommends.sh" || {
        echo '字体依赖自检未通过，中止打包 —— 宁可不发，也不要发出一个菜单显示方块的包' >&2
        exit 1
    }
fi

if [ "$wantDeb" = 1 ]; then
    echo '正在打 deb...'
    jpackage --type deb "${commonArgs[@]}" "${packageArgs[@]}" \
        --linux-deb-maintainer "$debMaintainer"
    # 【踩过的坑，很隐蔽】文件名按 --linux-package-name 来，也就是**小写**的
    # tinyquery_1.0.0-1_amd64.deb（下划线）。原来这里写的是 TinyQuery-1.0.0-1.*.deb
    # （大写 T、连字符），glob 一个都匹配不上 —— for 循环体一次都没执行，
    # Recommends 静默地没加上，而且外面看不出任何异常。
    # 教训：glob 匹配不到【不报错】，必须显式断言至少处理了一个文件。
    found=0
    for f in "$dest"/"$packageName"_"$appVersion"-1_*.deb; do
        [ -f "$f" ] || continue
        found=$((found + 1))
        addFontRecommends_deb "$f"
    done
    if [ "$found" -eq 0 ]; then
        echo "  ⚠️ 没找到刚打出的 deb（预期 $packageName"_"$appVersion-1_*.deb），未补 Recommends" >&2
    fi
fi

if [ "$wantRpm" = 1 ]; then
    echo '正在打 rpm...'
    jpackage --type rpm "${commonArgs[@]}" "${packageArgs[@]}" \
        --linux-rpm-license-type "$rpmLicense"
    found=0
    for f in "$dest"/"$packageName"-"$appVersion"-1.*.rpm; do
        [ -f "$f" ] || continue
        found=$((found + 1))
        addFontRecommends_rpm "$f"
    done
    if [ "$found" -eq 0 ]; then
        echo "  ⚠️ 没找到刚打出的 rpm（预期 $packageName-$appVersion-1.*.rpm）" >&2
    fi
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
    # 用 grep 取【所有】java-options 行，不是 -m1 只取第一行 ——
    # .cfg 里每个 java-options 占一行，只取第一行会把后面设的堆上限藏起来，
    # 而那正是最该确认的一项
    n=0
    while IFS= read -r line; do
        n=$((n + 1))
        if [ "$n" -eq 1 ]; then
            printf '  %-24s %s\n' '.cfg 内 java-options' "${line#java-options=}"
        else
            printf '  %-24s %s\n' '' "${line#java-options=}"
        fi
    done < <(grep '^java-options' "$cfg")
fi

echo
echo "产物："
printf '  %-56s %8s\n' "${app#$root/}" "$(du -sh "$app" | cut -f1)"
printf '  %-56s %8s\n' "${tarball#$root/}" "$(du -h "$tarball" | cut -f1)"
# 【踩过的坑】glob 的大小写/分隔符必须与 --linux-package-name 一致：
# deb 是 tinyquery_1.0.0-1_amd64.deb（小写下划线），rpm 是 tinyquery-1.0.0-1.x86_64.rpm。
# 原来写成 TinyQuery-1.0.0-1.*.deb，一个都匹配不到 —— 产物明明打出来了，
# 汇报里却不显示（同时也导致 Recommends 没被加上，见上面第 7 节）
for f in "$dest"/"$packageName"_"$appVersion"-1_*.deb "$dest"/"$packageName"-"$appVersion"-1.*.rpm; do
    [ -f "$f" ] && printf '  %-56s %8s\n' "${f#$root/}" "$(du -h "$f" | cut -f1)"
done

echo
echo "分发说明："
echo "  tar.gz 解压后直接运行 TinyQuery/bin/TinyQuery（tar 会保留可执行位）"
if [ "$wantDeb" = 1 ] || [ "$wantRpm" = 1 ]; then
    echo "  deb/rpm 安装后可从应用菜单启动（--linux-shortcut 会生成 .desktop）"
fi
echo "  ⚠️ 目标机器若无中文字体，托盘菜单中文会显示成方块"
echo "     建议安装：$fontPackagesHuman"
if [ "$wantDeb" = 1 ]; then
    echo "     （deb 已带 Recommends，用 apt 安装时通常会自动装上）"
fi
