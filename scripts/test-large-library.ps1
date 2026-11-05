#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateRange(1, 5000)][int]$Books = 2000,
    [ValidateRange(1, 10)][int]$Iterations = 3,
    [string]$Device,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false
$rootPath = Split-Path -Parent $PSScriptRoot
$reportRoot = Join-Path $rootPath ('outputs/performance/large-library-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
[IO.Directory]::CreateDirectory($reportRoot) | Out-Null
. (Join-Path $PSScriptRoot 'build-environment.ps1')
$sdk = Resolve-BuildAndroidSdk -ManualPath '' -RootPath $rootPath
$adb = Join-Path $sdk.Path 'platform-tools/adb.exe'
if (-not $Device) {
    $deviceLines = @(& $adb devices)
    if ($LASTEXITCODE -ne 0) { throw '无法查询 Android 设备。' }
    $ready = @($deviceLines | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
    if ($ready.Count -ne 1) { throw '请连接一台已授权设备，或使用 -Device 指定设备序列号。' }
    $Device = $ready[0]
}
if ($Device -notmatch '^[A-Za-z0-9_.:-]+$') { throw '设备序列号包含不支持的字符。' }

# 仅当前进程覆盖缓存位置，退出后恢复；不修改系统环境或现有用户书库。
$environmentNames = @('GRADLE_USER_HOME', 'ANDROID_USER_HOME', 'TEMP', 'TMP')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }
try {
    $env:GRADLE_USER_HOME = Join-Path $rootPath '.gradle-home'
    $env:ANDROID_USER_HOME = Join-Path $rootPath '.local/android/.android'
    $env:TEMP = Join-Path $rootPath 'outputs/tmp'
    $env:TMP = $env:TEMP
    [IO.Directory]::CreateDirectory($env:TEMP) | Out-Null
    & (Join-Path $rootPath 'build.ps1') -Offline:$Offline -Tasks @(
        ':app:assembleDebug', ':app:assembleDebugAndroidTest', '-PisolatedLibraryTests=true'
    ) -LogPath (Join-Path $reportRoot 'build.log')
    foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
        & $adb -s $Device install -r -t (Join-Path $rootPath $apk)
        if ($LASTEXITCODE -ne 0) { throw "测试安装失败：$apk；不会卸载或清除现有应用。" }
    }
    $classes = 'cc.novelia.app.performance.LargeLibraryPerformanceTest,cc.novelia.app.data.backup.LibraryBackupFlowTest'
    $testLog = Join-Path $reportRoot 'instrumentation.log'
    & $adb -s $Device shell am instrument -w -r -e class $classes -e performanceBooks $Books -e performanceIterations $Iterations 'cc.novelia.app.librarytest.test/androidx.test.runner.AndroidJUnitRunner' 2>&1 |
        Tee-Object -FilePath $testLog
    $testExitCode = $LASTEXITCODE
    $testOutput = Get-Content -LiteralPath $testLog -Encoding utf8 -Raw
    # 即使断言失败也尽量收集本次报告；不扫描设备上其他报告或用户文件。
    $paths = [regex]::Matches($testOutput, '(?m)^INSTRUMENTATION_STATUS: performance(?:Report|Summary)=(.+)\r?$')
    foreach ($entry in $paths) {
        $remote = $entry.Groups[1].Value.Trim()
        if ($remote -notmatch '^/[^\s]*/cc\.novelia\.app\.librarytest/files/performance/large-library-[a-f0-9-]+\.(json|txt)$') {
            throw '测试返回了不符合隔离目录约定的报告路径。'
        }
        & $adb -s $Device pull $remote $reportRoot
        if ($LASTEXITCODE -ne 0) { throw "无法取回性能报告：$remote" }
    }
    if ($testExitCode -ne 0 -or $testOutput -notmatch 'OK \(\d+ tests?\)' -or $testOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED') {
        throw "设备测试未通过，请查看 $testLog"
    }
    if ($paths.Count -ne 2) { throw '未找到本次性能 JSON 和文本报告。' }
    Write-Host "性能报告已保存：$reportRoot"
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
}
