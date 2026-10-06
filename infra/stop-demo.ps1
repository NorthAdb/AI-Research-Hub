# 停止本机 Demo 的前端和后端。
# 加 -All 时再停 Redis、Kafka 和 PostgreSQL 容器（数据卷保留）。
# 不会动 3000 端口上的 Cursor。
param([switch]$All)

$ErrorActionPreference = "Continue"
$stopped = 0

$targets = Get-CimInstance Win32_Process | Where-Object {
    $_.CommandLine -and (
        $_.CommandLine -like "*ai-research-hub-backend-0.1.0.jar*" -or
        ($_.Name -eq "node.exe" -and $_.CommandLine -like "*ai-research-hub\frontend*") -or
        ($_.Name -eq "cmd.exe" -and $_.CommandLine -like "*arh-frontend*")
    )
}

foreach ($proc in $targets) {
    Write-Host "停止 $($proc.Name) pid=$($proc.ProcessId)"
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
    $stopped++
}

if ($All) {
    $extra = Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -and (
            $_.CommandLine -like "*E:\devtools\redis\redis-server.exe*" -or
            $_.CommandLine -like "*E:/devtools/kafka*" -or
            $_.CommandLine -like "*kafka.Kafka*"
        )
    }
    foreach ($proc in $extra) {
        Write-Host "停止 $($proc.Name) pid=$($proc.ProcessId)"
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
        $stopped++
    }
    & (Join-Path $PSScriptRoot "pg\stop-db.cmd")
}

if ($stopped -eq 0 -and -not $All) {
    Write-Host "没有发现正在运行的前端或后端。"
} else {
    Write-Host "应用进程已停止。"
}
if (-not $All) {
    Write-Host "PostgreSQL、Redis、Kafka 仍在运行。若要全部停下，请加 -All。"
}
