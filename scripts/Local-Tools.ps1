function Get-LocalJavaHome([string]$Root) {
    $javaHome = Join-Path $Root '.tools/jdk21'
    if (!(Test-Path -LiteralPath (Join-Path $javaHome 'bin/javac.exe') -PathType Leaf)) {
        $javaHome = $env:JAVA_HOME
    }
    if (!$javaHome -or !(Test-Path -LiteralPath (Join-Path $javaHome 'bin/javac.exe') -PathType Leaf)) {
        throw '请安装 JDK 21，并将 JAVA_HOME 设置到 JDK 安装目录。'
    }
    $release = Join-Path $javaHome 'release'
    if (!(Test-Path -LiteralPath $release -PathType Leaf) -or
        !(Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="21(?:[."+-])' -Quiet)) {
        throw '当前使用的 JDK 不是 21，请检查 JAVA_HOME 或 .tools/jdk21。'
    }
    return $javaHome
}

function Test-LocalPort([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        return $client.ConnectAsync('127.0.0.1', $Port).Wait(350) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Enter-LocalLock([string]$Path) {
    try {
        return [IO.File]::Open($Path, 'OpenOrCreate', 'ReadWrite', 'None')
    } catch [IO.IOException] {
        throw "无法获取本地启动锁 $Path。请检查是否有其他启动脚本正在运行。"
    }
}

function Start-LocalProcess(
    [string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Root, [string]$Logs
) {
    # Start-Process joins argument strings. Quote each argument to preserve paths with spaces.
    $launch = @{
        FilePath = $Executable
        ArgumentList = $Arguments | ForEach-Object { '"' + $_ + '"' }
        WorkingDirectory = $Root
        WindowStyle = 'Hidden'
        RedirectStandardOutput = Join-Path $Logs "$Name.log"
        RedirectStandardError = Join-Path $Logs "$Name-error.log"
        PassThru = $true
    }
    $process = Start-Process @launch
    $process.Id | Set-Content -LiteralPath (Join-Path $Logs "$Name.pid")
    return $process
}
