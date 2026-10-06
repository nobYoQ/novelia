#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repository = 'nobYoQ/novelia',
    [string]$KeystorePath,
    [switch]$CheckOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$rootPath = Split-Path -Parent $PSScriptRoot
if (-not $KeystorePath) {
    $KeystorePath = if ($env:NOVELIA_PREVIEW_KEYSTORE_PATH) { $env:NOVELIA_PREVIEW_KEYSTORE_PATH } else { '.android/debug.keystore' }
}
$KeystorePath = [IO.Path]::GetFullPath($KeystorePath, $rootPath)
if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    throw '缺少原有预览密钥。请从私密备份恢复；本脚本不会生成或覆盖密钥。'
}
$expected = (Get-Content -LiteralPath (Join-Path $rootPath 'gradle/preview-signing.sha256') -Raw -Encoding utf8).Trim()
if ($expected -cnotmatch '^[0-9a-f]{64}$') { throw '预览证书指纹配置无效。' }

$keytool = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/keytool.exe' } else { 'bin/keytool' })
} else {
    $command = Get-Command keytool -CommandType Application -ErrorAction SilentlyContinue
    if (-not $command) { throw '未找到 keytool；请设置 JAVA_HOME 或把 JDK bin 加入 PATH。' }
    $command.Source
}
$previousPassword = [Environment]::GetEnvironmentVariable('NOVELIA_PREVIEW_STORE_PASSWORD')
try {
    # Android 测试密钥的标准口令；私密材料是现有密钥库本身。
    $env:NOVELIA_PREVIEW_STORE_PASSWORD = 'android'
    $certificateOutput = (& $keytool -list -rfc -alias androiddebugkey -keystore $KeystorePath -storepass:env NOVELIA_PREVIEW_STORE_PASSWORD 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw '读取预览证书失败；不会上传密钥。' }
} finally {
    [Environment]::SetEnvironmentVariable('NOVELIA_PREVIEW_STORE_PASSWORD', $previousPassword, 'Process')
}
$certificateMatch = [regex]::Match($certificateOutput, '(?s)-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----')
if (-not $certificateMatch.Success) { throw '未能读取预览公开证书；不会上传密钥。' }
$certificateBytes = [Convert]::FromBase64String(($certificateMatch.Groups[1].Value -replace '\s', ''))
$actual = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($certificateBytes)).ToLowerInvariant()
if ($actual -ne $expected) { throw '证书指纹不匹配；请使用原有本地密钥，不会上传替代密钥。' }
if ($certificateOutput -notmatch '\bPrivateKeyEntry\b') { throw '密钥库仅含公开证书，没有预览签名私钥；不会上传。' }
Write-Host "预览公开证书 SHA-256：$actual"
if ($CheckOnly) { return }

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw '未找到 GitHub CLI。安装 gh 并执行 gh auth login 后，再运行本脚本。'
}
$authOutput = & gh auth status --hostname github.com 2>&1
if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI 未登录。请在自己的终端执行 gh auth login 后重试。' }

$keystoreBytes = [IO.File]::ReadAllBytes($KeystorePath)
$encoded = $null
try {
    $encoded = [Convert]::ToBase64String($keystoreBytes)
    if ($encoded.Length -gt 48 * 1024) { throw '密钥库超过 GitHub Secret 大小限制；未上传。' }
    # 仅在内存中编码，通过 stdin 传给 gh；不落盘、不输出，也不放进命令行参数。
    $secretOutput = $encoded | & gh secret set NOVELIA_PREVIEW_KEYSTORE_BASE64 --repo $Repository --app actions 2>&1
    if ($LASTEXITCODE -ne 0) { throw '配置 GitHub Secret 失败；请检查仓库写入权限后重试。' }
} finally {
    [Array]::Clear($keystoreBytes, 0, $keystoreBytes.Length)
    $encoded = $null
}
Write-Host "已配置 $Repository 的 NOVELIA_PREVIEW_KEYSTORE_BASE64；未改动本地密钥。"
