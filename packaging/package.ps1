# ---------- 参数 ----------
# -FullRuntime：逃生口。出问题时一条命令回到"带整个 JRE"的旧行为。
#
# 为什么要留这个：精简 runtime 是靠一份**手工维护的模块清单**（见 make-runtime.py）。
# 漏掉一个模块不会在打包时报错，而是在用户那里表现为
# 「双击了没反应」—— 那种故障最难排查。
# 真出了这种事，先用 -FullRuntime 出一个能跑的包救急，
# 再慢慢定位缺的是哪个模块。
#
# 【param 必须是文件的第一条语句】放在注释块之【后】也不行 ——
# PowerShell 会把它当成一个叫 param 的命令去找，报
# 「无法将"param"项识别为 cmdlet」。实测踩过，所以这里让 param 打头。
param(
    [switch]$FullRuntime
)

<#
  TinyQuery Windows 打包脚本 —— 用 jpackage 生成免安装的 app-image。

  ⚠️ 本文件必须以「UTF-8 带 BOM」保存，不要去掉 BOM。

  为什么：Windows PowerShell 5.1（系统自带的那个）读取 .ps1 时，
  **没有 BOM 就按系统 ANSI 代码页解码**（简体中文 Windows 上是 GBK）。
  本文件里全是中文注释，按 GBK 解出来是一堆乱码，其中的字符会破坏引号配对，
  于是整个脚本连解析都过不去，报「表达式或语句中包含意外的标记」。
  实测踩过：某次编辑把 BOM 去掉后，本脚本在 Windows 上完全无法运行。

  多数编辑器默认写「UTF-8 无 BOM」，所以这个问题会反复出现 ——
  改动本文件后请确认 BOM 还在：
      [System.IO.File]::ReadAllBytes($p)[0..2] 应为 239,187,191
  也可以直接跑 packaging\check-ps1-encoding.py 检查。

  产物：jpackage-out\TinyQuery\
        必须发整个文件夹，单独把 TinyQuery.exe 拖出来跑不起来
        （启动器要靠同目录下的 app\TinyQuery.cfg 找 jar 和主类）。

  用法（在项目根目录执行）：
      powershell -File packaging\package.ps1                # 精简 runtime（默认）
      powershell -File packaging\package.ps1 -FullRuntime   # 完整 JRE（逃生口）

  前置条件：
      1. 已执行 mvn clean package，target\ 下有 tinyquery-1.0.0.jar
      2. 已装 JDK 17 —— jpackage 从 JDK 14 起随 JDK 一起提供，无需单独安装
      3. 装了 python（精简 runtime 用）。没有就用 -FullRuntime 跳过
#>

$ErrorActionPreference = 'Stop'

# ---------- 0. 应用标识 ----------
# 这些值必须与 pom.xml 的 <version> 保持一致。jpackage 的 --app-version
# 只接受「数字和点」，写 1.0-SNAPSHOT 会被直接拒绝，所以版本号已统一为 1.0.0。
$appVersion = '1.0.0'
# --vendor 会写进 exe 属性的「发布者」/「公司」字段。不写就是「未知」。
$vendor     = 'Liuling'
# macOS 的 bundle id。Windows 上被忽略，但一旦发出去就改不动了
# （改了等于换了一个应用，用户配置不跟随），所以现在就定死。
$bundleId   = 'com.liuling.tinyquery'

# 本脚本在 packaging\ 下，上一级就是项目根目录
$root = Split-Path -Parent $PSScriptRoot
$jar  = Join-Path $root "target\tinyquery-$appVersion.jar"
$icon = Join-Path $root 'packaging\tinyquery.ico'
$in   = Join-Path $root 'target\jpackage-input'
$dest = Join-Path $root 'jpackage-out'
$app  = Join-Path $dest 'TinyQuery'
$runtime = Join-Path $root 'tinyquery-runtime'

# ---------- 1. 前置检查 ----------
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw '找不到 jpackage，请确认 JDK 17 已安装且在 PATH 里'
}
if (-not (Test-Path $jar)) {
    throw "找不到 $jar —— 请先执行 mvn clean package（版本号见 pom.xml，本脚本的 `$appVersion 必须与它一致）"
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

# ---------- 3. 清旧输出 ----------
# 放在第 4.5 步生成 runtime 之后（见那边注释）：jpackage 不覆盖已存在的
# 输出目录，会直接报「目标目录已存在」。而生成 runtime 不影响 jpackage-out，
# 所以集中到一处删，避免两处各删一次、其中一处漏掉
# （实测踩过：留了两处，后加的那处把顺序搞乱，打包直接失败）

# ---------- 4. 打包 ----------
# --main-jar 是必需的：不加会报「创建应用程序映像需要 --main-jar 或 --module」。
#   主类不用写死 —— 这个 jar 的 MANIFEST.MF 里已经有
#   Main-Class: org.springframework.boot.loader.launch.JarLauncher，
#   jpackage 会自己读出来。
# --icon 要求 .ico 内含 256x256 一张，packaging\tinyquery.ico 已内嵌 7 个尺寸。
# --app-version / --vendor：应用标识，决定 exe 属性里显示的版本与发布者。
#
# --java-options '-XX:MaxRAMPercentage=50'：把堆上限定成物理内存的 50%。
#   为什么需要：DataSourceManager 把整表放内存（无上限无淘汰），实测常驻堆约为
#   文件大小的 5 倍。不设 -Xmx 时 JVM 默认只给物理内存的约 1/4，大文件很容易撞上限；
#   而且「默认值」随机器而变，用户无从预期。
#   为什么用百分比而不是固定值（如 -Xmx4g）：固定值在 8GB 机器上太大、
#   在 64GB 机器上又太小。百分比会自动跟着机器走。
#   为什么是 50% 而不是更高：标签页、浏览器、其它程序也在用同一台机器，
#   留一半给它们，也免得 GC 压力过大。
#   注意：命令行里显式传的 --server.port 这类参数不受影响；
#   若用户想覆盖堆设置，可用 JAVA_TOOL_OPTIONS 或直接改 .cfg 里的 java-options。
#
# --mac-package-identifier 是 macOS 专属选项，在 Windows 上传了会直接报
# 「此选项在平台无效」并以退出码 1 失败（实测，不是「被忽略」）——
# 所以必须按平台条件拼接，不能无条件写死。
$platformArgs = @()
# 用 [Environment]::OSVersion 而不是 $IsMacOS：后者是 PowerShell 6+ 的自动变量，
# 在 Windows PowerShell 5.1 上不存在，会静默变成 $null 导致选项传不出去。
$isMac = ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::MacOSX)
if ($isMac) {
    $platformArgs += @('--mac-package-identifier', $bundleId)
}

# ---------- 4.5 精简 runtime ----------
# 默认走精简：先 jlink 出一个只含必需模块的 runtime，再让 jpackage 用它。
# 体积从约 176 MB 降到约 94 MB（runtime 128 → 47 MB）。
#
# 【为什么不能直接用 jpackage 的 --jlink-options 减模块】
# jpackage 手册写明：非模块化应用（--main-jar）一律使用「默认模块集」，
# --add-modules 只能加不能减。所以必须自己 jlink 再用 --runtime-image 喂进去。
#
# 【模块清单从哪来】见 make-runtime.py 的注释 —— 靠
# `java --show-module-resolution` 实测，不是靠 jdeps 猜。
# jdeps 只给出 4 个模块，按它裁会在启动时报 NoClassDefFoundError: GSSException。
$runtimeArgs = @()

# 先把还跑着的旧产物关掉。
# 【为什么必须显式做】Windows 上文件被占用时删除直接抛 WinError 5，
# 而占用者通常就是**上一次打包出来、被谁点开还没关的 TinyQuery**。
# 实测：连跑两次打包时第二次必然失败，报
# 「删不掉 ...TinyQuery.exe（WinError 5）」—— 光靠重试等不到它自己释放，
# 因为进程会一直活着。这里主动结束，把"再跑一次"变成真的可行。
# 放在生成 runtime 之前：那一步最后要删旧的 jpackage-out
$running = @(Get-Process TinyQuery -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    Write-Host ("关闭正在运行的旧 TinyQuery（{0} 个进程）" -f $running.Count)
    $running | Stop-Process -Force -ErrorAction SilentlyContinue
    # 等句柄真正释放。Tomcat 线程池与 AWT 事件线程的收尾不是瞬时的
    Start-Sleep -Seconds 3
}

if ($FullRuntime) {
    Write-Host '【-FullRuntime】跳过裁剪，使用完整的 JRE（逃生口）'
} else {
    $mk = Join-Path $PSScriptRoot 'make-runtime.py'
    $py = Get-Command python -ErrorAction SilentlyContinue
    if (-not $py) { $py = Get-Command python3 -ErrorAction SilentlyContinue }
    if (-not $py) {
        throw "找不到 python —— 精简 runtime 需要它。`n装一个，或者用 -FullRuntime 跳过裁剪（体积大 80 MB 左右）"
    }
    if (-not (Test-Path $mk)) { throw "找不到 $mk" }

    Write-Host '正在生成精简 runtime...'
    & $py.Source $mk --jar $jar --version $appVersion --out $root
    if ($LASTEXITCODE -ne 0) { throw "生成 runtime 失败，退出码 $LASTEXITCODE" }
    if (-not (Test-Path $runtime)) { throw "runtime 没生成出来：$runtime" }
    $runtimeArgs = @('--runtime-image', $runtime)
}

# 清掉旧的输出目录。
# 【必须放在生成 runtime 之【后】】jpackage 不覆盖已存在的输出目录，会直接报
# 「目标目录已存在」并退出。而 make-runtime.py 内部不会碰 jpackage-out，
# 所以清目录这一步只能在这里、且要在一切准备就绪之后做
if (Test-Path $app) { Remove-Item $app -Recurse -Force }

Write-Host '正在打包...'
& jpackage `
    --type app-image `
    --input $in `
    --name TinyQuery `
    --main-jar "tinyquery-$appVersion.jar" `
    --app-version $appVersion `
    --vendor $vendor `
    @platformArgs `
    @runtimeArgs `
    --icon $icon `
    --dest $dest `
    --java-options '-XX:MaxRAMPercentage=50'
if ($LASTEXITCODE -ne 0) { throw "jpackage 失败，退出码 $LASTEXITCODE" }

# ---------- 5. 回报应用标识（不是只看「打包成功」）----------
$cfgFile = Join-Path $app 'app\TinyQuery.cfg'
if (Test-Path $cfgFile) {
    $verLine = Select-String -Path $cfgFile -Pattern 'jpackage\.app-version' -SimpleMatch
    Write-Host ''
    Write-Host '应用标识：'
    Write-Host "  --name                 TinyQuery"
    Write-Host "  --app-version          $appVersion"
    Write-Host "  --vendor               $vendor"
    if ($isMac) {
        Write-Host "  --mac-package-identifier $bundleId"
    } else {
        Write-Host "  --mac-package-identifier $bundleId（仅 macOS 生效，本平台不传）"
    }
    if ($verLine) { Write-Host ("  .cfg 内实际写入： " + $verLine.Line.Trim()) }
}

# ---------- 6. 汇报 ----------
$exe  = Join-Path $app 'TinyQuery.exe'
$size = (Get-ChildItem $app -Recurse -File | Measure-Object Length -Sum).Sum
$rt   = Join-Path $app 'runtime'
$rtSize = if (Test-Path $rt) {
    (Get-ChildItem $rt -Recurse -File | Measure-Object Length -Sum).Sum
} else { 0 }
Write-Host ''
Write-Host "打包完成：$app"
# 分开报 runtime 与应用的大小 —— 只报一个总数的话，
# 看不出裁剪到底有没有生效（曾经这里写死「约 128 MB 是自带的 JRE」，
# 精简之后那行就成了错的）
Write-Host ("  总大小   {0:N1} MB" -f ($size / 1MB))
Write-Host ("  其中 runtime {0:N1} MB / 应用 {1:N1} MB" -f ($rtSize / 1MB), (($size - $rtSize) / 1MB))
Write-Host "  启动器 $exe"
if (-not $FullRuntime) {
    Write-Host ''
    Write-Host '  下一步：python packaging\verify-package.py'
    Write-Host '          改动过模块清单的话，这一步必须跑 —— 缺模块不会在打包时报错，'
    Write-Host '          而是运行时才炸，表现是「双击了没反应」'
}
