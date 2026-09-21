#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Debug', 'Release')][string]$Variant,
    [ValidateSet('universal', 'arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')][string]$Abi = 'universal',
    [switch]$Offline,
    [switch]$Verify,
    [switch]$Unsigned
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$rootPath = Split-Path -Parent $PSScriptRoot
$utf8 = [Text.UTF8Encoding]::new($false)
$Variant = if ($Variant -ieq 'Debug') { 'Debug' } else { 'Release' }
$Abi = $Abi.ToLowerInvariant()

if ($Unsigned -and $Variant -ne 'Release') { throw '-Unsigned 仅适用于 Release。' }
$mode = if ($Variant -eq 'Debug') { 'debug' } elseif ($Unsigned) { 'release-unsigned' } else { 'release-local' }
$versionPath = Join-Path $rootPath 'version.properties'
$version = ConvertFrom-StringData (Get-Content -LiteralPath $versionPath -Encoding utf8 -Raw)
$versionName = $version.versionName
$versionCode = [int]$version.versionCode
if ($versionName -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$' -or $versionCode -le 0) {
    throw 'version.properties 中的版本配置无效。'
}

$logDirectory = Join-Path $rootPath 'outputs/logs'
[IO.Directory]::CreateDirectory($logDirectory) | Out-Null
$logPath = Join-Path $logDirectory ("build-$mode-$Abi-" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.log')
$tasks = @()
if ($Verify) { $tasks += ":app:test${Variant}UnitTest", ":app:lint$Variant" }
$tasks += ":app:assemble$Variant"
# Explicit false values override machine-local Gradle properties as well as the default.
$tasks += '-PreleaseSigning=false'
$tasks += if ($mode -eq 'release-local') { '-PlocalReleaseSigning=true' } else { '-PlocalReleaseSigning=false' }
$tasks += "-PtargetAbi=$Abi"

Write-Host "构建：$mode / $Abi / $versionName ($versionCode)"
if ($mode -eq 'release-local') { Write-Host '签名：本地 Android Debug 测试证书；已启用 Release 压缩优化。' }
if ($Unsigned) { Write-Host '签名：未签名；此 APK 不能直接安装。' }
Write-Host "日志：$logPath"
try {
    & (Join-Path $rootPath 'build.ps1') -Tasks $tasks -Offline:$Offline -LogPath $logPath

    $apkDirectory = Join-Path $rootPath ('app/build/outputs/apk/' + $Variant.ToLowerInvariant())
    $metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Encoding utf8 -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'cc.novelia.app' -or @($metadata.elements).Count -ne 1) {
        throw 'APK 输出信息与预期不符；没有整理安装包。'
    }
    $element = $metadata.elements[0]
    if ($element.versionName -ne $versionName -or $element.versionCode -ne $versionCode) {
        throw 'APK 版本与本次构建读取的 version.properties 不一致；没有整理安装包。'
    }
    $apk = [IO.Path]::GetFullPath((Join-Path $apkDirectory $element.outputFile))
    $apkPrefix = [IO.Path]::GetFullPath($apkDirectory) + [IO.Path]::DirectorySeparatorChar
    if (-not $apk.StartsWith($apkPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetExtension($apk) -ne '.apk' -or -not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw 'APK 输出文件无效或不在构建目录内。'
    }
    $mapping = Join-Path $rootPath 'app/build/outputs/mapping/release/mapping.txt'
    if ($Variant -eq 'Release' -and -not (Test-Path -LiteralPath $mapping -PathType Leaf)) {
        throw 'Release 构建缺少 mapping.txt；没有整理安装包。'
    }

    $outputDirectory = Join-Path $rootPath "outputs/packages/$mode"
    [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    $stem = "Novelia-$versionName-$mode-$Abi"
    $outputApk = Join-Path $outputDirectory "$stem.apk"
    Copy-Item -LiteralPath $apk -Destination $outputApk -Force
    $sha256 = (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$outputApk.sha256", "$sha256  $stem.apk`n", $utf8)
    if ($Variant -eq 'Release') {
        Copy-Item -LiteralPath $mapping -Destination (Join-Path $outputDirectory "$stem-mapping.txt") -Force
    }

    Write-Host ''
    Write-Host "构建完成：$outputApk"
    Write-Host "SHA-256：$outputApk.sha256"
    if ($Variant -eq 'Release') { Write-Host "混淆映射：$(Join-Path $outputDirectory "$stem-mapping.txt")" }
    Write-Host "构建日志：$logPath"
} catch {
    Write-Host "构建或产物整理失败，请检查上述错误及日志：$logPath"
    throw
}
