#Requires -Version 5.1
<#
.SYNOPSIS
    生成 ADMIN_PASSWORD_HASH 需要的 BCrypt 哈希（cost 12）。

.DESCRIPTION
    直接复用 fatJar 里已打包的 at.favre.lib:bcrypt，不需要额外装工具：
    在 build/tmp/pwhash 下编译一个几行的 Java 辅助类，再从标准输入读取口令算哈希。
    fatJar 不存在时先执行 .\gradlew.bat --offline shadowJar。

.PARAMETER Password
    明文口令。不传则交互式隐藏输入。

.PARAMETER JavaHome
    JDK 根目录（需含 bin\javac.exe）。默认取 $env:JAVA_HOME，其次 E:\android_studio\jbr，最后 PATH 上的 javac。

.EXAMPLE
    .\scripts\New-PasswordHash.ps1 -Password 'MyDevPwd1'

.EXAMPLE
    # 输出直接用于启动：$env:ADMIN_PASSWORD_HASH = (.\scripts\New-PasswordHash.ps1)
#>
[CmdletBinding()]
param(
    [string]$Password = '',
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'

function Resolve-JdkBin {
    param([string]$PreferredHome)

    $candidates = @()
    if ($PreferredHome) { $candidates += $PreferredHome }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += 'E:\android_studio\jbr'

    foreach ($jdkCandidate in $candidates) {
        $bin = Join-Path $jdkCandidate 'bin'
        if (Test-Path (Join-Path $bin 'javac.exe')) { return $bin }
    }

    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { return (Split-Path -Parent $javac.Source) }

    throw '找不到 javac：请用 -JavaHome 指定 JDK 根目录（本机为 E:\android_studio\jbr）'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$jdkBin = Resolve-JdkBin -PreferredHome $JavaHome

$jar = Get-ChildItem -Path (Join-Path $repoRoot 'build\libs') -Filter '*-all.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw '未找到 fatJar：先执行 .\gradlew.bat --offline shadowJar'
}

if (-not $Password) {
    $secure = Read-Host '输入明文口令' -AsSecureString
    $Password = [System.Net.NetworkCredential]::new('', $secure).Password
}
if (-not $Password) { throw '口令不能为空' }

$toolDir = Join-Path $repoRoot 'build\tmp\pwhash'
New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
$sourcePath = Join-Path $toolDir 'PasswordHashTool.java'
$classPath = Join-Path $toolDir 'PasswordHashTool.class'

if (-not (Test-Path $classPath) -or (Get-Item $classPath).LastWriteTime -lt $jar.LastWriteTime) {
    $source = @(
        'import at.favre.lib.crypto.bcrypt.BCrypt;'
        ''
        'public final class PasswordHashTool {'
        '    public static void main(String[] args) throws Exception {'
        '        String password = args.length > 0'
        '            ? args[0]'
        '            : new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();'
        '        System.out.println(BCrypt.withDefaults().hashToString(12, password.toCharArray()));'
        '    }'
        '}'
    ) -join "`n"
    Set-Content -Path $sourcePath -Value $source -Encoding ascii
    & (Join-Path $jdkBin 'javac.exe') -cp $jar.FullName -d $toolDir $sourcePath
    if ($LASTEXITCODE -ne 0) { throw '编译 PasswordHashTool 失败' }
}

$hash = $Password | & (Join-Path $jdkBin 'java.exe') -cp "$($jar.FullName);$toolDir" PasswordHashTool
if ($LASTEXITCODE -ne 0) { throw '生成 BCrypt 哈希失败' }

Write-Output $hash.Trim()
