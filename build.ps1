param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest'), [switch]$Offline)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$rootPath = $PSScriptRoot
$env:GRADLE_USER_HOME = Join-Path $rootPath '.gradle-home'
$env:ANDROID_USER_HOME = Join-Path $rootPath '.android'
$jdkCandidates = @($env:JAVA_HOME, 'D:\Android Studio\jbr', 'C:\Program Files\Android\Android Studio\jbr')
$javaExecutable = $null
foreach ($candidate in $jdkCandidates) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin/java.exe'))) {
        $javaExecutable = Join-Path $candidate 'bin/java.exe'
        if ($candidate.EndsWith('jbr')) { break }
    }
}
if (-not $javaExecutable) { throw '需要 JDK 17 或更新版本，请设置 JAVA_HOME。' }
$arguments = @('-classpath', (Join-Path $rootPath 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain', '--no-daemon', '--console=plain') + $Tasks
if ($Offline) { $arguments += '--offline' }
Push-Location -LiteralPath $rootPath
try {
    & $javaExecutable @arguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE" }
} finally { Pop-Location }
