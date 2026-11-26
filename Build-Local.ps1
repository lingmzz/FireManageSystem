[CmdletBinding(DefaultParameterSetName = 'Online')]
param(
    [Parameter(ParameterSetName = 'Online')][switch]$Online,
    [Parameter(ParameterSetName = 'Offline')][switch]$Offline
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/Local-Tools.ps1')

$tools = Join-Path $PSScriptRoot '.tools'
$frontend = Join-Path $PSScriptRoot 'frontend'
$javaHome = Get-LocalJavaHome $PSScriptRoot
$maven = Get-Command mvn.cmd -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (!$maven) { throw '请安装 Maven 并将 mvn.cmd 加入 PATH。' }

$bundledNode = Join-Path $tools 'node24/node.exe'
$bundledPnpm = Join-Path $tools 'pnpm10/package/bin/pnpm.cjs'
$oldPath = $env:Path
$oldJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $javaHome
    if (Test-Path -LiteralPath $bundledNode -PathType Leaf) {
        $env:Path = (Split-Path -Parent $bundledNode) + ';' + $env:Path
    }
    $node = Get-Command node.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$node) { throw '请安装 Node.js 24 并将 node.exe 加入 PATH。' }

    $pnpmArgs = @()
    if (Test-Path -LiteralPath $bundledPnpm -PathType Leaf) {
        $pnpm = $node.Source
        $pnpmArgs = @($bundledPnpm)
    } else {
        $pnpmCommand = Get-Command pnpm.cmd -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if (!$pnpmCommand) { throw '请运行 npm install --global pnpm@10.24.0 安装 pnpm。' }
        $pnpm = $pnpmCommand.Source
    }

    $installArgs = @('install', '--frozen-lockfile', '--reporter=append-only')
    $store = Join-Path $tools 'pnpm-store'
    if (Test-Path -LiteralPath $store -PathType Container) { $installArgs += @('--store-dir', $store) }
    if ($Offline) { $installArgs += '--offline' }

    Push-Location $frontend
    try {
        & $pnpm @pnpmArgs @installArgs
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败，请检查 Node.js、pnpm 和依赖下载配置。' }
        & $pnpm @pnpmArgs build
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败，请查看上方输出。' }
    } finally {
        Pop-Location
    }

    $pom = Join-Path $PSScriptRoot 'backend/pom.xml'
    $repo = Join-Path $tools 'maven-repo'
    $settings = Join-Path $tools 'maven-settings.xml'
    $mavenArgs = @('-B', '-ntp', '-f', $pom)
    if ($Offline) { $mavenArgs += '-o' }
    if (Test-Path -LiteralPath $repo -PathType Container) { $mavenArgs += "-Dmaven.repo.local=$repo" }
    if (Test-Path -LiteralPath $settings -PathType Leaf) { $mavenArgs += @('-s', $settings) }
    & $maven.Source @mavenArgs clean package
    if ($LASTEXITCODE -ne 0) { throw '后端构建失败，请查看上方 Maven 输出。' }
} finally {
    $env:Path = $oldPath
    $env:JAVA_HOME = $oldJavaHome
}
