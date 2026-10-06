#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ArtifactsDirectory,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')][string]$Tag = 'preview'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$rootPath = Split-Path -Parent $PSScriptRoot

function Invoke-PreviewGh {
    param([string[]]$Arguments, [switch]$AllowNotFound)
    $response = (& gh @Arguments 2>&1) -join "`n"
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        if ($AllowNotFound -and $response -match '\bHTTP 404\b') { return $null }
        $status = if ($response -match '\bHTTP [0-9]{3}\b') { $Matches[0] } else { 'no HTTP status' }
        $operation = if ($Arguments[0] -eq 'api') { "$($Arguments[2]) $($Arguments[3])" } else { $Arguments[0] }
        throw "GitHub CLI failed ($status, exit $exitCode) during $operation."
    }
    return $response
}

function Get-PreviewJson {
    param([string]$Endpoint, [switch]$AllowNotFound, [switch]$Paginate)
    $arguments = @('api', '--method', 'GET', $Endpoint, '-H', 'X-GitHub-Api-Version: 2026-03-10')
    if ($Paginate) { $arguments += '--paginate', '--slurp' }
    $response = Invoke-PreviewGh -Arguments $arguments -AllowNotFound:$AllowNotFound
    if ($null -eq $response) { return $null }
    return ($response | ConvertFrom-Json -NoEnumerate)
}

function Get-PreviewRelease {
    param([string]$Repository, [string]$EncodedTag, [string]$Tag)
    # The tag endpoint only resolves published releases. Drafts need the list endpoint.
    $published = Get-PreviewJson -Endpoint "repos/$Repository/releases/tags/$EncodedTag" -AllowNotFound
    if ($null -ne $published) { return $published }
    $pages = Get-PreviewJson -Endpoint "repos/$Repository/releases?per_page=100" -Paginate
    $matchingReleases = @($pages | ForEach-Object { $_ } | Where-Object { $_.tag_name -eq $Tag })
    if ($matchingReleases.Count -gt 1) { throw 'Multiple releases use the preview tag; refusing an ambiguous update.' }
    if ($matchingReleases.Count -eq 1) { return $matchingReleases[0] }
    return $null
}

function Test-PreviewHead {
    $branch = [Uri]::EscapeDataString($env:PREVIEW_DEFAULT_BRANCH)
    $head = Get-PreviewJson -Endpoint "repos/$env:GITHUB_REPOSITORY/git/ref/heads/$branch"
    return $head.object.sha -eq $env:GITHUB_SHA
}

function Write-PreviewSummary {
    param([string]$Message)
    $Message | Out-File -LiteralPath $env:GITHUB_STEP_SUMMARY -Append -Encoding utf8
    Write-Host $Message
}

if ([string]::IsNullOrWhiteSpace($env:GH_TOKEN)) { throw 'Missing GitHub workflow token.' }
if ($env:GITHUB_REPOSITORY -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' -or
    $env:GITHUB_SHA -notmatch '^[0-9a-f]{40}$') { throw 'Invalid repository or commit.' }
if ([string]::IsNullOrWhiteSpace($env:PREVIEW_DEFAULT_BRANCH) -or
    $env:GITHUB_REF -ne "refs/heads/$env:PREVIEW_DEFAULT_BRANCH") { throw 'Only the default branch may update the preview release.' }
foreach ($name in @('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')) {
    if ([Environment]::GetEnvironmentVariable($name) -notmatch '^[0-9]+$') { throw "Invalid $name." }
}

$sourceDirectory = [IO.Path]::GetFullPath($ArtifactsDirectory, $rootPath)
$apks = @(Get-ChildItem -LiteralPath $sourceDirectory -Filter '*.apk' -File)
if ($apks.Count -ne 1) { throw 'Expected exactly one verified preview APK.' }
$apk = $apks[0]
$sourceStem = $apk.BaseName
$checksumPath = "$($apk.FullName).sha256"
$mappingPath = Join-Path $sourceDirectory "$sourceStem-mapping.txt"
$infoPath = Join-Path $sourceDirectory "$sourceStem-build-info.txt"
foreach ($path in @($checksumPath, $mappingPath, $infoPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Missing preview checksum, build info or R8 mapping.' }
}
$hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$recordedHash = (Get-Content -LiteralPath $checksumPath -Raw -Encoding utf8).Trim().Split(' ')[0]
if ($hash -ne $recordedHash) { throw 'Downloaded preview APK checksum mismatch.' }
$info = @{}
foreach ($line in (Get-Content -LiteralPath $infoPath -Encoding utf8)) {
    $pair = $line.Split('=', 2)
    if ($pair.Count -eq 2) { $info[$pair[0]] = $pair[1] }
}
if ($info['commit'] -ne $env:GITHUB_SHA -or $info['variant'] -ne 'release' -or $info['abi'] -ne 'universal') {
    throw 'Preview build info does not match this Release build.'
}
if (-not (Test-PreviewHead)) {
    Write-PreviewSummary '跳过预发布更新：默认分支已有更新的提交，本次 APK 仍可从 Actions 下载。'
    return
}

$repository = $env:GITHUB_REPOSITORY
$encodedTag = [Uri]::EscapeDataString($Tag)
$release = Get-PreviewRelease -Repository $repository -EncodedTag $encodedTag -Tag $Tag
if ($null -ne $release) {
    if (-not $release.prerelease) { throw 'The selected tag belongs to a regular release; refusing to replace it.' }
    if ($release.PSObject.Properties['immutable'] -and $release.immutable) {
        throw 'The preview release is immutable. Use a mutable preview tag; immutable assets cannot be replaced.'
    }
}

# Staging names keep the previous download available until every new upload succeeds.
$generation = "$env:GITHUB_RUN_ID-$env:GITHUB_RUN_ATTEMPT"
$stageDirectory = Join-Path $rootPath "outputs/preview-release/$generation"
[IO.Directory]::CreateDirectory($stageDirectory) | Out-Null
$names = @('Novelia-preview-universal.apk', 'Novelia-preview-universal.apk.sha256', 'Novelia-preview-build-info.txt', 'Novelia-preview-mapping.zip')
$staged = @($names | ForEach-Object {
    [pscustomobject]@{ Name = $_; StagedName = "Novelia-preview-staging-$generation-$_"; Path = (Join-Path $stageDirectory "Novelia-preview-staging-$generation-$_") }
})
Copy-Item -LiteralPath $apk.FullName -Destination $staged[0].Path -Force
"$hash  $($names[0])" | Set-Content -LiteralPath $staged[1].Path -Encoding utf8NoBOM
Copy-Item -LiteralPath $infoPath -Destination $staged[2].Path -Force
Compress-Archive -LiteralPath $mappingPath -DestinationPath $staged[3].Path -CompressionLevel Optimal -Force
$releaseUrl = "$env:GITHUB_SERVER_URL/$repository/releases/tag/$encodedTag"
$apkUrl = "$env:GITHUB_SERVER_URL/$repository/releases/download/$encodedTag/$($names[0])"
$notesPath = Join-Path $stageDirectory 'release-notes.md'
@(
    '此 Pre-release 随默认分支的成功构建自动更新，附件会被后续预览替换。'
    ''
    "- 应用版本：$($info['versionName']) ($($info['versionCode']))"
    "- 源码提交：[$env:GITHUB_SHA]($env:GITHUB_SERVER_URL/$repository/commit/$env:GITHUB_SHA)"
    "- 构建记录：[GitHub Actions]($env:GITHUB_SERVER_URL/$repository/actions/runs/$env:GITHUB_RUN_ID)"
    "- [下载 Release 通用 APK]($apkUrl)"
    ''
    '启用 R8 压缩和资源收缩，与本地构建共用固定预览测试证书。同签名本地包可覆盖安装，新包版本码须不低于已安装版本；其他签名来源迁移前请先备份阅读资料。'
) | Set-Content -LiteralPath $notesPath -Encoding utf8NoBOM
$title = "Novelia $($info['versionName']) 预览版"
if ($null -eq $release) {
    Invoke-PreviewGh -Arguments @('release', 'create', $Tag, '--repo', $repository, '--draft', '--prerelease', '--latest=false', '--target', $env:GITHUB_SHA, '--title', $title, '--notes-file', $notesPath) | Out-Null
    $release = Get-PreviewRelease -Repository $repository -EncodedTag $encodedTag -Tag $Tag
    if ($null -eq $release) { throw 'The newly created preview draft could not be located.' }
}
Invoke-PreviewGh -Arguments (@('release', 'upload', $Tag, '--repo', $repository, '--clobber') + @($staged.Path)) | Out-Null
$assetPages = Get-PreviewJson -Endpoint "repos/$repository/releases/$($release.id)/assets?per_page=100" -Paginate
$assets = @($assetPages | ForEach-Object { $_ })
$newAssets = @{}
foreach ($file in $staged) {
    $matchingAssets = @($assets | Where-Object { $_.name -eq $file.StagedName })
    if ($matchingAssets.Count -ne 1 -or $matchingAssets[0].state -ne 'uploaded' -or $matchingAssets[0].size -ne (Get-Item -LiteralPath $file.Path).Length) {
        throw 'A staged Release asset is missing or incomplete; previous preview assets were preserved.'
    }
    $asset = $matchingAssets[0]
    $expectedDigest = 'sha256:' + (Get-FileHash -LiteralPath $file.Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($asset.PSObject.Properties['digest'] -and $asset.digest -and $asset.digest -ne $expectedDigest) {
        throw 'A staged Release asset digest does not match; previous preview assets were preserved.'
    }
    $newAssets[$file.Name] = $asset
}
if (-not (Test-PreviewHead)) {
    foreach ($asset in $newAssets.Values) {
        Invoke-PreviewGh -Arguments @('api', '--method', 'DELETE', "repos/$repository/releases/assets/$($asset.id)") | Out-Null
    }
    Write-PreviewSummary '跳过预发布替换：上传期间默认分支已有更新，上一版预览附件已保留。'
    return
}

# Move only the rolling preview tag; versioned distribution tags are not selected automatically.
$tagEndpoint = "repos/$repository/git/ref/tags/$encodedTag"
$tagRef = Get-PreviewJson -Endpoint $tagEndpoint -AllowNotFound
if ($null -eq $tagRef) {
    Invoke-PreviewGh -Arguments @('api', '--method', 'POST', "repos/$repository/git/refs", '-f', "ref=refs/tags/$Tag", '-f', "sha=$env:GITHUB_SHA") | Out-Null
} elseif ($tagRef.object.sha -ne $env:GITHUB_SHA) {
    Invoke-PreviewGh -Arguments @('api', '--method', 'PATCH', "repos/$repository/git/refs/tags/$encodedTag", '-f', "sha=$env:GITHUB_SHA", '-F', 'force=true') | Out-Null
}
foreach ($file in $staged) {
    foreach ($old in @($assets | Where-Object { $_.name -eq $file.Name })) {
        Invoke-PreviewGh -Arguments @('api', '--method', 'DELETE', "repos/$repository/releases/assets/$($old.id)") | Out-Null
    }
    Invoke-PreviewGh -Arguments @('api', '--method', 'PATCH', "repos/$repository/releases/assets/$($newAssets[$file.Name].id)", '-f', "name=$($file.Name)") | Out-Null
}
Invoke-PreviewGh -Arguments @('release', 'edit', $Tag, '--repo', $repository, '--draft=false', '--prerelease', '--latest=false', '--target', $env:GITHUB_SHA, '--title', $title, '--notes-file', $notesPath) | Out-Null
$newIds = @($newAssets.Values.id)
foreach ($oldStage in @($assets | Where-Object { $_.name.StartsWith('Novelia-preview-staging-', [StringComparison]::Ordinal) -and $_.id -notin $newIds })) {
    Invoke-PreviewGh -Arguments @('api', '--method', 'DELETE', "repos/$repository/releases/assets/$($oldStage.id)") | Out-Null
}
Write-PreviewSummary "[Preview Pre-release]($releaseUrl) 已更新，固定下载地址：[Release APK]($apkUrl)。"
