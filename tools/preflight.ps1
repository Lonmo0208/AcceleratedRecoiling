# 部署前预检：用 NeoForge 开发服务端完整启动一次，把所有 mixin 真正施加一遍。
#
# 为什么需要它：mixin 的注入签名错误（例如目标有返回值却声明 CallbackInfo）只会在
# 类被加载、注入被施加的那一刻抛出，编译器与打包脚本都发现不了。已经因此让用户
# 撞过一次启动崩溃，所以把它做成部署流程里的固定一道闸。
#
# 终止方式只针对开发服务端：按命令行里的 DevLaunch 匹配 java 进程，不按镜像名杀，
# 避免误伤用户正在跑的游戏。

param(
    [int]$TimeoutSeconds = 240,
    [string]$Proj = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Continue"
$log = Join-Path $Proj "build\preflight.log"
if (Test-Path $log) { Remove-Item -Force $log }

$env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Java" -Directory |
    Where-Object { $_.Name -like "Graalvm-JDK-21*" } | Select-Object -First 1).FullName
if (-not $env:JAVA_HOME) { Write-Host "[FAIL] 找不到 JDK21"; exit 2 }

Write-Host "== 预检：启动开发服务端 ($TimeoutSeconds s 上限) =="

# 先记录「本次启动之前」已有的开发进程，结束时只杀新增的。
# 教训：早先按命令行里的 DevLaunch 匹配来杀，会连用户正在跑的开发客户端一起杀掉（已发生一次）。
$devLaunch = { Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like "*DevLaunch*" } |
    Select-Object -ExpandProperty ProcessId }
$preExisting = @(& $devLaunch)

# 端口被占是最常见的启动失败原因（隐藏 240 秒超时也看不出），先查一次，直接给结论。
# 端口取自 run/server.properties——本项目的开发服务端刻意用 25599 而不是默认的 25565，
# 因为用户可能同时开着别的模组项目的开发环境（实测就被 Lucis 项目的 dev 进程占过 25565）。
$port = 25599
$props = Join-Path $Proj "run\server.properties"
if (Test-Path $props) {
    $m = Select-String -Path $props -Pattern '^server-port=(\d+)' | Select-Object -First 1
    if ($m) { $port = [int]$m.Matches[0].Groups[1].Value }
}
$busy = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    Write-Host "  [FAIL] 端口 $port 已被占用，开发服务端起不来（占用进程 PID: $($busy.OwningProcess -join ', ')）" -ForegroundColor Red
    Write-Host "         这个端口是本项目开发服务端专用的；占用者很可能是别的项目的开发环境或遗留进程，"
    Write-Host "         请确认后自行关闭，或改 run\server.properties 里的 server-port。"
    exit 5
}

$proc = Start-Process -FilePath (Join-Path $Proj "gradlew.bat") `
    -ArgumentList "runServer", "--no-daemon" `
    -WorkingDirectory $Proj -PassThru -NoNewWindow `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$verdict = "timeout"
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 4
    if (-not (Test-Path $log)) { continue }
    $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
    if (-not $text) { continue }
    if ($text -match "MixinApplyError|InvalidInjectionException|InvalidDescriptor") { $verdict = "mixin-error"; break }
    if ($text -match 'Done \([\d\.]+s\)!') { $verdict = "ok"; break }
    # 启动期失败要立刻判定，否则会白等到超时（BindException / Failed to initialize server 都属此类）
    if ($text -match "Failed to start the minecraft server|FAILED TO BIND TO PORT|Failed to initialize server") {
        $verdict = "server-failed"; break
    }
}

# 只杀本次新增的开发进程；启动前就存在的（例如用户开着的客户端）一律不碰
$killed = @()
@(& $devLaunch) | Where-Object { $preExisting -notcontains $_ } | ForEach-Object {
    $killed += $_
    Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
}
if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
if (Test-Path "$log.err") { Remove-Item -Force "$log.err" -ErrorAction SilentlyContinue }

Write-Host "  已结束本次启动的开发服务端进程: $($killed -join ', ')（启动前已存在的进程未动）"
switch ($verdict) {
    "ok" {
        Write-Host "  [OK] 服务端完整启动，全部 mixin 注入通过"
        exit 0
    }
    "mixin-error" {
        Write-Host "  [FAIL] mixin 注入错误，部署已阻止："
        Select-String -Path $log -Pattern "MixinApplyError|InvalidInjectionException|InvalidDescriptor|Invalid descriptor" |
            Select-Object -First 5 | ForEach-Object { Write-Host "         $($_.Line.Trim())" }
        exit 1
    }
    "server-failed" {
        Write-Host "  [FAIL] 服务端启动失败（非注入原因）："
        Select-String -Path $log -Pattern "Failed to start the minecraft server" -Context 0,3 |
            Select-Object -First 1 | ForEach-Object { Write-Host "         $($_.Line.Trim())" }
        exit 3
    }
    default {
        Write-Host "  [WARN] $TimeoutSeconds 秒内既没起来也没报错，判不准，请人工看 build\preflight.log"
        exit 4
    }
}
