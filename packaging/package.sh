#!/usr/bin/env bash
#
# TinyQuery macOS 打包脚本 —— 用 jpackage 生成 .app 与 .dmg。
#
# 产物：
#   jpackage-out/TinyQuery.app            ← 可直接双击的应用程序包
#   jpackage-out/TinyQuery-<版本>.dmg     ← 发给别人用的磁盘映像
#
# 用法（在项目根目录执行）：
#   bash packaging/package.sh
#
# 前置条件：
#   1. 已执行 mvn clean package，target/ 下有 tinyquery-1.0.0.jar
#   2. 已装 JDK 17 —— jpackage 从 JDK 14 起随 JDK 一起提供，无需单独安装
#   3. packaging/tinyquery.icns 存在（用 iconutil 从 iconset 生成）
#
# 为什么单独写 .sh 而不是改 package.ps1：jpackage 不能交叉编译，
# PowerShell 脚本在 Mac 上跑不了。两个脚本的应用标识必须保持一致。

set -euo pipefail

# ---------- 0. 应用标识 ----------
# 这些值必须与 pom.xml 的 <version> 保持一致。jpackage 的 --app-version
# 只接受「数字和点」，写 1.0-SNAPSHOT 会被直接拒绝，所以版本号已统一为 1.0.0。
appVersion='1.0.0'
# --vendor 会写进包的发布者字段。不写就是「未知」。
vendor='Liuling'
# macOS 的 bundle id。一旦发出去就改不动了（改了等于换了一个应用，
# 用户配置不跟随），所以现在就定死，与 Windows 端记录的值一致。
bundleId='com.liuling.tinyquery'

# 本脚本在 packaging/ 下，上一级就是项目根目录
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$root/target/tinyquery-$appVersion.jar"
icon="$root/packaging/tinyquery.icns"
in="$root/target/jpackage-input"
dest="$root/jpackage-out"
app="$dest/TinyQuery.app"
dmg="$dest/TinyQuery-$appVersion.dmg"

# ---------- 1. 前置检查 ----------
if ! command -v jpackage >/dev/null 2>&1; then
    echo '找不到 jpackage，请确认 JDK 17 已安装且在 PATH 里' >&2
    exit 1
fi
if [ ! -f "$jar" ]; then
    echo "找不到 $jar —— 请先执行 mvn clean package（版本号见 pom.xml，本脚本的 appVersion 必须与它一致）" >&2
    exit 1
fi
if [ ! -f "$icon" ]; then
    echo "找不到图标 $icon —— 请先用 iconutil 从 iconset 生成" >&2
    exit 1
fi

# ---------- 2. 准备干净的输入目录 ----------
# 不能直接 --input target：那会把 test-classes/ 等一起打进发布包。
# jpackage 只认目录、不认单个 jar，所以先把 jar 复制到一个只有它的目录。
rm -rf "$in"
mkdir -p "$in"
cp "$jar" "$in/"

# ---------- 3. jpackage 不覆盖已存在的输出 ----------
# 输出已存在时它会直接报错退出，而不是覆盖，所以必须先删。
rm -rf "$app"
rm -f "$dmg"

# ---------- 4. 打包 ----------
# --main-jar 是必需的：不加会报「创建应用程序映像需要 --main-jar 或 --module」。
#   主类不用写死 —— 这个 jar 的 MANIFEST.MF 里已经有
#   Main-Class: org.springframework.boot.loader.launch.JarLauncher，
#   jpackage 会自己读出来。
#
# --mac-package-identifier 是 macOS 专属选项。反过来，Windows 专属的选项
#   （如 --win-console、--win-shortcut）在 macOS 上传了会直接报「此选项在平台无效」
#   并以退出码 1 失败（不是「被忽略」）—— 所以两个平台的参数不能无脑共用。
#
# 先出 app-image 再出 dmg，而不是一步 --type dmg：因为 .dmg 里只有一个压缩过的
#   .app，验证「双击能不能起来」还得先挂载。让 jpackage 先把 .app 留在
#   jpackage-out 里，本地验证、看 Info.plist、量体积都方便得多。
echo '正在生成 .app ...'
jpackage \
    --type app-image \
    --input "$in" \
    --name TinyQuery \
    --main-jar "tinyquery-$appVersion.jar" \
    --app-version "$appVersion" \
    --vendor "$vendor" \
    --mac-package-identifier "$bundleId" \
    --icon "$icon" \
    --dest "$dest"

# ---------- 4.1 ad-hoc 签名（macOS 上必需，否则应用根本起不来）----------
# 不做这一步的话，产出的 .app 在 Apple Silicon 上**无法运行**：
# jpackage 把启动器从 JDK 里拷出来时保留了 Oracle 自带的签名，而那是「带资源
# 信封」的签名（CodeResources 只存在于 JDK 包内），拷出来就失效了 ——
# 结果只有启动器二进制带签名、外层 bundle 没有 _CodeSignature。实测后果：
#   ① codesign --verify 报
#      "code has no resources but signature indicates they must be present"
#   ② Apple Silicon 的内核拒绝执行签名无效的二进制
#   ③ 更糟：macOS 会把这个「已损坏」的 app 直接移走 —— 实测双击或直接运行后，
#      整个 TinyQuery.app 从磁盘上消失，jpackage-out 里只剩 .dmg
# 加上 --force --deep --sign -（ad-hoc，不需要开发者证书）之后就正常了。
#
# ⚠️ 这【不是】正式签名：ad-hoc 没有开发者身份、不能公证，
#    用户拿到 dmg 仍然会被 Gatekeeper 拦（右键 → 打开 可绕过）。
#    它只是让应用在本机跑得起来，从而让「双击验证」做得成。
#    另外 ad-hoc 签名每次重建都会变（CDHash 不同），这正是
#    「重新打包后钥匙串可能重新弹窗」的根源 —— 见 MacCredentialStore 的类注释。
echo '正在做 ad-hoc 签名 ...'
codesign --force --deep --sign - "$app"
codesign --verify --verbose=2 "$app" 2>&1 | sed 's/^/  /' || true

echo '正在生成 .dmg ...'
jpackage \
    --type dmg \
    --name TinyQuery \
    --app-image "$app" \
    --app-version "$appVersion" \
    --vendor "$vendor" \
    --mac-package-identifier "$bundleId" \
    --icon "$icon" \
    --dest "$dest"

# ---------- 5. 回报应用标识（不是只看「打包成功」）----------
# 只报「打包成功」等于没验证：--app-version 传错了、bundle id 没生效，
# 都要等发出去才发现。这里直接从产物里读回来核对。
cfg="$app/Contents/app/TinyQuery.cfg"
plist="$app/Contents/Info.plist"
echo ''
echo '产物内实际写入的应用标识：'
if [ -f "$cfg" ]; then
    grep -E 'jpackage\.app-version|jpackage\.app-name' "$cfg" | sed 's/^/  /' || true
fi
if [ -f "$plist" ]; then
    /usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$plist" 2>/dev/null \
        | sed 's/^/  CFBundleIdentifier        = /' || true
    /usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$plist" 2>/dev/null \
        | sed 's/^/  CFBundleShortVersionString= /' || true
    /usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$plist" 2>/dev/null \
        | sed 's/^/  CFBundleVersion           = /' || true
fi

# ---------- 6. 汇报 ----------
echo ''
echo "打包完成："
echo "  $app"
echo "  $dmg"
echo ''
echo "  .app 体积：$(du -sh "$app" | cut -f1)"
[ -f "$dmg" ] && echo "  .dmg 体积：$(du -sh "$dmg" | cut -f1)"
echo ''
echo "提示：本机未签名，首次打开会被 Gatekeeper 拦下（这是预期行为，不是打包失败）。"
echo "      临时绕过：右键 → 打开；或 xattr -dr com.apple.quarantine /Applications/TinyQuery.app"
