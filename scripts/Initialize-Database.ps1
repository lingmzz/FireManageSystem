[CmdletBinding()]
param([ValidateRange(1024, 65535)][int]$MySqlPort = 13306)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Local-Tools.ps1')

$root = Split-Path -Parent $PSScriptRoot
$local = Join-Path $root '.local'
$data = Join-Path $local 'mysql-data'
$logs = Join-Path $local 'logs'
$myIni = Join-Path $local 'mysql.ini'
$adminIni = Join-Path $local 'mysql-admin.cnf'
$appIni = Join-Path $local 'mysql-fire.cnf'
$bootSql = Join-Path $local 'mysql-bootstrap.sql'
$backendConfig = Join-Path $local 'application-local.yaml'

$mysqlHome = $env:FIRE_MYSQL_HOME
if (!$mysqlHome -and (Test-Path -LiteralPath $myIni -PathType Leaf)) {
    $basedir = Select-String -LiteralPath $myIni -Pattern '^\s*basedir\s*=\s*(.+)$' | Select-Object -First 1
    if ($basedir) { $mysqlHome = $basedir.Matches[0].Groups[1].Value.Trim().Trim('"') }
}
if (!$mysqlHome) {
    $server = Get-Command mysqld.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($server) { $mysqlHome = Split-Path -Parent (Split-Path -Parent $server.Source) }
}
if (!$mysqlHome) { throw '请安装 MySQL 8.0，并将 FIRE_MYSQL_HOME 设置到 MySQL 安装目录。' }

$mysqlHome = [IO.Path]::GetFullPath($mysqlHome)
$mysqlServer = Join-Path $mysqlHome 'bin/mysqld.exe'
$mysqlClient = Join-Path $mysqlHome 'bin/mysql.exe'
if (!(Test-Path -LiteralPath $mysqlServer -PathType Leaf) -or !(Test-Path -LiteralPath $mysqlClient -PathType Leaf)) {
    throw "未找到 MySQL 8.0 程序，请检查 $mysqlHome 下的 bin 目录。"
}
$version = & $mysqlServer --version
if ($LASTEXITCODE -ne 0 -or $version -notmatch '\bVer 8\.0\.\d+\b') {
    throw '本地数据库脚本需要 MySQL 8.0 的 Windows 版本。'
}

function Test-ProjectDatabase {
    & $mysqlClient "--defaults-file=$appIni" "--port=$MySqlPort" -D fire_manage --connect-timeout=2 '--execute=SELECT 1' 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

New-Item -ItemType Directory -Path $logs -Force | Out-Null
$lock = Enter-LocalLock (Join-Path $logs 'mysql-start.lock')
try {
    if (!(Test-Path -LiteralPath $myIni)) {
        if (Test-Path -LiteralPath $data) {
            throw '已有 .local/mysql-data，但缺少 mysql.ini。请恢复原配置后再启动。'
        }
        if (Test-LocalPort $MySqlPort) { throw "MySQL 端口 $MySqlPort 已被占用。" }

        $rootPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        $appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        $initialPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(12))

        @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';
CREATE DATABASE IF NOT EXISTS fire_manage CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'fire_manage'@'127.0.0.1' IDENTIFIED BY '$appPassword';
GRANT ALL PRIVILEGES ON fire_manage.* TO 'fire_manage'@'127.0.0.1';
"@ | Set-Content -LiteralPath $bootSql -Encoding utf8NoBOM

        @"
[client]
user=root
password=$rootPassword
host=127.0.0.1
port=$MySqlPort
protocol=tcp
"@ | Set-Content -LiteralPath $adminIni -Encoding utf8NoBOM
        @"
[client]
user=fire_manage
password=$appPassword
host=127.0.0.1
port=$MySqlPort
protocol=tcp
default-character-set=utf8mb4
"@ | Set-Content -LiteralPath $appIni -Encoding utf8NoBOM

        @"
spring:
  datasource:
    url: 'jdbc:mysql://127.0.0.1:$MySqlPort/fire_manage?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&sslMode=DISABLED'
    username: fire_manage
    password: '$appPassword'
fire:
  bootstrap-password: '$initialPassword'
"@ | Set-Content -LiteralPath $backendConfig -Encoding utf8NoBOM
        @"
用户名 admin
初始密码 $initialPassword
"@ | Set-Content -LiteralPath (Join-Path $local 'admin-credentials.txt') -Encoding utf8NoBOM

        @"
[mysqld]
basedir="$($mysqlHome.Replace('\', '/'))"
datadir="$($data.Replace('\', '/'))"
port=$MySqlPort
bind-address=127.0.0.1
mysqlx=OFF
skip-log-bin
lower_case_table_names=1
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
"@ | Set-Content -LiteralPath $myIni -Encoding utf8NoBOM
    }

    if (!(Select-String -LiteralPath $myIni -Pattern "^\s*port\s*=\s*$MySqlPort\s*$" -Quiet)) {
        throw 'MySQL 端口与 .local/mysql.ini 不一致。请传入首次初始化使用的 -MySqlPort。'
    }
    if (!(Test-Path -LiteralPath $appIni -PathType Leaf) -or !(Test-Path -LiteralPath $backendConfig -PathType Leaf)) {
        throw '项目数据库配置不完整，请恢复 .local/mysql-fire.cnf 和 .local/application-local.yaml。'
    }

    if (Test-LocalPort $MySqlPort) {
        if (!(Test-ProjectDatabase)) { throw "端口 $MySqlPort 上的服务无法使用本项目的数据库配置连接。" }
    } else {
        $needsBootstrap = Test-Path -LiteralPath $bootSql -PathType Leaf
        if (!(Test-Path -LiteralPath $data)) {
            if (!$needsBootstrap) { throw '找不到已有项目数据库目录 .local/mysql-data，请从备份恢复。' }
            & $mysqlServer "--defaults-file=$myIni" --initialize-insecure --console *> (Join-Path $logs 'mysql-initialize.log')
            if ($LASTEXITCODE -ne 0) { throw '数据库初始化失败，请查看 .local/logs/mysql-initialize.log。' }
        }

        $arguments = @("--defaults-file=$myIni", '--console')
        if ($needsBootstrap) { $arguments += "--init-file=$bootSql" }
        $database = Start-LocalProcess 'mysql' $mysqlServer $arguments $root $logs
        $ready = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            if ($database.HasExited) { break }
            if ((Test-LocalPort $MySqlPort) -and (Test-ProjectDatabase)) {
                $ready = $true
                break
            }
            Start-Sleep -Milliseconds 500
        }
        if (!$ready) { throw '项目数据库未就绪，请查看 .local/logs/mysql-error.log。' }
    }

    if (Test-Path -LiteralPath $bootSql) { Remove-Item -LiteralPath $bootSql }
    Write-Output "项目 MySQL 已就绪，端口 $MySqlPort。"
} finally {
    $lock.Dispose()
}
