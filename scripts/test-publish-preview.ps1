#requires -Version 7.0
# 使用模拟的 gh 命令验证发布行为，不连接 GitHub。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$rootPath = Split-Path -Parent $PSScriptRoot
$publicationPath = Join-Path $PSScriptRoot 'publish-preview.ps1'

function gh {
    $arguments = @($args)
    $fixture = $global:NoveliaPreviewFixture
    $fixture.Calls.Add(($arguments -join ' '))
    $global:LASTEXITCODE = 0
    if ($arguments[0] -eq 'release') {
        switch ($arguments[1]) {
            'create' {
                $fixture.Created = $true
                return 'https://github.com/example/novelia/releases/untagged/draft-fixture'
            }
            'upload' {
                if ($fixture.Mode -eq 'upload-failure') {
                    $global:LASTEXITCODE = 1
                    return 'gh: Service Unavailable (HTTP 503)'
                }
                foreach ($path in @($arguments | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })) {
                    $fixture.NextId++
                    $fixture.Assets.Add([pscustomobject]@{
                        id = $fixture.NextId
                        name = [IO.Path]::GetFileName($path)
                        state = 'uploaded'
                        size = (Get-Item -LiteralPath $path).Length
                        digest = 'sha256:' + (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
                    })
                }
                return ''
            }
            'edit' {
                $fixture.Published = $true
                return ''
            }
        }
    }
    if ($arguments[0] -eq 'api') {
        $method = $arguments[2]
        $endpoint = $arguments[3]
        if ($method -eq 'GET') {
            if ($endpoint.EndsWith('/git/ref/heads/main')) {
                if ($fixture.Mode -eq 'head-not-found') {
                    $global:LASTEXITCODE = 1
                    return 'gh: Not Found (HTTP 404)'
                }
                $fixture.HeadReads++
                $sha = if ($fixture.Mode -eq 'stale' -or ($fixture.Mode -eq 'race' -and $fixture.HeadReads -gt 1)) { '2' * 40 } else { $env:GITHUB_SHA }
                return (@{ object = @{ sha = $sha } } | ConvertTo-Json -Compress)
            }
            if ($endpoint.EndsWith('/releases/tags/preview')) {
                if ($fixture.Mode -eq 'forbidden') {
                    $global:LASTEXITCODE = 1
                    return 'gh: Resource not accessible by integration (HTTP 403)'
                }
                if ($fixture.Mode -in @('create', 'resume-draft', 'duplicate-drafts') -and -not $fixture.Published) {
                    $global:LASTEXITCODE = 1
                    return 'gh: Not Found (HTTP 404)'
                }
                return (@{ id = 7; prerelease = ($fixture.Mode -ne 'regular'); immutable = ($fixture.Mode -eq 'immutable') } | ConvertTo-Json -Compress)
            }
            if ($endpoint.EndsWith('/releases?per_page=100')) {
                $pages = [object[]]::new(2)
                $pages[0] = @(@{ id = 8; tag_name = 'v0.0.1'; draft = $false; prerelease = $false; immutable = $true })
                $pages[1] = @()
                if ($fixture.Created -or $fixture.Mode -in @('resume-draft', 'duplicate-drafts')) {
                    $pages[1] = @(@{ id = 7; tag_name = 'preview'; draft = $true; prerelease = $true; immutable = $false })
                }
                if ($fixture.Mode -eq 'duplicate-drafts') {
                    $pages[1] += @{ id = 9; tag_name = 'preview'; draft = $true; prerelease = $true; immutable = $false }
                }
                return (ConvertTo-Json -InputObject $pages -Depth 5 -Compress)
            }
            if ($endpoint.EndsWith('/assets?per_page=100')) {
                if ($fixture.Mode -eq 'digest-mismatch') { $fixture.Assets[$fixture.Assets.Count - 1].digest = 'sha256:bad' }
                # gh api --paginate --slurp returns an outer array of page arrays.
                $assetList = $fixture.Assets.ToArray()
                $pages = [object[]]::new(2)
                $pages[0] = @($assetList | Select-Object -First 3)
                $pages[1] = @($assetList | Select-Object -Skip 3)
                return (ConvertTo-Json -InputObject $pages -Depth 5 -Compress)
            }
            if ($endpoint.EndsWith('/git/ref/tags/preview')) {
                if ($null -eq $fixture.TagSha) {
                    $global:LASTEXITCODE = 1
                    return 'gh: Not Found (HTTP 404)'
                }
                return (@{ object = @{ sha = $fixture.TagSha } } | ConvertTo-Json -Compress)
            }
        }
        if ($method -eq 'POST' -and $endpoint.EndsWith('/git/refs')) {
            $fixture.TagSha = $env:GITHUB_SHA
            return '{}'
        }
        if ($method -eq 'PATCH' -and $endpoint.EndsWith('/git/refs/tags/preview')) {
            if ($fixture.Mode -eq 'tag-protected') {
                $global:LASTEXITCODE = 1
                return 'gh: Resource not accessible by integration (HTTP 403)'
            }
            $fixture.TagSha = $env:GITHUB_SHA
            return '{}'
        }
        if ($endpoint -match '/releases/assets/([0-9]+)$') {
            $assetId = [int]$Matches[1]
            $asset = @($fixture.Assets | Where-Object { $_.id -eq $assetId })[0]
            if ($method -eq 'DELETE') {
                $fixture.Deleted.Add($assetId)
                $fixture.Assets.Remove($asset) | Out-Null
                return ''
            }
            if ($method -eq 'PATCH') {
                $field = @($arguments | Where-Object { $_.StartsWith('name=') })[0]
                $asset.name = $field.Substring(5)
                return '{}'
            }
        }
    }
    throw "Unexpected mock GitHub command: $($arguments -join ' ')"
}

function Assert-PreviewTest {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$environment = @{
    GH_TOKEN = 'validation-placeholder'
    GITHUB_REPOSITORY = 'example/novelia'
    GITHUB_SERVER_URL = 'https://github.com'
    GITHUB_SHA = '1' * 40
    GITHUB_REF = 'refs/heads/main'
    PREVIEW_DEFAULT_BRANCH = 'main'
    GITHUB_RUN_ID = '12345'
    GITHUB_RUN_ATTEMPT = '1'
    GITHUB_STEP_SUMMARY = ''
}
$previousEnvironment = @{}
foreach ($name in $environment.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
    [Environment]::SetEnvironmentVariable($name, $environment[$name], 'Process')
}
try {
    $modes = @('create', 'resume-draft', 'update', 'duplicate-drafts', 'head-not-found', 'stale', 'race', 'upload-failure', 'digest-mismatch', 'tag-protected', 'immutable', 'regular', 'forbidden', 'checksum-mismatch', 'non-default')
    foreach ($mode in $modes) {
        $fixtureDirectory = Join-Path $rootPath ('outputs/ci-checks/publish-tests/' + $mode + '-' + [Guid]::NewGuid().ToString('N'))
        [IO.Directory]::CreateDirectory($fixtureDirectory) | Out-Null
        $apkPath = Join-Path $fixtureDirectory 'Novelia-fixture.apk'
        'synthetic APK fixture' | Set-Content -LiteralPath $apkPath -Encoding utf8NoBOM
        $hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($mode -eq 'checksum-mismatch') { $hash = '0' * 64 }
        "$hash  Novelia-fixture.apk" | Set-Content -LiteralPath "$apkPath.sha256" -Encoding utf8NoBOM
        'synthetic R8 mapping' | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'Novelia-fixture-mapping.txt') -Encoding utf8NoBOM
        @("commit=$env:GITHUB_SHA", 'versionName=0.0.1', 'versionCode=1', 'variant=release', 'abi=universal') |
            Set-Content -LiteralPath (Join-Path $fixtureDirectory 'Novelia-fixture-build-info.txt') -Encoding utf8NoBOM
        $env:GITHUB_STEP_SUMMARY = Join-Path $fixtureDirectory 'summary.md'
        $env:GITHUB_REF = if ($mode -eq 'non-default') { 'refs/heads/feature' } else { 'refs/heads/main' }
        $global:NoveliaPreviewFixture = @{
            Mode = $mode
            Calls = [Collections.Generic.List[string]]::new()
            Deleted = [Collections.Generic.List[int]]::new()
            Assets = [Collections.Generic.List[object]]::new()
            NextId = 100
            Created = $false
            Published = $false
            HeadReads = 0
            TagSha = $(if ($mode -in @('create', 'resume-draft')) { $null } else { '0' * 40 })
        }
        $fixture = $global:NoveliaPreviewFixture
        $stableNames = @('Novelia-preview-universal.apk', 'Novelia-preview-universal.apk.sha256', 'Novelia-preview-build-info.txt', 'Novelia-preview-mapping.zip')
        if ($mode -ne 'create') {
            for ($index = 0; $index -lt $stableNames.Count; $index++) {
                $fixture.Assets.Add([pscustomobject]@{ id = $index + 1; name = $stableNames[$index]; state = 'uploaded'; size = 1; digest = 'sha256:old' })
            }
            $fixture.Assets.Add([pscustomobject]@{ id = 5; name = 'manual-notes.txt'; state = 'uploaded'; size = 1; digest = 'sha256:manual' })
            $fixture.Assets.Add([pscustomobject]@{ id = 6; name = 'Novelia-preview-staging-999-1-leftover.apk'; state = 'uploaded'; size = 1; digest = 'sha256:leftover' })
        }
        $failure = $null
        try { & $publicationPath -ArtifactsDirectory $fixtureDirectory -Tag 'preview' *> $null }
        catch { $failure = $_.Exception.Message }
        if ($mode -in @('create', 'resume-draft', 'update')) {
            Assert-PreviewTest ($null -eq $failure) "$mode failed: $failure"
            Assert-PreviewTest ($fixture.Published -and $fixture.TagSha -eq $env:GITHUB_SHA) "$mode did not publish the expected commit."
            foreach ($name in $stableNames) {
                Assert-PreviewTest (@($fixture.Assets | Where-Object { $_.name -eq $name }).Count -eq 1) "$mode did not replace $name."
            }
            Assert-PreviewTest (@($fixture.Assets | Where-Object { $_.name.StartsWith('Novelia-preview-staging-') }).Count -eq 0) "$mode left staged assets after success."
            Assert-PreviewTest ($fixture.Calls.Exists([Predicate[string]]{ param($call) $call.Contains('--latest=false') })) "$mode could change the stable Latest release."
            if ($mode -eq 'resume-draft') {
                Assert-PreviewTest (-not $fixture.Created) 'An existing draft was not reused.'
            }
            if ($mode -eq 'update') {
                Assert-PreviewTest (-not $fixture.Deleted.Contains(5)) 'A manually attached release asset was removed.'
                $uploadIndex = $fixture.Calls.FindIndex([Predicate[string]]{ param($call) $call.StartsWith('release upload ') })
                $deleteIndex = $fixture.Calls.FindIndex([Predicate[string]]{ param($call) $call.StartsWith('api --method DELETE ') })
                Assert-PreviewTest ($deleteIndex -gt $uploadIndex) 'Old release assets were deleted before upload success.'
            }
        } else {
            $shouldFail = $mode -notin @('stale', 'race')
            Assert-PreviewTest (($null -ne $failure) -eq $shouldFail) "$mode returned an unexpected result: $failure"
            Assert-PreviewTest (-not $fixture.Published) "$mode published unexpectedly."
            foreach ($id in @(1, 2, 3, 4, 5)) {
                Assert-PreviewTest (-not $fixture.Deleted.Contains($id)) "$mode removed the previous preview or a manual attachment."
            }
            Assert-PreviewTest ($fixture.TagSha -eq ('0' * 40)) "$mode moved the preview tag unexpectedly."
            Assert-PreviewTest (-not $fixture.Created) "$mode created a release unexpectedly."
            if ($mode -eq 'head-not-found') {
                Assert-PreviewTest ($failure.Contains('GET repos/example/novelia/git/ref/heads/main')) 'API diagnostics omitted the failing endpoint.'
            }
        }
        Write-Output "PASS: $mode"
    }
    Write-Output "Preview publication checks passed: $($modes.Count) scenarios, no network requests."
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
