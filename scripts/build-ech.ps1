#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$TestOnly,
    [switch]$Offline,
    [ValidateSet('universal', 'arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')][string]$Abi = 'universal'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$rootPath = Split-Path -Parent $PSScriptRoot
# 与 Android Studio / gradlew 共享构建任务；Go 引导由 prepareEchGo 自动执行。
$task = if ($TestOnly) { ':app:testEchNative' } else { ':app:buildEchNative' }
& (Join-Path $rootPath 'build.ps1') -Tasks @($task, "-PtargetAbi=$($Abi.ToLowerInvariant())") -Offline:$Offline
