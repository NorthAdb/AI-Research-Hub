# 启动本机 Demo。默认不依赖 Kafka：文档在后端进程里异步处理。
# 用法:
#   powershell -ExecutionPolicy Bypass -File infra\start-demo.ps1
#   powershell -ExecutionPolicy Bypass -File infra\start-demo.ps1 -WithKafka
param([switch]$WithKafka)

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$RunDir = Join-Path $PSScriptRoot ".run"
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

function Test-Listening([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Get-ListenerProcess([int]$Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) { return $null }
    return Get-CimInstance Win32_Process -Filter "ProcessId=$($conn.OwningProcess)" -ErrorAction SilentlyContinue
}

function Wait-Http([string]$Url, [int]$Seconds) {
    for ($i = 0; $i -lt $Seconds; $i++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing $Url -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return $true }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    return $false
}

function Wait-Port([int]$Port, [int]$Seconds) {
    for ($i = 0; $i -lt $Seconds; $i++) {
        if (Test-Listening $Port) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

Write-Host "1/4  PostgreSQL"
& (Join-Path $PSScriptRoot "pg\start-db.cmd")
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 启动失败" }

Write-Host "2/4  Redis（问答缓存；没有它后端仍能回答，只是不缓存）"
if (Test-Listening 6379) {
    Write-Host "     6379 已在监听，跳过"
} elseif (Test-Path "E:\devtools\redis\redis-server.exe") {
    Start-Process -FilePath "E:\devtools\redis\redis-server.exe" `
        -ArgumentList "--port","6379","--save","","--appendonly","no" `
        -WindowStyle Hidden
    if (-not (Wait-Port 6379 15)) { throw "Redis 没有在 6379 监听" }
    Write-Host "     Redis 已启动"
} else {
    Write-Host "     未找到 E:\devtools\redis\redis-server.exe，缓存将自动降级"
}

$pipeline = "inline"
$kafkaHealth = "false"
Write-Host "3/4  后端（PIPELINE_MODE=$pipeline）"
if ($WithKafka) {
    $pipeline = "kafka"
    $kafkaHealth = "true"
    Write-Host "     同时启动 Kafka，文档处理改为消息队列"
    if (Test-Listening 9092) {
        Write-Host "     9092 已在监听，跳过 Kafka"
    } else {
        $bash = "C:\Program Files\Git\bin\bash.exe"
        if (-not (Test-Path $bash)) { throw "启动 Kafka 需要 Git Bash: $bash" }
        if (-not (Test-Path "E:\devtools\kafka\bin\kafka-server-start.sh")) {
            throw "未找到 E:\devtools\kafka"
        }
        Start-Process -FilePath $bash -ArgumentList "-lc","cd /e/devtools/kafka && bin/kafka-server-start.sh config/broker.properties" -WindowStyle Hidden
        if (-not (Wait-Port 9092 40)) { throw "Kafka 没有在 9092 监听" }
        Write-Host "     Kafka 已启动"
    }
}

if (Test-Listening 8080) {
    Write-Host "     8080 已在监听，跳过后端。若刚改过代码，请先运行 stop-demo.ps1 再启动。"
} else {
    $jar = Join-Path $Root "backend\target\ai-research-hub-backend-0.1.0.jar"
    if (-not (Test-Path $jar)) { throw "找不到 $jar 。请先在 backend 目录执行 mvn package" }
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
    $env:PIPELINE_MODE = $pipeline
    $env:KAFKA_HEALTH_ENABLED = $kafkaHealth
    $env:CORS_ALLOWED_ORIGINS = "http://localhost:3001,http://localhost:3002"
    $backendDir = Join-Path $Root "backend"
    Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
        -ArgumentList "-jar","$jar" `
        -WorkingDirectory $backendDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $RunDir "backend.out.log") `
        -RedirectStandardError (Join-Path $RunDir "backend.err.log")
    if (-not (Wait-Http "http://localhost:8080/api/health" 60)) {
        throw "后端没有在 60 秒内通过健康检查。日志: $RunDir\backend.err.log"
    }
    Write-Host "     后端已就绪 http://localhost:8080/api/health"
}

Write-Host "4/4  前端"
$frontendUrl = "http://localhost:3001"
$frontendPort = 3001
$frontendListener = Get-ListenerProcess 3001
$frontendIsOurs = $frontendListener -and $frontendListener.Name -eq "node.exe" -and $frontendListener.CommandLine -like "*ai-research-hub\frontend*"
if ($frontendIsOurs) {
    Write-Host "     3001 已是本项目前端，跳过"
} else {
    if ($frontendListener) {
        $frontendPort = 3002
        $taken = Get-ListenerProcess 3002
        if ($taken -and -not ($taken.Name -eq "node.exe" -and $taken.CommandLine -like "*ai-research-hub\frontend*")) {
            throw "3001 被 $($frontendListener.Name) 占用，3002 也被 $($taken.Name) 占用。请先空出一个端口。"
        }
        if ($taken) {
            Write-Host "     前端已在 3002，跳过"
            $frontendUrl = "http://localhost:3002"
            $frontendPort = 0
        } else {
            Write-Host "     3001 被 $($frontendListener.Name) 占用，改用 3002"
            $frontendUrl = "http://localhost:3002"
        }
    }
    if ($frontendPort -ne 0) {
        $frontendDir = Join-Path $Root "frontend"
        Start-Process -FilePath "cmd.exe" `
            -ArgumentList "/c","title arh-frontend & npx next dev -p $frontendPort" `
            -WorkingDirectory $frontendDir `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $RunDir "frontend.out.log") `
            -RedirectStandardError (Join-Path $RunDir "frontend.err.log")
        if (-not (Wait-Port $frontendPort 90)) {
            throw "前端没有在 $frontendPort 监听。日志: $RunDir\frontend.err.log"
        }
        Write-Host "     前端已就绪 http://localhost:$frontendPort"
        $frontendUrl = "http://localhost:$frontendPort"
    }
}

Write-Host ""
Write-Host "Demo 已启动。浏览器打开 $frontendUrl"
Write-Host "演示账号 demo@aireshub.dev / demo12345"
Write-Host "停止应用: powershell -ExecutionPolicy Bypass -File infra\stop-demo.ps1"
Write-Host "连同 Redis、Kafka、数据库一起停: 同上命令再加 -All"
