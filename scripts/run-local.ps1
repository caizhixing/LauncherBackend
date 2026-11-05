#Requires -Version 5.1
<#
.SYNOPSIS
    本地启动 feedback-backend（开发机自用；生产用 systemd/Docker 配真实环境变量，不走本脚本）。

.DESCRIPTION
    1) fatJar 不存在时先执行 .\gradlew.bat --offline shadowJar（依赖已在本地缓存，禁止联网）；
    2) 补齐 AppConfig 需要的环境变量（ADMIN_USERNAME / ADMIN_PASSWORD_HASH / JWT_SECRET / DB_* / PORT）；
    3) 探测数据库端口，连不上只告警不阻断（服务启动不校验 DB，见 DECISIONS.md #DB故障降级实现）；
    4) 前台启动 Ktor(Netty)，Ctrl+C 停止。

.PARAMETER AdminPassword
    管理员明文口令；给了就用它现算 BCrypt(cost 12) 哈希。不给则用 -AdminPasswordHash，
    两者都不给时用开发默认口令 admin123。

.PARAMETER JwtSecret
    JWT 签名密钥（≥32 字符）。不给则每次启动随机生成 64 字符，重启后旧 token 失效。

.PARAMETER DbPassword
    MySQL 口令。默认 123456，与 .\scripts\mysql-local.ps1 -Action init 创建的便携实例一致。

.PARAMETER DbUrl
    JDBC URL。默认 jdbc:mysql://127.0.0.1:3306/feedback?useSSL=false&characterEncoding=utf8&allowPublicKeyRetrieval=true

.PARAMETER CorsOrigins
    逗号分隔的允许来源；App 端直连不需要，浏览器前端调试才填（如 http://localhost:5173）。

.EXAMPLE
    .\scripts\run-local.ps1 -DbPassword 123456

.EXAMPLE
    .\scripts\run-local.ps1 -AdminPassword 'MyDevPwd1' -DbUser feedback -DbPassword 123456 -Port 9090
#>
[CmdletBinding()]
param(
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = '',
    [string]$AdminPasswordHash = '',
    [string]$JwtSecret = '',
    [string]$DbUrl = 'jdbc:mysql://127.0.0.1:3306/feedback?useSSL=false&characterEncoding=utf8&allowPublicKeyRetrieval=true',
    [string]$DbUser = 'root',
    [string]$DbPassword = '123456',
    [int]$Port = 8080,
    [string]$CorsOrigins = '',
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'

# 开发默认口令 admin123 的 BCrypt(cost 12) 哈希，仅用于本机调试
$DEV_DEFAULT_PASSWORD_HASH = '$2a$12$IG3UXXeHWGgYjQQIlq6lfO78aCYewTgMvJxnGiBHnODG1D.SWkzVG'

function Resolve-JdkHome {
    param([string]$PreferredHome)

    $candidates = @()
    if ($PreferredHome) { $candidates += $PreferredHome }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += 'E:\android_studio\jbr'

    foreach ($jdkCandidate in $candidates) {
        if (Test-Path (Join-Path $jdkCandidate 'bin\java.exe')) { return $jdkCandidate }
    }

    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) { return (Split-Path -Parent (Split-Path -Parent $java.Source)) }

    throw '找不到 java：请用 -JavaHome 指定 JDK 根目录（本机为 E:\android_studio\jbr）'
}

function Test-DbEndpoint {
    param([string]$JdbcUrl)

    $match = [regex]::Match($JdbcUrl, '^jdbc:mysql://(?<host>[^:/?]+)(:(?<port>\d+))?/')
    if (-not $match.Success) { return $null }
    $hostName = $match.Groups['host'].Value
    $portNumber = if ($match.Groups['port'].Success) { [int]$match.Groups['port'].Value } else { 3306 }

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($hostName, $portNumber)
        if (-not $task.Wait(800)) { return $false }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$jdkHome = Resolve-JdkHome -PreferredHome $JavaHome

function Find-FatJar {
    Get-ChildItem -Path (Join-Path $repoRoot 'build\libs') -Filter '*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

$jar = Find-FatJar
if (-not $jar) {
    Write-Host '[build] 未找到 fatJar，执行 .\gradlew.bat --offline shadowJar ...' -ForegroundColor Yellow
    $gradlew = Join-Path $repoRoot 'gradlew.bat'
    if (-not (Test-Path $gradlew)) { throw "缺少 $gradlew" }
    $env:JAVA_HOME = $jdkHome
    Push-Location $repoRoot
    try {
        & $gradlew --offline shadowJar
        if ($LASTEXITCODE -ne 0) { throw '离线构建失败（检查 Gradle/JDK/本地缓存）' }
    } finally {
        Pop-Location
    }
    $jar = Find-FatJar
    if (-not $jar) { throw '构建完成但仍未找到 *-all.jar' }
}

if ($AdminPassword) {
    Write-Host '[admin] 用明文口令现算 BCrypt 哈希 ...' -ForegroundColor Yellow
    $AdminPasswordHash = & (Join-Path $PSScriptRoot 'New-PasswordHash.ps1') -Password $AdminPassword -JavaHome $jdkHome
}
if (-not $AdminPasswordHash) {
    $AdminPasswordHash = $DEV_DEFAULT_PASSWORD_HASH
    Write-Host '[admin] 使用开发默认口令：admin / admin123（生产部署必须换掉 ADMIN_PASSWORD_HASH）' -ForegroundColor Yellow
}

if (-not $JwtSecret) {
    $alphabet = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
    $JwtSecret = -join (1..64 | ForEach-Object { $alphabet[(Get-Random -Maximum $alphabet.Length)] })
    Write-Host '[jwt] 未指定 JWT_SECRET，已随机生成（重启后旧 token 失效）' -ForegroundColor Yellow
}
if ($JwtSecret.Length -lt 32) { throw 'JWT_SECRET 必须至少 32 字符（AppConfig 启动校验）' }

$dbReachable = Test-DbEndpoint -JdbcUrl $DbUrl
if ($dbReachable -eq $false) {
    Write-Warning "数据库不可达：$DbUrl。服务仍会启动（启动不校验 DB），但提交/列表/标记/删除接口会在连接超时后返回 50000。"
    Write-Warning '修复：CREATE DATABASE IF NOT EXISTS feedback DEFAULT CHARACTER SET utf8mb4; 然后 mysql -uroot -p feedback < schema.sql'
} elseif ($dbReachable -eq $true) {
    Write-Host '[db] 端口可达' -ForegroundColor Green
}

$env:ADMIN_USERNAME = $AdminUsername
$env:ADMIN_PASSWORD_HASH = $AdminPasswordHash
$env:JWT_SECRET = $JwtSecret
$env:DB_URL = $DbUrl
$env:DB_USER = $DbUser
$env:DB_PASSWORD = $DbPassword
$env:PORT = "$Port"
if ($CorsOrigins) { $env:CORS_ORIGINS = $CorsOrigins }

function Test-PortInUse {
    param([int]$TargetPort)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $TargetPort)
        if (-not $task.Wait(800)) { return $false }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

# 端口被占时先说清楚，而不是让 Netty 抛 BindException 堆栈
if (Test-PortInUse -TargetPort $Port) {
    throw "端口 $Port 已被占用（多为上一次没退出的后端实例）。查占用：netstat -ano | findstr :$Port 拿到 PID，再 tasklist 确认；或换端口：.\scripts\run-local.cmd -Port 9090"
}

Write-Host ''
Write-Host "启动：http://127.0.0.1:$Port" -ForegroundColor Cyan
Write-Host "  admin 用户名 : $AdminUsername"
Write-Host "  数据库       : $DbUser @ $DbUrl"
Write-Host "  CORS         : $(if ($CorsOrigins) { $CorsOrigins } else { '<未设置>' })"
Write-Host '  Ctrl+C 停止'
Write-Host ''

& (Join-Path $jdkHome 'bin\java.exe') -jar $jar.FullName
exit $LASTEXITCODE
