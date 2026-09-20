#requires -Version 7.0
[CmdletBinding()]
param(
    [string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest'),
    [switch]$Offline,
    [string]$LogPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false
$rootPath = $PSScriptRoot
$jdkCandidates = @($env:JAVA_HOME, 'D:\Android Studio\jbr', 'C:\Program Files\Android\Android Studio\jbr')
$javaExecutable = $null
if ($env:JAVA_HOME -and -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'JAVA_HOME 无效，请指向已安装的 JDK 根目录。'
}
foreach ($candidate in $jdkCandidates) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin/java.exe'))) {
        $javaExecutable = Join-Path $candidate 'bin/java.exe'
        break
    }
}
if (-not $javaExecutable) {
    $javaCommand = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javaCommand) { $javaExecutable = $javaCommand.Source }
}
if (-not $javaExecutable) { throw '需要 JDK 17 或更新版本，请设置 JAVA_HOME。' }
if ($LogPath) {
    $LogPath = [IO.Path]::GetFullPath($LogPath)
    [IO.Directory]::CreateDirectory((Split-Path -Parent $LogPath)) | Out-Null
}
$arguments = @('-classpath', (Join-Path $rootPath 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain', '--no-daemon', '--console=plain') + $Tasks
if ($Offline) { $arguments += '--offline' }
$previousGradleHome = $env:GRADLE_USER_HOME
$previousAndroidHome = $env:ANDROID_USER_HOME
Push-Location -LiteralPath $rootPath
try {
    $env:GRADLE_USER_HOME = Join-Path $rootPath '.gradle-home'
    $env:ANDROID_USER_HOME = Join-Path $rootPath '.android'
    if ($LogPath) {
        & $javaExecutable @arguments 2>&1 | Tee-Object -FilePath $LogPath
    } else {
        & $javaExecutable @arguments
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE" }
} finally {
    $env:GRADLE_USER_HOME = $previousGradleHome
    $env:ANDROID_USER_HOME = $previousAndroidHome
    Pop-Location
}
