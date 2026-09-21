#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApkSignerPath,
    [Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9:]{64,95}$')][string]$CertificateSha256,
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86', 'universal')][string]$Abi = 'arm64-v8a',
    [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$rootPath = Split-Path -Parent $PSScriptRoot
$utf8 = [Text.UTF8Encoding]::new($false)

function Invoke-CheckedGit {
    param([string[]]$Arguments)
    $result = @(& git @Arguments)
    if ($LASTEXITCODE -ne 0) { throw 'Git 检查失败，请确认当前提交和版本标签。' }
    return $result
}

Push-Location -LiteralPath $rootPath
try {
    if (-not (Test-Path -LiteralPath $ApkSignerPath -PathType Leaf)) { throw '找不到 apksigner，请指定 Android Build Tools 中的完整路径。' }
    $ApkSignerPath = (Resolve-Path -LiteralPath $ApkSignerPath).Path
    $expectedCertificate = $CertificateSha256.Replace(':', '').ToLowerInvariant()
    if ($expectedCertificate -notmatch '^[a-f0-9]{64}$') { throw '证书 SHA-256 指纹必须包含 64 位十六进制数字。' }
    $version = ConvertFrom-StringData (Get-Content -LiteralPath version.properties -Encoding utf8 -Raw)
    $versionName = $version.versionName
    $versionCode = [int]$version.versionCode
    if ($versionName -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$' -or $versionCode -le 0) { throw '版本配置无效。' }
    $tag = "v$versionName"
    $status = @(Invoke-CheckedGit -Arguments @('status', '--porcelain', '--untracked-files=normal'))
    if ($status.Count -ne 0) { throw '工作区存在未提交文件。请先审查并提交变更，再从对应标签准备发行包。' }
    $commit = (Invoke-CheckedGit -Arguments @('rev-parse', 'HEAD')) -join ''
    $tagCommit = (Invoke-CheckedGit -Arguments @('rev-parse', '--verify', "refs/tags/$tag^{commit}")) -join ''
    if ($commit -ne $tagCommit) { throw 'HEAD 与版本标签不一致。请切换到待发布标签对应的提交。' }
    foreach ($name in @('NOVELIA_KEYSTORE_PATH', 'NOVELIA_KEYSTORE_PASSWORD', 'NOVELIA_KEY_ALIAS', 'NOVELIA_KEY_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "缺少签名环境变量：$name。请在本机安全配置，不要粘贴到 Issue 或聊天中。" }
    }
    $outputPath = Join-Path $rootPath "outputs/releases/$tag-$Abi"
    if (Test-Path -LiteralPath $outputPath) { throw '发行目录已存在；为避免覆盖，先自行核对并归档旧目录。' }

    $tasks = @(':app:testReleaseUnitTest', ':app:lintRelease', ':app:assembleRelease', '-PreleaseSigning=true')
    if ($Abi -ne 'universal') { $tasks += "-PtargetAbi=$Abi" }
    & (Join-Path $rootPath 'build.ps1') -Tasks $tasks -Offline:$Offline

    $apkDirectory = Join-Path $rootPath 'app/build/outputs/apk/release'
    $metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Encoding utf8 -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'cc.novelia.app' -or @($metadata.elements).Count -ne 1) { throw 'APK 输出信息与预期不符。' }
    $element = $metadata.elements[0]
    if ($element.versionName -ne $versionName -or $element.versionCode -ne $versionCode) { throw 'APK 版本与 version.properties 不一致。' }
    $apk = [IO.Path]::GetFullPath((Join-Path $apkDirectory $element.outputFile))
    if (-not $apk.StartsWith($apkDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'APK 输出路径不在构建目录内。' }
    $verification = @(& $ApkSignerPath verify --verbose --print-certs $apk)
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名验证失败。' }
    $verificationText = $verification -join "`n"
    if ($verificationText -match '(?i)CN=Android Debug') { throw '检测到 Android Debug 证书，拒绝整理为正式发行版。' }
    $certificateMatches = [regex]::Matches($verificationText, '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([a-f0-9]{64})\s*$')
    if ($certificateMatches.Count -ne 1 -or $certificateMatches[0].Groups[1].Value.ToLowerInvariant() -ne $expectedCertificate) {
        throw 'APK 签名证书与指定的长期发布证书指纹不一致。'
    }
    if (@(Invoke-CheckedGit -Arguments @('status', '--porcelain', '--untracked-files=normal')).Count -ne 0 -or
        ((Invoke-CheckedGit -Arguments @('rev-parse', 'HEAD')) -join '') -ne $commit -or
        ((Invoke-CheckedGit -Arguments @('rev-parse', '--verify', "refs/tags/$tag^{commit}")) -join '') -ne $commit) {
        throw '构建期间源码或标签发生变化，拒绝生成来源不一致的发行附件。'
    }

    New-Item -ItemType Directory -Path $outputPath | Out-Null
    $stem = "Novelia-$versionName-$Abi"
    Copy-Item -LiteralPath $apk -Destination (Join-Path $outputPath "$stem.apk")
    Copy-Item -LiteralPath 'app/build/generated/openSourceAssets/open-source/NOTICE.txt' -Destination (Join-Path $outputPath 'OPEN_SOURCE_NOTICES.txt')
    Copy-Item -LiteralPath 'CHANGELOG.md' -Destination $outputPath
    $sourceArchive = Join-Path $outputPath "Novelia-$versionName-source.zip"
    Invoke-CheckedGit -Arguments @('archive', '--format=zip', "--prefix=Novelia-$versionName/", "--output=$sourceArchive", $commit) | Out-Null
    Compress-Archive -LiteralPath 'app/build/outputs/mapping/release/mapping.txt' -DestinationPath (Join-Path $outputPath "$stem-mapping.zip")
    $releaseMetadata = [ordered]@{
        repository = 'https://github.com/nobYoQ/novelia'
        tag = $tag
        commit = $commit
        versionName = $versionName
        versionCode = $versionCode
        abi = $Abi
        certificateSha256 = $expectedCertificate
    } | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $outputPath "$stem-metadata.json"), $releaseMetadata + "`n", $utf8)
    $checksums = foreach ($file in Get-ChildItem -LiteralPath $outputPath -File | Sort-Object Name) {
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($file.Name)"
    }
    [IO.File]::WriteAllText((Join-Path $outputPath "SHA256SUMS-$Abi.txt"), ($checksums -join "`n") + "`n", $utf8)
    Write-Output "发行附件已准备：$outputPath"
    Write-Output '请完成设备验收后上传 GitHub Release 草稿；本脚本不会推送代码、上传文件或发布。'
} finally {
    Pop-Location
}
