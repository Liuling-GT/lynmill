<#
  TinyQuery Windows 打包脚本 —— 用 jpackage 生成免安装的 app-image。

  产物：jpackage-out\TinyQuery\
        必须发整个文件夹，单独把 TinyQuery.exe 拖出来跑不起来
        （启动器要靠同目录下的 app\TinyQuery.cfg 找 jar 和主类）。

  用法（在项目根目录执行）：
      pwsh -File packaging\package.ps1

  前置条件：
      1. 已执行 mvn clean package，target\ 下有 tinyquery-1.0-SNAPSHOT.jar
      2. 已装 JDK 17 —— jpackage 从 JDK 14 起随 JDK 一起提供，无需单独安装
#>

$ErrorActionPreference = 'Stop'

# 本脚本在 packaging\ 下，上一级就是项目根目录
$root = Split-Path -Parent $PSScriptRoot
$jar  = Join-Path $root 'target\tinyquery-1.0-SNAPSHOT.jar'
$icon = Join-Path $root 'packaging\tinyquery.ico'
$in   = Join-Path $root 'target\jpackage-input'
$dest = Join-Path $root 'jpackage-out'
$app  = Join-Path $dest 'TinyQuery'

# ---------- 1. 前置检查 ----------
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw '找不到 jpackage，请确认 JDK 17 已安装且在 PATH 里'
}
if (-not (Test-Path $jar)) {
    throw "找不到 $jar —— 请先执行 mvn clean package"
}
if (-not (Test-Path $icon)) {
    throw "找不到图标 $icon"
}

# ---------- 2. 准备干净的输入目录 ----------
# 不能直接 --input target：那会把 test-classes\ 等一起打进发布包。
# jpackage 只认目录，不认单个 jar，所以先把 jar 复制到一个只有它的目录。
if (Test-Path $in) { Remove-Item $in -Recurse -Force }
New-Item -ItemType Directory -Path $in | Out-Null
Copy-Item $jar $in

# ---------- 3. jpackage 不覆盖已存在的输出目录 ----------
# 输出目录已存在时它会直接报错退出，而不是覆盖，所以必须先删。
if (Test-Path $app) { Remove-Item $app -Recurse -Force }

# ---------- 4. 打包 ----------
# --main-jar 是必需的：不加会报「创建应用程序映像需要 --main-jar 或 --module」。
#   主类不用写死 —— 这个 jar 的 MANIFEST.MF 里已经有
#   Main-Class: org.springframework.boot.loader.launch.JarLauncher，
#   jpackage 会自己读出来。
# --icon 要求 .ico 内含 256x256 一张，packaging\tinyquery.ico 已内嵌 7 个尺寸。
Write-Host '正在打包...'
& jpackage `
    --type app-image `
    --input $in `
    --name TinyQuery `
    --main-jar 'tinyquery-1.0-SNAPSHOT.jar' `
    --icon $icon `
    --dest $dest
if ($LASTEXITCODE -ne 0) { throw "jpackage 失败，退出码 $LASTEXITCODE" }

# ---------- 5. 汇报 ----------
$exe  = Join-Path $app 'TinyQuery.exe'
$size = (Get-ChildItem $app -Recurse -File | Measure-Object Length -Sum).Sum
Write-Host ''
Write-Host "打包完成：$app"
Write-Host ("  总大小 {0:N1} MB（其中约 128 MB 是自带的 JRE）" -f ($size / 1MB))
Write-Host "  启动器 $exe"
