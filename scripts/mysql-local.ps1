#Requires -Version 5.1
<#
.SYNOPSIS
    便携版 MySQL 8 的本地实例管理（解压即用，不注册 Windows 服务）。

.DESCRIPTION
    数据与程序全部放在仓库下的 .mysql\（已 gitignore），删掉该目录即彻底卸载。
    首次使用需先下载并解压 winx64 ZIP 到 .mysql\ 下，见 README「本地 MySQL」。

.PARAMETER Action
    init  ：初始化数据目录 + 设 root 密码 + 建 feedback 库 + 执行 schema.sql（只跑一次）
    start ：后台启动 mysqld（127.0.0.1:3306）
    stop  ：关闭 mysqld
    status：查看端口与进程状态

.PARAMETER RootPassword
    init 时设置的 root 口令，默认 123456（仅本机开发用）。

.EXAMPLE
    .\scripts\mysql-local.ps1 -Action init
    .\scripts\mysql-local.ps1 -Action start
#>
[CmdletBinding()]
param(
    [ValidateSet('init', 'start', 'stop', 'status')]
    [string]$Action = 'status',
    [string]$RootPassword = '123456',
    [int]$Port = 3306
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$mysqlRoot = Join-Path $repoRoot '.mysql'
$dataDir = Join-Path $mysqlRoot 'data'
$tmpDir = Join-Path $mysqlRoot 'tmp'
$configFile = Join-Path $mysqlRoot 'my.ini'

$basedir = Get-ChildItem -Path $mysqlRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'mysql-*' } |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $basedir) {
    throw "未找到 MySQL 程序目录：请先把 winx64 ZIP 解压到 $mysqlRoot\（应得到 $mysqlRoot\mysql-8.0.x-winx64\bin\mysqld.exe）"
}
$binDir = Join-Path $basedir.FullName 'bin'
$mysqld = Join-Path $binDir 'mysqld.exe'
$mysql = Join-Path $binDir 'mysql.exe'
$mysqladmin = Join-Path $binDir 'mysqladmin.exe'

# 用 TCP 连接探测而不是 Get-NetTCPConnection：后者在受限环境（沙箱/无权限查询连接表）下会漏报在监听的服务
function Test-PortListening {
    param([int]$TargetPort)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $TargetPort)
        if (-not $task.Wait(1000)) { return $false }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-Port {
    param([int]$TargetPort, [int]$TimeoutSeconds = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortListening -TargetPort $TargetPort) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

# 用 WMI(Win32_Process.Create) 启动：进程由 WMI 服务持有，不随启动它的 shell 退出而被回收；
# 沙箱/任务对象环境会自动退回 Start-Process。
function Start-Mysqld {
    $commandLine = '"' + $mysqld + '" --defaults-file="' + $configFile + '"'
    try {
        $result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $commandLine }
        if ($result.ReturnValue -eq 0) { return }
        Write-Warning "WMI 启动失败（ReturnValue=$($result.ReturnValue)），退回 Start-Process"
    } catch {
        Write-Warning "WMI 不可用，退回 Start-Process：$($_.Exception.Message)"
    }
    Start-Process -FilePath $mysqld -ArgumentList "--defaults-file=$configFile" -WindowStyle Hidden
}

function Write-MyIni {
    $content = @(
        '[mysqld]'
        "basedir=$($basedir.FullName -replace '\\', '/')"
        "datadir=$($dataDir -replace '\\', '/')"
        "port=$Port"
        'bind-address=127.0.0.1'
        'character-set-server=utf8mb4'
        'collation-server=utf8mb4_0900_ai_ci'
        "default-time-zone='+08:00'"
        "tmpdir=$($tmpDir -replace '\\', '/')"
        'mysqlx=0'
        "log-error=$(($mysqlRoot -replace '\\', '/'))/mysqld.err"
        'pid-file=mysqld.pid'
        'max_connections=50'
    ) -join "`r`n"
    Set-Content -Path $configFile -Value $content -Encoding ascii
}

switch ($Action) {
    'status' {
        $listening = Test-PortListening -TargetPort $Port
        $proc = Get-Process mysqld -ErrorAction SilentlyContinue
        "port $Port : $(if ($listening) { 'LISTEN' } else { 'free' })"
        "mysqld    : $(if ($proc) { "running (pid=$($proc.Id -join ','))" } else { 'not running' })"
        "basedir   : $($basedir.FullName)"
        "datadir   : $(if (Test-Path $dataDir) { '已初始化' } else { '未初始化（-Action init）' })"
        if (Test-Path (Join-Path $mysqlRoot 'mysqld.err')) {
            "log       : $(Join-Path $mysqlRoot 'mysqld.err')"
        }
    }

    'init' {
        if (Test-PortListening -TargetPort $Port) { throw "端口 $Port 已被占用，先停止已有 MySQL（-Action stop）" }
        if (Test-Path $dataDir) { throw "$dataDir 已存在，无需重复 init（要重来就先删掉 .mysql\data）" }

        Write-MyIni
        New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
        Write-Host '[init] mysqld --initialize-insecure ...' -ForegroundColor Yellow
        & $mysqld "--defaults-file=$configFile" '--initialize-insecure' '--console'
        if ($LASTEXITCODE -ne 0) { throw "初始化失败，看 $mysqlRoot\mysqld.err" }

        Write-Host '[init] 启动实例 ...' -ForegroundColor Yellow
        Start-Mysqld
        if (-not (Wait-Port -TargetPort $Port)) { throw "mysqld 未在 60 秒内监听 $Port。常见原因：tmpdir/TEMP 不可写、端口被占、数据目录被另一实例锁定；详见 $mysqlRoot\mysqld.err" }

        Write-Host '[init] 设置 root 口令 + 建库 + 建表 ...' -ForegroundColor Yellow
        $script = @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$RootPassword';
CREATE DATABASE IF NOT EXISTS feedback DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
"@
        $scriptPath = Join-Path $mysqlRoot 'init.sql'
        Set-Content -Path $scriptPath -Value $script -Encoding ascii
        # 首次连接时 root 还是空口令；口令走 MYSQL_PWD 而不是 -p，避免明文进进程参数
        $env:MYSQL_PWD = $null
        & $mysql '--protocol=TCP' '-h127.0.0.1' "-P$Port" '-uroot' "--default-character-set=utf8mb4" '-e' "source $(($scriptPath -replace '\\', '/'))"
        if ($LASTEXITCODE -ne 0) { throw 'root 口令/建库失败' }

        $env:MYSQL_PWD = $RootPassword
        $schemaPath = Join-Path $repoRoot 'schema.sql'
        & $mysql '--protocol=TCP' '-h127.0.0.1' "-P$Port" '-uroot' '--default-character-set=utf8mb4' 'feedback' '-e' "source $(($schemaPath -replace '\\', '/'))"
        $env:MYSQL_PWD = $null
        if ($LASTEXITCODE -ne 0) { throw "执行 schema.sql 失败，看 $mysqlRoot\mysqld.err" }

        Write-Host ''
        Write-Host "完成：127.0.0.1:$Port / feedback / root / $RootPassword" -ForegroundColor Green
        Write-Host '启动后端：.\scripts\run-local.ps1 -DbPassword ' -NoNewline
        Write-Host $RootPassword -ForegroundColor Cyan
    }

    'start' {
        if (Test-PortListening -TargetPort $Port) { Write-Host "已在运行：127.0.0.1:$Port" -ForegroundColor Green; break }
        if (-not (Test-Path $dataDir)) { throw "$dataDir 不存在，先执行 -Action init" }
        if (-not (Test-Path $configFile)) { Write-MyIni }
        New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

        Start-Mysqld
        if (Wait-Port -TargetPort $Port) {
            Write-Host "已启动：127.0.0.1:$Port" -ForegroundColor Green
        } else {
            throw "mysqld 未在 60 秒内监听 $Port。常见原因：tmpdir/TEMP 不可写、端口被占、数据目录被另一实例锁定；详见 $mysqlRoot\mysqld.err"
        }
    }

    'stop' {
        if (-not (Test-PortListening -TargetPort $Port)) { Write-Host '未在运行'; break }
        $env:MYSQL_PWD = $RootPassword
        & $mysqladmin '--protocol=TCP' '-h127.0.0.1' "-P$Port" '-uroot' 'shutdown'
        $env:MYSQL_PWD = $null
        if (Wait-Port -TargetPort $Port -TimeoutSeconds 1) {
            # 兜底：强杀
            Get-Process mysqld -ErrorAction SilentlyContinue | Stop-Process -Force
        }
        Write-Host '已停止' -ForegroundColor Green
    }
}
