#requires -Version 7.0
# 环境自动检测；如需手动填写 JDK、SDK 或缓存路径，修改根目录 build.ps1 顶部“手动环境配置区”。
# 应用版本号/版本码统一修改根目录 version.properties；修改后直接重新执行本脚本。
[CmdletBinding()]
param(
    [ValidateSet('universal', 'arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')][string]$Abi = 'universal',
    [switch]$Offline,
    [switch]$Verify,
    [switch]$Unsigned
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

& (Join-Path $PSScriptRoot 'scripts/build-package.ps1') -Variant Release -Abi $Abi -Offline:$Offline -Verify:$Verify -Unsigned:$Unsigned
