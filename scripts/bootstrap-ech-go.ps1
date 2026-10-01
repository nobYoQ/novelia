#requires -Version 7.0
[CmdletBinding()]
param([switch]$Offline)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw '自动下载仅支持 Windows x64；请安装 gradle/ech-native.properties 指定的 Go 并设置 NOVELIA_GO_HOME。'
}
$rootPath = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $rootPath 'outputs/ech-tools'
$versions = ConvertFrom-StringData -StringData (Get-Content -LiteralPath (Join-Path $rootPath 'gradle/ech-native.properties') -Raw -Encoding utf8)
$goVersion = $versions.goVersion
$goExe = Join-Path $toolRoot 'go/bin/go.exe'
if (Test-Path -LiteralPath $goExe) { return }
if ($Offline) { throw "离线构建缺少 Go $goVersion；请先在线执行 scripts/build-ech.ps1，或设置 NOVELIA_GO_HOME。" }
[IO.Directory]::CreateDirectory($toolRoot) | Out-Null
$archive = Join-Path $toolRoot "go$goVersion.windows-amd64.zip"
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri "https://go.dev/dl/go$goVersion.windows-amd64.zip" -OutFile $archive -TimeoutSec 300
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $versions.goWindowsAmd64Sha256) {
    throw 'Go 工具链校验失败；请检查 outputs/ech-tools 内的下载文件。'
}
Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot
