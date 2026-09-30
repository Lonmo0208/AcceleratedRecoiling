# 加速碰撞ECO —— 原生库 + Java + jar 打包 + 部署 一键管线
#
# 为什么需要这个脚本：
#   1. 手动打包步骤多且易漏（StripPreview、jocl、mods.toml、natives 覆盖），漏一步就是静默降级或启动崩溃。
#   2. MSVC 的 /GA 标志会为「TLS 在主 EXE 里」生成硬编码索引 0 的访问代码。本 DLL 是被 FFM 动态
#      加载的，索引 0 属于别的模块，任何 thread_local 都会读到垃圾指针并让 JVM 崩溃。
#      本脚本生成的 build.bat 不含 /GA，与 CMake 构建保持一致。
#   3. MSVC/zig 都需要 ASCII 路径，中文工程路径会出问题，所以先同步到 staging 目录再编译。
#
# 用法（PowerShell，无需参数）：& .\tools\build_all.ps1
#   可选开关：-SkipNative 跳过原生编译（只重打 Java/jar），-NoDeploy 只产出不部署，
#             -SkipPreflight 跳过启动预检（会快约一分钟，但注入签名错误就无法提前发现）。
#
# 预检说明：mixin 的注入签名错误只在类被加载、注入被施加的那一刻抛出，编译器与打包脚本
# 都发现不了——曾经因此让用户撞过一次启动崩溃。所以默认在部署前用开发服务端完整启动一次。

param(
    [switch]$SkipNative,
    [switch]$NoDeploy,
    [switch]$SkipPreflight,
    [switch]$NativeOnly
)

$ErrorActionPreference = "Continue"
# PowerShell 5.1 默认不加载 ZipFile，最后校验 jar 内容时要用到。
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---- 路径配置 ----
$Proj        = "E:\加速碰撞ECO\AcceleratedRecoiling-21.1.13-alpha"
$WinStage    = "C:\Users\Administrator\eco_win_msvc"
$LinuxStage  = "C:\Users\Administrator\eco_linux_src"
$JarStage    = "C:\Users\Administrator\eco_jar_tmp"
$Zig         = "C:\Users\Administrator\zig\zig-x86_64-windows-0.16.0\zig.exe"
$VcVars      = "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat"
$Jdk21       = "C:\Program Files\Java\Graalvm-JDK-21-vm"
$JarName     = "acceleratedrecoiling-EcoUpAdd-21.1.13-dev.jar"
$ModsDir     = "E:\awa8-21\.minecraft\versions\1.21.1-NeoForge\mods"

$EcoSources = @(
    "eco-native\src\blocks\block_scan.cpp",
    "eco-native\src\geometry\voxel_geometry.cpp",
    "eco-native\src\motion\movement_solver.cpp",
    "eco-native\src\motion\push_run.cpp",
    "eco-native\src\query\collision_rules.cpp",
    "eco-native\src\query\query_api.cpp",
    "eco-native\src\spatial\section_index.cpp",
    "eco-native\src\spatial\spatial_index.cpp",
    "eco-native\src\state\context_api.cpp",
    "eco-native\src\state\metadata_api.cpp",
    "eco-native\src\state\persistent_index.cpp"
)

# 必须全部导出，缺任何一个都会让对应路径静默降级。
$RequiredSymbols = @(
    'putCollisionEntity', 'removeCollisionEntity', 'updateCollisionLocation', 'scanCollisionBlocks',
    'createCollisionContext', 'destroyCollisionContext', 'setCollisionGridSize', 'beginCollisionFrame',
    'addCollisionEntity', 'updateCollisionEntity', 'invalidateEntityPushabilityCache',
    'invalidatePushEligibilityFields', 'queryCollisionEntities', 'queryHardCollisionEntities',
    'queryEntitiesInBox', 'queryPushableEntities', 'executePushRun', 'executeFullPushRun',
    'prepareGpuPush', 'applyGpuPush', 'verifyGpuPush',
    'solveMovement', 'prepareMovement', 'createCtx', 'destroyCtx', 'createCfg', 'updateCfg',
    'destroyCfg', 'push'
)

function Step($text) { Write-Host ""; Write-Host "=== $text ===" -ForegroundColor Cyan }
function Ok($text)   { Write-Host "  [OK] $text" -ForegroundColor Green }
function Fail($text) { Write-Host "  [FAIL] $text" -ForegroundColor Red; exit 1 }

function Sync-Sources($dest) {
    if (Test-Path "$dest\eco-native") { Remove-Item -Recurse -Force "$dest\eco-native" }
    New-Item -ItemType Directory -Force -Path "$dest\eco-native" | Out-Null
    Copy-Item -Recurse "$Proj\eco-native\include" "$dest\eco-native\"
    Copy-Item -Recurse "$Proj\eco-native\src"     "$dest\eco-native\"
    Copy-Item "$Proj\eco-native\version-script"   "$dest\eco-native\"
    Copy-Item "$Proj\acceleratedRecoilingLib.cpp" "$dest\"
}

function Assert-Symbols($dll, $label) {
    $bytes = [IO.File]::ReadAllBytes($dll)
    $text = [Text.Encoding]::ASCII.GetString($bytes)
    $missing = @($RequiredSymbols | Where-Object { -not $text.Contains($_) })
    if ($missing.Count -gt 0) { Fail "$label 缺少导出符号: $($missing -join ', ')" }
    Ok "$label $($bytes.Length) 字节，$($RequiredSymbols.Count) 个符号齐全"
}

# ---- 1. Windows DLL ----
if (-not $SkipNative) {
    Step "1/6 编译 Windows DLL (MSVC)"
    Sync-Sources $WinStage

    # build.bat 由脚本生成（单一事实来源）。逐条 cl 独立一行：拼成一条会超过 8191 字符上限。
    $bat = @()
    $bat += '@echo off'
    $bat += "call `"$VcVars`""
    $bat += 'set COMMON=/nologo /O2 /std:c++20 /EHsc /utf-8 /D_CRT_SECURE_NO_WARNINGS /DAR_WINDOWS /DAR_X64 /D_AMD64_ /DECO_VANILLA_ORDER=1 /I stub /I eco-native\include /I eco-native\src'
    $objs = @()
    $bat += 'cl %COMMON% /arch:AVX2 /c acceleratedRecoilingLib.cpp /Fobuild\ar.obj'
    $objs += 'build\ar.obj'
    $n = 0
    foreach ($src in $EcoSources) {
        $obj = "build\eco_$n.obj"
        $bat += "cl %COMMON% /arch:AVX2 /c $src /Fo$obj"
        $objs += $obj
        $n++
    }
    $bat += "link /DLL /NOLOGO /OUT:build\AcceleratedRecoiling.dll $($objs -join ' ')"
    Set-Content -Path "$WinStage\build.bat" -Value ($bat -join "`r`n") -Encoding ASCII

    Push-Location $WinStage
    # 必须用 PowerShell 直接调用：cmd /c 在本机被安全策略拦截。
    $out = & .\build.bat 2>&1
    Pop-Location
    if (-not (Test-Path "$WinStage\build\AcceleratedRecoiling.dll")) {
        $out | Select-Object -Last 25 | ForEach-Object { Write-Host "    $_" }
        Fail "MSVC 构建未产出 DLL"
    }
    Copy-Item "$WinStage\build\AcceleratedRecoiling.dll" "$Proj\AcceleratedRecoiling-third-party\out\win-x64\AcceleratedRecoiling.dll" -Force
    Assert-Symbols "$Proj\AcceleratedRecoiling-third-party\out\win-x64\AcceleratedRecoiling.dll" "Windows DLL"

    # ---- 2. Linux .so ----
    Step "2/6 编译 Linux .so (zig 交叉编译)"
    Sync-Sources $LinuxStage
    $env:ZIG_LOCAL_CACHE_DIR  = "C:\Users\Administrator\zig\cache"
    $env:ZIG_GLOBAL_CACHE_DIR = "C:\Users\Administrator\zig\gcache"
    # -ffp-contract=off 不能省：clang 默认允许浮点融合，会改掉原版逐项除法/乘法的运算顺序。
    # 不加 version-script，保持与旧 .so 一致的全量导出，避免漏掉 AR 侧符号。
    $flags = @(
        "-target", "x86_64-linux-gnu", "-shared", "-o", "out\AcceleratedRecoiling.so",
        "-O2", "-std=c++20", "-fexceptions", "-fPIC", "-pthread", "-mavx2", "-ffp-contract=off",
        "-Wl,--no-undefined", "-D_CRT_SECURE_NO_WARNINGS", "-DAR_X64", "-D_AMD64_",
        "-DECO_VANILLA_ORDER=1", "-I", "stub", "-I", "eco-native\include", "-I", "eco-native\src"
    )
    $srcs = @("acceleratedRecoilingLib.cpp") + $EcoSources
    Push-Location $LinuxStage
    New-Item -ItemType Directory -Force -Path "out" | Out-Null
    $zigOut = & $Zig c++ @flags @srcs 2>&1
    $zigCode = $LASTEXITCODE
    $zigOut | Out-File -Encoding utf8 "link.log"
    Pop-Location
    if ($zigCode -ne 0) { $zigOut | Select-Object -Last 25 | ForEach-Object { Write-Host "    $_" }; Fail "zig 交叉编译失败 (exit=$zigCode)" }
    Copy-Item "$LinuxStage\out\AcceleratedRecoiling.so" "$Proj\AcceleratedRecoiling-third-party\out\linux-x64\AcceleratedRecoiling.so" -Force
    Assert-Symbols "$Proj\AcceleratedRecoiling-third-party\out\linux-x64\AcceleratedRecoiling.so" "Linux .so"
} else {
    Step "1-2/6 跳过原生编译"
}

if ($NativeOnly) {
    Step "只编译原生：跳过 Java/打包/部署"
    exit 0
}

# ---- 3. Java ----
Step "3/6 编译 Java (JDK21 预览特性)"
$env:JAVA_HOME = $Jdk21
Push-Location $Proj
$javaOut = & "$Proj\gradlew.bat" compileJava --no-daemon 2>&1
$javaCode = $LASTEXITCODE
Pop-Location
$javaOut | Out-File -Encoding utf8 "$Proj\build_compile.log"
if ($javaCode -ne 0) { $javaOut | Select-Object -Last 30 | ForEach-Object { Write-Host "    $_" }; Fail "gradle compileJava 失败" }
if (-not (Test-Path "$Proj\build\classes\java\main\com\wiyuka\acceleratedrecoiling\engine\EcoFrame.class")) { Fail "找不到编译产物" }
Ok "class 输出就绪"

# ---- 4. 打包 ----
Step "4/6 打包 jar"
$distJar = "$Proj\dist\$JarName"
if (-not (Test-Path $distJar)) { Fail "找不到基准 dist jar：$distJar（打包需要它提供已验证的 META-INF 与资源）" }
$pkg = "$JarStage\pkg"
if (Test-Path $pkg) { Remove-Item -Recurse -Force $pkg }
New-Item -ItemType Directory -Force -Path $pkg | Out-Null
$rawJar = "$JarStage\raw.jar"
if (Test-Path $rawJar) { Remove-Item -Force $rawJar }

Copy-Item $distJar "$Proj\dist\$JarName.prev" -Force
Push-Location $pkg
& "$Jdk21\bin\jar.exe" xf $distJar
if ($LASTEXITCODE -ne 0) { Pop-Location; Fail "解包基准 jar 失败" }
Copy-Item -Recurse -Force "$Proj\build\classes\java\main\com" "$pkg\"
# jocl 的原生桥。合并时只把 org/jocl 的 class 平铺进根目录，嵌套 jar 里的 lib/ 丢了，
# 于是 GPU 后端一调用就 UnsatisfiedLinkError（被 tryLoad 吞掉，表现为「GPU 不可用」）。
# jocl 的 LibUtils 是按 classpath 资源 /lib/<name> 找原生库的，所以把 lib/ 放到 jar 根即可。
$joclJar = "$Proj\tools\jocl-2.0.6.jar"
if (Test-Path $joclJar) {
    Push-Location $pkg
    & "$Jdk21\bin\jar.exe" xf $joclJar lib
    $joclCode = $LASTEXITCODE
    Pop-Location
    if ($joclCode -ne 0) { Fail "解出 jocl 原生库失败" }
} else {
    Write-Host "  [警告] 找不到 $joclJar，GPU 后端将不可用" -ForegroundColor Yellow
}
# mixin 注册表必须以源码为准。基准 jar 里带的是旧版本：只替换 class 而不替换它，
# 新增的 mixin 会静默不生效——class 明明在 jar 里，注册表里却没有名字。
Copy-Item -Force "$Proj\src\main\resources\acceleratedrecoiling.mixins.json" "$pkg\acceleratedrecoiling.mixins.json"
Copy-Item -Force "$Proj\AcceleratedRecoiling-third-party\out\win-x64\AcceleratedRecoiling.dll" "$pkg\natives\windows-x64\"
Copy-Item -Force "$Proj\AcceleratedRecoiling-third-party\out\linux-x64\AcceleratedRecoiling.so" "$pkg\natives\linux-x64\"
& "$Jdk21\bin\jar.exe" cfm $rawJar META-INF/MANIFEST.MF .
$jarCode = $LASTEXITCODE
Pop-Location
if ($jarCode -ne 0) { Fail "jar 打包失败" }

# 必须 StripPreview：游戏跑 Java 27，预览字节码的小版本号 0xFFFF 会让类加载失败。
# 数量本身不是不变式（新增一个用预览特性编译的类就会 +1），真正要守的是
# 「输出 jar 里没有任何 class 还带着 0xFFFF」，所以这里扫一遍产物。
$stripOut = & "$Jdk21\bin\java.exe" -cp "$Proj\dist\strip" StripPreview $rawJar $distJar 2>&1
Write-Host "    $stripOut"
if ($stripOut -notmatch "stripped=(\d+)") { Fail "StripPreview 结果无法解析：$stripOut" }
$strippedCount = [int]$Matches[1]
if ($strippedCount -lt 8) { Fail "剥离数量异常（预期至少 8）：$stripOut" }
$zc = [IO.Compression.ZipFile]::OpenRead($distJar)
$leftover = 0
foreach ($e in $zc.Entries) {
    if (-not $e.FullName.EndsWith('.class')) { continue }
    $st = $e.Open()
    $head = New-Object byte[] 8
    [void]$st.Read($head, 0, 8)
    $st.Close()
    if ($head[4] -eq 0xFF -and $head[5] -eq 0xFF) { $leftover++ }
}
$zc.Dispose()
if ($leftover -gt 0) { Fail "还有 $leftover 个 class 带预览标记，Java 27 下会类加载失败" }
Ok "jar 已生成 $((Get-Item $distJar).Length) 字节，stripped=$strippedCount，无残留预览标记"

# ---- 5. 部署 ----
if ($NoDeploy) {
    Step "5-6/6 跳过部署"
} else {
    if (-not $SkipPreflight) {
        Step "4.5/6 启动预检（完整起一次服务端，验证 mixin 注入）"
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$Proj\tools\preflight.ps1" -Proj $Proj
        $preflightCode = $LASTEXITCODE
        if ($preflightCode -ne 0) {
            Fail "启动预检失败（退出码 $preflightCode），已阻止部署。日志：$Proj\build\preflight.log"
        }
        Ok "启动预检通过，全部 mixin 注入有效"
    } else {
        Step "4.5/6 已跳过启动预检（-SkipPreflight）"
    }
    Step "5/6 部署到 mods"
    # 同 modId 共存会让 ModLauncher 在启动期直接崩（Duplicate mod）。以前只警告、让人手动改名，
    # 结果用户连续撞了两次；现在自动把同 modId 的兄弟文件改成 .disabled。
    # 可逆，且是本项目既有约定；改名后第二次运行不会重复处理。
    $other = @(Get-ChildItem -Path $ModsDir -Filter "*.jar" | Where-Object { $_.Name -ne $JarName -and $_.Name -match "recoiling" })
    foreach ($stale in $other) {
        $target = "$($stale.FullName).disabled"
        # 这里曾经静默失败过：文件名带中文时 Move-Item 报错被 $ErrorActionPreference=Continue 吞掉，
        # 于是打印了「已自动禁用」但文件还在原地——下一次启动就是 Duplicate mod 崩溃。
        # 所以改完必须回读确认，不确认就等于没做。
        try {
            Move-Item -Force -LiteralPath $stale.FullName -Destination $target -ErrorAction Stop
        } catch {
            Fail "无法禁用同 modId 的旧 jar（会导致启动崩溃）：$($stale.Name) — $($_.Exception.Message)"
        }
        if (Test-Path -LiteralPath $stale.FullName) {
            Fail "同 modId 的旧 jar 仍然存在，改名没生效：$($stale.Name)"
        }
        Write-Host "  [自动禁用] $($stale.Name) -> $($stale.Name).disabled（同 modId 会启动崩溃）" -ForegroundColor Yellow
    }
    $leftover = @(Get-ChildItem -Path $ModsDir -Filter "*.jar" | Where-Object { $_.Name -ne $JarName -and $_.Name -match "recoiling" })
    if ($leftover.Count -gt 0) {
        Fail "mods 目录仍有同 modId 的 jar：$($leftover.Name -join ', ')"
    }
    Copy-Item $distJar "$ModsDir\$JarName" -Force
    Ok "已部署 $((Get-Item "$ModsDir\$JarName").Length) 字节"

    Step "6/6 校验部署产物"
    $z = [IO.Compression.ZipFile]::OpenRead("$ModsDir\$JarName")
    foreach ($entryName in @('natives/windows-x64/AcceleratedRecoiling.dll', 'natives/linux-x64/AcceleratedRecoiling.so')) {
        $e = $z.Entries | Where-Object { $_.FullName -eq $entryName }
        if (-not $e) { $z.Dispose(); Fail "jar 内缺少 $entryName" }
        $ms = New-Object IO.MemoryStream; $s = $e.Open(); $s.CopyTo($ms); $s.Close()
        $text = [Text.Encoding]::ASCII.GetString($ms.ToArray())
        if (-not $text.Contains('executeFullPushRun')) { $z.Dispose(); Fail "$entryName 缺 executeFullPushRun" }
    }
    # 反向校验：jar 里每个 mixin 类都必须在注册表里出现，否则它不会生效而且不会有任何报错
    $jsonEntry = $z.Entries | Where-Object { $_.FullName -eq 'acceleratedrecoiling.mixins.json' }
    if (-not $jsonEntry) { $z.Dispose(); Fail "jar 内缺少 acceleratedrecoiling.mixins.json" }
    $ms2 = New-Object IO.MemoryStream; $s2 = $jsonEntry.Open(); $s2.CopyTo($ms2); $s2.Close()
    $registry = [Text.Encoding]::UTF8.GetString($ms2.ToArray())
    $mixinClasses = @($z.Entries | Where-Object { $_.FullName -match '^com/wiyuka/acceleratedrecoiling/mixin/[^/]+\.class$' } | ForEach-Object { ($_.FullName -split '/')[-1] -replace '\.class$','' })
    $missing = @($mixinClasses | Where-Object { $registry -notmatch [regex]::Escape('"' + $_ + '"') })
    $z.Dispose()
    if ($missing.Count -gt 0) { Fail "以下 mixin 类没有写进注册表，会被静默忽略：$($missing -join ', ')" }
    Ok "jar 内 $($mixinClasses.Count) 个 mixin 全部已在注册表中"
}

Write-Host ""
Write-Host "完成。回滚备份：dist\$JarName.prev" -ForegroundColor Green
