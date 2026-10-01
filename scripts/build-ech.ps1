#requires -Version 7.0
[CmdletBinding()]
param([switch]$TestOnly, [switch]$Offline)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$rootPath = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $rootPath 'outputs/ech-tools'
$goVersion = '1.27.1'
$goHash = 'a3911b5e0e1b1053f25ed0675f4c1c6aad1e2bfcf253df2b9be4caabd2edd95d'
$goExe = Join-Path $toolRoot 'go/bin/go.exe'
[IO.Directory]::CreateDirectory($toolRoot) | Out-Null
if (-not (Test-Path -LiteralPath $goExe)) {
    if ($Offline) { throw '离线构建缺少 Go 工具链，请先在线执行 scripts/build-ech.ps1。' }
    $archive = Join-Path $toolRoot "go$goVersion.windows-amd64.zip"
    if (-not (Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -Uri "https://go.dev/dl/go$goVersion.windows-amd64.zip" -OutFile $archive -TimeoutSec 300
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $goHash) {
        throw 'Go 工具链校验失败。'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot
}
$actualVersion = & $goExe version
if ($LASTEXITCODE -ne 0 -or $actualVersion -notlike "go version go$goVersion *") { throw 'Go 版本不符。' }
. (Join-Path $PSScriptRoot 'build-environment.ps1')
$sdk = Resolve-BuildAndroidSdk -ManualPath '' -RootPath $rootPath
$jdk = Resolve-BuildJdk -ManualPath '' -RootPath $rootPath
$ndk = Join-Path $sdk.Path 'ndk/28.2.13676358'
if (-not $TestOnly -and -not (Test-Path -LiteralPath $ndk)) { throw '构建 ECH 需要 Android NDK 28.2.13676358。' }
$names = @('GOROOT','GOPATH','GOCACHE','GOMODCACHE','GOTOOLCHAIN','GOPROXY','GOSUMDB','ANDROID_HOME','ANDROID_NDK_HOME','JAVA_HOME','PATH')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name) }
Push-Location -LiteralPath (Join-Path $rootPath 'native/ech')
try {
    $env:GOROOT = Join-Path $toolRoot 'go'
    $env:GOPATH = Join-Path $toolRoot 'gopath'
    $env:GOCACHE = Join-Path $toolRoot 'cache'
    $env:GOMODCACHE = Join-Path $toolRoot 'modules'
    $env:GOTOOLCHAIN = 'local'
    $env:GOPROXY = if ($Offline) { 'off' } else { 'https://proxy.golang.org' }
    $env:GOSUMDB = 'sum.golang.org'
    $env:ANDROID_HOME = $sdk.Path
    $env:ANDROID_NDK_HOME = $ndk
    $env:JAVA_HOME = $jdk.Path
    $env:PATH = (Join-Path $env:GOROOT 'bin') + ';' + (Join-Path $env:GOPATH 'bin') + ';' + (Join-Path $jdk.Path 'bin') + ';' + $env:PATH
    & $goExe test ./...
    if ($LASTEXITCODE -ne 0) { throw 'ECH Go 单元测试失败。' }
    if ($TestOnly) { return }
    & $goExe install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
    if ($LASTEXITCODE -ne 0) { throw 'gomobile 构建失败。' }
    [IO.Directory]::CreateDirectory((Join-Path (Get-Location) 'build')) | Out-Null
    $gomobile = Join-Path $env:GOPATH 'bin/gomobile.exe'
    $bindArguments = @('bind', '-target=android', '-androidapi=26', '-javapkg=cc.novelia.nativeech', '-ldflags=-s -w', '-o', 'build/novelia-ech.aar', '.')
    & $gomobile @bindArguments
    if ($LASTEXITCODE -ne 0) { throw 'ECH Android AAR 构建失败。' }
    Get-FileHash -LiteralPath 'build/novelia-ech.aar' -Algorithm SHA256
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
