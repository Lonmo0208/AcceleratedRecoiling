# 一键起 Dev 测试环境。
#
# 为什么用它：Dev 环境直接跑 build/classes（改完代码只要 compileJava，不必打包→StripPreview→部署），
# 而且 mods 目录是干净的（只有本模组 + NeoForge），拿到的性能读数不受其它模组干扰。
#
# 用法：
#   & .\tools\run_dev.ps1                 # 起开发客户端（有窗口，自己操作）
#   & .\tools\run_dev.ps1 -Mode Server    # 起开发服务端（无窗口，适合只看日志）
#   & .\tools\run_dev.ps1 -SkipCompile    # 跳过编译直接起（改过 Java 就别跳）
#
# 注意：
#   - 客户端运行目录是 run-client/（与预检用的 run/ 分开，互不抢世界与端口）
#   - 配置文件在运行目录下的 acceleratedRecoiling.json；/check 里的 CONFIG_FILE 就是它
#   - 原生库与 jocl 的 lib/ 由 build.gradle 的 processResources 拷进 build/resources/main，
#     所以本脚本必须先跑一次 processResources（编译步骤里已包含）

param(
    [ValidateSet("Client", "Server")]
    [string]$Mode = "Client",
    [switch]$SkipCompile,
    [int]$TimeoutSeconds = 0
)

$ErrorActionPreference = "Continue"
$Proj = (Resolve-Path "$PSScriptRoot\..").Path
$env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Java" -Directory |
    Where-Object { $_.Name -like "Graalvm-JDK-21*" } | Select-Object -First 1).FullName
if (-not $env:JAVA_HOME) { Write-Host "[FAIL] 找不到 JDK21（Graalvm-JDK-21*）"; exit 2 }

Push-Location $Proj
try {
    if (-not $SkipCompile) {
        Write-Host "== 编译 + 资源（含原生库与 jocl lib/）=="
        & "$Proj\gradlew.bat" compileJava processResources --no-daemon
        if ($LASTEXITCODE -ne 0) { Write-Host "[FAIL] 编译失败，修正后再起"; exit 1 }
    }

    # 起来之前先确认原生库真的在开发资源里，否则引擎会静默降级 PARITY 而看不出来
    $dll = "$Proj\build\resources\main\natives\windows-x64\AcceleratedRecoiling.dll"
    $jocl = "$Proj\build\resources\main\lib\JOCL_2_0_6-windows-x86_64.dll"
    if (Test-Path $dll) { Write-Host "  [OK] 开发资源内含原生库: $((Get-Item $dll).Length) 字节" }
    else { Write-Host "  [警告] 开发资源里没有原生库，引擎会降级为 PARITY" -ForegroundColor Yellow }
    if (Test-Path $jocl) { Write-Host "  [OK] 开发资源内含 jocl 原生桥（kernelMode=GPU 可用）" }
    else { Write-Host "  [警告] 开发资源里没有 jocl 原生桥，GPU 档不可用" -ForegroundColor Yellow }

    $task = if ($Mode -eq "Server") { "runServer" } else { "runClient" }
    Write-Host "== 启动开发$Mode (gradlew $task) =="
    if ($Mode -eq "Server" -and $TimeoutSeconds -gt 0) {
        # 无窗口模式可以限时，便于脚本化验证。只杀「本次新增」的开发进程，
        # 免得连用户自己开着的客户端一起干掉（这个坑已经踩过一次）。
        $devLaunch = { Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine -like "*DevLaunch*" } |
            Select-Object -ExpandProperty ProcessId }
        $preExisting = @(& $devLaunch)
        $proc = Start-Process -FilePath "$Proj\gradlew.bat" -ArgumentList $task, "--no-daemon" `
            -WorkingDirectory $Proj -PassThru -NoNewWindow
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        while ((Get-Date) -lt $deadline -and -not $proc.HasExited) { Start-Sleep -Seconds 2 }
        if (-not $proc.HasExited) {
            @(& $devLaunch) | Where-Object { $preExisting -notcontains $_ } | ForEach-Object {
                Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
            }
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            Write-Host "  已到时结束本次开发服务端（启动前已存在的进程未动；日志: $Proj\run\logs\latest.log）"
        }
    } else {
        & "$Proj\gradlew.bat" $task --no-daemon
    }
} finally {
    Pop-Location
}
