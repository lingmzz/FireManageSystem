[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)][int]$AppPort = 48080,
    [string]$DatabaseConfig
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/Local-Tools.ps1')

$logs = Join-Path $PSScriptRoot '.local/logs'
$jar = Join-Path $PSScriptRoot 'backend/target/fire-manage.jar'
$homeUrl = "http://127.0.0.1:$AppPort/"

if (!(Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw '请先运行 Build-Local.ps1 构建项目。'
}
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

    if (!$alreadyRunning) {
        if (!$DatabaseConfig) {
            if ($env:FIRE_DB_URL -notlike 'jdbc:oracle:thin:@*' -or
                [string]::IsNullOrWhiteSpace($env:FIRE_DB_USER) -or [string]::IsNullOrEmpty($env:FIRE_DB_PASSWORD)) {
                throw '请设置 Oracle 连接所需的 FIRE_DB_URL、FIRE_DB_USER 和 FIRE_DB_PASSWORD，或传入 -DatabaseConfig。'
            }
            $DatabaseConfig = Join-Path $PSScriptRoot 'config/application-external.example.yml'
        }
        if (!(Test-Path -LiteralPath $DatabaseConfig -PathType Leaf)) {
            throw "找不到数据库配置文件 $DatabaseConfig。"
        }
        $configUri = ([uri](Resolve-Path -LiteralPath $DatabaseConfig).Path).AbsoluteUri
        $arguments = @('-Dfile.encoding=UTF-8', '-jar', $jar,
            "--spring.config.additional-location=$configUri", "--server.port=$AppPort")
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
} finally {
    $lock.Dispose()
}
