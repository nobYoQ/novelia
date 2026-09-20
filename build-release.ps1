#requires -Version 7.0
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
