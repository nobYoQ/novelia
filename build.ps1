#requires -Version 7.0
[CmdletBinding()]
param(
    [string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest'),
    [switch]$Offline,
    [string]$LogPath,
    [switch]$CheckEnvironment
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false
$rootPath = $PSScriptRoot

# ===== 手动环境配置区：通常全部留空，自动查找失败时再填写 =====
# 填写后优先于自动检测；支持绝对路径，或相对于本脚本目录的路径。
# JDK 根目录，必须包含 bin/java.exe 和 bin/javac.exe。例如：'C:/Tools/jdk-21'
$ManualJavaHome = ''
# Android SDK 根目录，包含 platforms、build-tools 等。例如：'C:/Tools/Android/Sdk'
$ManualAndroidSdk = ''
# 可选缓存目录；留空时优先沿用同名环境变量，否则使用项目 .gradle-home 和 .android。
# 更换 ANDROID_USER_HOME 会改变默认 Debug 测试证书的存放位置。
$ManualGradleUserHome = ''
$ManualAndroidUserHome = ''
# ===== 手动环境配置区结束；版本号请修改根目录 version.properties =====

if (-not $IsWindows) { throw '这些 PowerShell 构建入口用于 Windows；Linux/macOS 请使用 sh ./gradlew。' }
$outputRoot = Join-Path $rootPath 'outputs'
if (-not $LogPath) {
    $LogPath = Join-Path $outputRoot ('logs/build-gradle-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.log')
}
# 相对路径始终以仓库根目录为基准；自定义日志也必须留在 outputs 内。
$LogPath = [IO.Path]::GetFullPath($LogPath, $rootPath)
$outputPrefix = $outputRoot + [IO.Path]::DirectorySeparatorChar
if (-not $LogPath.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw '-LogPath 必须位于仓库根目录的 outputs/ 下，例如 outputs/logs/custom.log。'
}
. (Join-Path $rootPath 'scripts/build-environment.ps1')
$jdk = Resolve-BuildJdk -ManualPath $ManualJavaHome -RootPath $rootPath
$sdk = Resolve-BuildAndroidSdk -ManualPath $ManualAndroidSdk -RootPath $rootPath
$gradleHomePath = if ($ManualGradleUserHome) { $ManualGradleUserHome } elseif ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { '.gradle-home' }
$androidUserPath = if ($ManualAndroidUserHome) { $ManualAndroidUserHome } elseif ($env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME } else { '.android' }
$gradleHomePath = ConvertTo-BuildPath $gradleHomePath $rootPath
$androidUserPath = ConvertTo-BuildPath $androidUserPath $rootPath
Write-Host "JDK $($jdk.Version)：$($jdk.Path) [$($jdk.Source)]"
Write-Host "Android SDK：$($sdk.Path) [$($sdk.Source)]"
Write-Host "Gradle 缓存：$gradleHomePath"
Write-Host "Android 用户目录：$androidUserPath"
if ($CheckEnvironment) {
    Write-Host '环境路径检测完成；未运行 Gradle、下载依赖或修改 local.properties。SDK 组件完整性由实际构建检查。'
    return
}
# ECH AAR 由 Gradle 的 buildEchNative 任务按内容增量生成，所有构建入口使用同一依赖图。
Set-BuildLocalSdk -SdkPath $sdk.Path -RootPath $rootPath
[IO.Directory]::CreateDirectory((Split-Path -Parent $LogPath)) | Out-Null
Write-Host "Gradle 日志：$LogPath"
$arguments = @('-classpath', (Join-Path $rootPath 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain', '--no-daemon', '--console=plain') + $Tasks
if ($Offline) { $arguments += '--offline' }
$environmentNames = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_USER_HOME', 'ANDROID_USER_HOME')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }
Push-Location -LiteralPath $rootPath
try {
    $env:JAVA_HOME = $jdk.Path
    $env:ANDROID_HOME = $sdk.Path
    $env:ANDROID_SDK_ROOT = $sdk.Path
    $env:GRADLE_USER_HOME = $gradleHomePath
    $env:ANDROID_USER_HOME = $androidUserPath
    # 同时指定 Gradle daemon JDK，避免用户 gradle.properties 中旧机器路径覆盖检测结果。
    $arguments += "-Dorg.gradle.java.home=$($jdk.Path)"
    & $jdk.Java @arguments 2>&1 | Tee-Object -FilePath $LogPath
    $gradleExitCode = $LASTEXITCODE
    if ($gradleExitCode -ne 0) { throw "Gradle 构建失败，退出码 $gradleExitCode" }
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    Pop-Location
}
