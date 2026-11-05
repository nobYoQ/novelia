#requires -Version 7.0
# 供 build.ps1 复用的检测逻辑；手动配置请统一填写 build.ps1 顶部的配置区。
Set-StrictMode -Version Latest

function ConvertTo-BuildPath {
    param([string]$Path, [string]$RootPath)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $null }
    $expanded = [Environment]::ExpandEnvironmentVariables($Path.Trim().Trim('"'))
    return [IO.Path]::GetFullPath($expanded, $RootPath)
}

function Get-BuildJdkCandidates {
    foreach ($name in @('JAVA_HOME', 'JDK_HOME', 'STUDIO_JDK')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value) { [pscustomobject]@{ Path = $value; Source = $name } }
    }
    foreach ($command in @(Get-Command java.exe -CommandType Application -All -ErrorAction SilentlyContinue)) {
        [pscustomobject]@{ Path = $command.Source; Source = 'PATH' }
    }

    # Android Studio 的注册表安装位置也能覆盖安装到自定义盘符的情况。
    $studioPaths = @(
        foreach ($key in @('HKCU:\Software\Android Studio', 'HKLM:\SOFTWARE\Android Studio', 'HKLM:\SOFTWARE\WOW6432Node\Android Studio')) {
            $entry = Get-ItemProperty -LiteralPath $key -ErrorAction SilentlyContinue
            if ($entry -and $entry.PSObject.Properties['Path']) { $entry.Path }
        }
        foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
            if ($base) { Join-Path $base 'Android/Android Studio' }
        }
    )
    foreach ($studioPath in $studioPaths) {
        foreach ($runtime in @('jbr', 'jre')) {
            [pscustomobject]@{ Path = (Join-Path $studioPath $runtime); Source = 'Android Studio' }
        }
    }
    $installRoots = @(
        if ($env:USERPROFILE) { Join-Path $env:USERPROFILE '.jdks' }
        foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
            if ($base) {
                foreach ($vendor in @('Java', 'Eclipse Adoptium', 'Microsoft', 'Amazon Corretto', 'Zulu', 'BellSoft', 'Semeru')) {
                    Join-Path $base $vendor
                }
            }
        }
    )
    foreach ($installRoot in $installRoots) {
        foreach ($directory in @(Get-ChildItem -LiteralPath $installRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)) {
            [pscustomobject]@{ Path = $directory.FullName; Source = '常见 JDK 安装目录' }
        }
    }
}

function Resolve-BuildJdk {
    param([string]$ManualPath, [string]$RootPath)
    $candidates = if ($ManualPath) {
        @([pscustomobject]@{ Path = $ManualPath; Source = 'build.ps1 手动配置' })
    } else { @(Get-BuildJdkCandidates) }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($candidate in $candidates) {
        try {
            $candidatePath = ConvertTo-BuildPath $candidate.Path $RootPath
            $java = if ($candidate.Source -eq 'PATH') { $candidatePath } else { Join-Path $candidatePath 'bin/java.exe' }
            if (-not $seen.Add($java)) { continue }
            if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw '缺少 bin/java.exe' }
            # 读取真实 java.home，兼容 PATH 中 Oracle javapath 等转发目录。
            $details = @(& $java '-XshowSettings:properties' '-version' 2>&1)
            $javaExitCode = $LASTEXITCODE
            $detailsText = $details -join "`n"
            if ($javaExitCode -ne 0) { throw "Java 无法运行（退出码 $javaExitCode）" }
            $homeMatch = [regex]::Match($detailsText, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
            $versionMatch = [regex]::Match($detailsText, '(?m)^\s*java\.specification\.version\s*=\s*(\d+)\s*$')
            if (-not $homeMatch.Success -or -not $versionMatch.Success) { throw '无法读取 Java 版本和实际安装目录' }
            $major = [int]$versionMatch.Groups[1].Value
            # 当前 AGP 8.13.2 要求至少 17，Gradle 8.13 最高支持运行于 23。
            # 升级 Wrapper 时一并核对 https://docs.gradle.org/current/userguide/compatibility.html
            if ($major -lt 17 -or $major -gt 23) { throw "JDK $major 不适用于当前构建工具，请使用 JDK 17–23（推荐 17 或 21）" }
            $jdkPath = ConvertTo-BuildPath $homeMatch.Groups[1].Value $RootPath
            if (-not (Test-Path -LiteralPath (Join-Path $jdkPath 'bin/javac.exe') -PathType Leaf)) { throw '缺少 bin/javac.exe，需要完整 JDK' }
            return [pscustomobject]@{ Path = $jdkPath; Java = (Join-Path $jdkPath 'bin/java.exe'); Version = $major; Source = $candidate.Source }
        } catch {
            if ($ManualPath) { throw "build.ps1 的 `$ManualJavaHome 无效：$($_.Exception.Message)" }
            if ($candidate.Source -in @('JAVA_HOME', 'JDK_HOME', 'STUDIO_JDK', 'PATH')) {
                Write-Warning "跳过 $($candidate.Source) 的 JDK：$($_.Exception.Message)"
            }
        }
    }
    throw '未找到可用 JDK 17–23。请安装 JDK，设置 JAVA_HOME，或填写 build.ps1 顶部的 $ManualJavaHome（JDK 根目录，不是 bin）。'
}

function Get-LocalSdkSetting {
    param([string]$RootPath)
    $file = Join-Path $RootPath 'local.properties'
    $lines = @(if (Test-Path -LiteralPath $file -PathType Leaf) { [IO.File]::ReadAllLines($file, [Text.Encoding]::UTF8) })
    $otherLines = [Collections.Generic.List[string]]::new()
    $sdkPath = $null
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $physicalLines = [Collections.Generic.List[string]]::new()
        $logicalLine = $lines[$i]
        $physicalLines.Add($lines[$i])
        # Java properties 的续行规则：末尾奇数个反斜杠表示继续下一行。
        while (([regex]::Match($logicalLine, '\\+$').Length % 2) -eq 1 -and $i + 1 -lt $lines.Count) {
            $i++
            $physicalLines.Add($lines[$i])
            $logicalLine = $logicalLine.Substring(0, $logicalLine.Length - 1) + $lines[$i].TrimStart()
        }
        if ($logicalLine -match '^\s*sdk\.dir(?:\s*[=:]\s*|\s+)(.*)$') {
            # 兼容 D\:/Android/sdk、双反斜杠和 Android Studio 的 \uXXXX 路径。
            $sdkPath = [regex]::Replace($Matches[1], '\\(u[0-9a-fA-F]{4}|.)', {
                param($match)
                $escaped = $match.Groups[1].Value
                if ($escaped.Length -eq 5 -and $escaped[0] -eq 'u') { return [string][char][Convert]::ToInt32($escaped.Substring(1), 16) }
                switch ($escaped) { 't' { "`t" }; 'n' { "`n" }; 'r' { "`r" }; 'f' { "`f" }; default { $escaped } }
            })
        } else { $otherLines.AddRange($physicalLines) }
    }
    return [pscustomobject]@{ Path = $sdkPath; OtherLines = $otherLines.ToArray(); File = $file }
}

function Resolve-BuildAndroidSdk {
    param([string]$ManualPath, [string]$RootPath)
    $localSetting = Get-LocalSdkSetting $RootPath
    $candidates = if ($ManualPath) {
        @([pscustomobject]@{ Path = $ManualPath; Source = 'build.ps1 手动配置' })
    } else {
        @(
            [pscustomobject]@{ Path = $localSetting.Path; Source = 'local.properties' }
            foreach ($name in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
                [pscustomobject]@{ Path = [Environment]::GetEnvironmentVariable($name); Source = $name }
            }
            if ($env:LOCALAPPDATA) { [pscustomobject]@{ Path = (Join-Path $env:LOCALAPPDATA 'Android/Sdk'); Source = 'Android SDK 默认目录' } }
            foreach ($command in @(Get-Command adb.exe -CommandType Application -All -ErrorAction SilentlyContinue)) {
                [pscustomobject]@{ Path = (Split-Path -Parent (Split-Path -Parent $command.Source)); Source = 'PATH (adb)' }
            }
        )
    }
    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate.Path)) { continue }
        try {
            $sdkPath = ConvertTo-BuildPath $candidate.Path $RootPath
            if (-not (Test-Path -LiteralPath $sdkPath -PathType Container)) { throw '目录不存在' }
            $sdkFolders = @('platforms', 'build-tools', 'cmdline-tools', 'platform-tools')
            $found = @($sdkFolders | Where-Object { Test-Path -LiteralPath (Join-Path $sdkPath $_) -PathType Container })
            if ($found.Count -eq 0) { throw '目录中没有 Android SDK 组件' }
            return [pscustomobject]@{ Path = $sdkPath; Source = $candidate.Source }
        } catch {
            if ($ManualPath) { throw "build.ps1 的 `$ManualAndroidSdk 无效：$($_.Exception.Message)" }
            Write-Warning "跳过 $($candidate.Source) 的 SDK：$($_.Exception.Message)"
        }
    }
    throw '未找到 Android SDK。请通过 Android Studio 安装 SDK，设置 ANDROID_HOME，或填写 build.ps1 顶部的 $ManualAndroidSdk（SDK 根目录，不是 platform-tools）。'
}

function Set-BuildLocalSdk {
    param([string]$SdkPath, [string]$RootPath)
    $setting = Get-LocalSdkSetting $RootPath
    if ($setting.Path) {
        try { if ((ConvertTo-BuildPath $setting.Path $RootPath) -eq $SdkPath) { return } } catch { }
    }
    # 仅同步被 Git 忽略的本机 SDK 配置，保留其他属性和注释。
    # properties 由 Java 按 Latin-1 读取，非 ASCII 路径用标准 Unicode 转义保存。
    $value = $SdkPath.Replace('\', '/').Replace(':', '\:')
    $value = [regex]::Replace($value, '[^\x20-\x7e]', { param($match) '\u{0:x4}' -f [int][char]$match.Value })
    $text = (@($setting.OtherLines) + "sdk.dir=$value") -join "`n"
    [IO.File]::WriteAllText($setting.File, $text + "`n", [Text.UTF8Encoding]::new($false))
    Write-Host '已同步 local.properties 的 sdk.dir（本机配置，不提交到 Git）。'
}
