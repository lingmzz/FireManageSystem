[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)][int]$MySqlPort = 13306,
    [ValidateRange(1024, 65535)][int]$AppPort = 48080
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/Local-Tools.ps1')

$logs = Join-Path $PSScriptRoot '.local/logs'
$jar = Join-Path $PSScriptRoot 'backend/target/fire-manage.jar'
$homeUrl = "http://127.0.0.1:$AppPort/"

if (!(Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw '请先运行 Build-Local.ps1 构建项目。'
}
if ($AppPort -eq $MySqlPort) { throw '应用端口和 MySQL 端口必须不同。' }
$java = Join-Path (Get-LocalJavaHome $PSScriptRoot) 'bin/java.exe'

New-Item -ItemType Directory -Path $logs -Force | Out-Null
$lock = Enter-LocalLock (Join-Path $logs 'app-start.lock')
try {
    $alreadyRunning = Test-LocalPort $AppPort
    if ($alreadyRunning) {
        $pidFile = Join-Path $logs 'app.pid'
        $appId = 0
        if (!(Test-Path -LiteralPath $pidFile -PathType Leaf) -or
            ![int]::TryParse((Get-Content -LiteralPath $pidFile -TotalCount 1), [ref]$appId)) {
            throw "应用端口 $AppPort 已被占用。"
        }
        $listener = Get-NetTCPConnection -State Listen -LocalPort $AppPort -OwningProcess $appId -ErrorAction SilentlyContinue
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$appId"
        if (!$listener -or !$process.CommandLine -or !$process.CommandLine.Contains($jar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "应用端口 $AppPort 已被另一个进程占用。"
        }
    }

    & (Join-Path $PSScriptRoot 'scripts/Initialize-Database.ps1') -MySqlPort $MySqlPort
    if (!$alreadyRunning) {
        $arguments = @('-Dfile.encoding=UTF-8', '-jar', $jar,
            '--spring.config.additional-location=file:./.local/application-local.yaml', "--server.port=$AppPort")
        $app = Start-LocalProcess 'app' $java $arguments $PSScriptRoot $logs
        $ready = $false
        for ($attempt = 0; $attempt -lt 90; $attempt++) {
            if ($app.HasExited) { break }
            if (Test-LocalPort $AppPort) {
                try {
                    $response = Invoke-WebRequest -Uri $homeUrl -NoProxy -TimeoutSec 2
                    if ($response.StatusCode -eq 200) {
                        $ready = $true
                        break
                    }
                } catch {
                    # The web server may still be starting.
                }
            }
            Start-Sleep -Milliseconds 500
        }
        if (!$ready) { throw '应用未就绪，请查看 .local/logs/app.log 和 app-error.log。' }
    }

    Write-Output "消防管理系统 $homeUrl"
    Write-Output '首次登录信息见 .local/admin-credentials.txt。'
} finally {
    $lock.Dispose()
}
