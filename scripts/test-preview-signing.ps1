#requires -Version 7.0
# 校验现有密钥与失败保护；gh 由本进程内的替身代替，不连接 GitHub。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$nativeExitVariable = Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
$hadNativeExitCode = $null -ne $nativeExitVariable
$previousNativeExitCode = if ($hadNativeExitCode) { $nativeExitVariable.Value } else { $null }
$rootPath = Split-Path -Parent $PSScriptRoot
$configure = Join-Path $PSScriptRoot 'configure-preview-signing.ps1'
$fixturePath = Join-Path $rootPath 'outputs/ci-checks/preview-signing'
[IO.Directory]::CreateDirectory($fixturePath) | Out-Null
$signingFixture = [pscustomobject]@{ Mode = 'success'; SecretWrites = 0 }

function gh {
    [CmdletBinding(PositionalBinding = $false)]
    param(
        [Parameter(ValueFromRemainingArguments, Position = 0)][string[]]$CliArguments,
        [Parameter(ValueFromPipeline)][string]$InputValue
    )
    end {
        $global:LASTEXITCODE = 0
        if (($CliArguments -join ' ') -eq 'auth status --hostname github.com') {
            if ($signingFixture.Mode -eq 'unauthenticated') { $global:LASTEXITCODE = 1 }
            return
        }
        if (($CliArguments -join ' ') -ne 'secret set NOVELIA_PREVIEW_KEYSTORE_BASE64 --repo nobYoQ/novelia --app actions') {
            throw 'Unexpected gh command; refusing any real request.'
        }
        $signingFixture.SecretWrites++
        $decoded = [Convert]::FromBase64String($InputValue)
        $original = [IO.File]::ReadAllBytes((Join-Path $rootPath '.android/debug.keystore'))
        try {
            if (-not [Linq.Enumerable]::SequenceEqual[byte]($decoded, $original)) {
                throw 'stdin did not contain the original key.'
            }
        } finally {
            [Array]::Clear($decoded, 0, $decoded.Length)
            [Array]::Clear($original, 0, $original.Length)
            $InputValue = $null
        }
        if ($signingFixture.Mode -eq 'upload-failure') { $global:LASTEXITCODE = 1 }
    }
}

function Assert-SigningFailure {
    param([string]$Name, [scriptblock]$Action, [string]$ExpectedMessage)
    $failed = $false
    try { & $Action 6>$null } catch {
        if ($_.Exception.Message -notmatch $ExpectedMessage) { throw "Unexpected failure in $Name." }
        $failed = $true
    }
    if (-not $failed) { throw "$Name should have failed." }
    Write-Host "PASS: $Name"
}

$environmentNames = @('RUNNER_TEMP', 'GITHUB_ENV', 'NOVELIA_PREVIEW_KEYSTORE_BASE64', 'NOVELIA_PREVIEW_KEYSTORE_PATH')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }
$invalidKeystore = Join-Path $fixturePath 'invalid.keystore'
$publicCertificatePath = Join-Path $fixturePath 'public-only.cer'
$publicKeystorePath = Join-Path $fixturePath 'public-only.keystore'
try {
    $env:NOVELIA_PREVIEW_KEYSTORE_PATH = $null
    & $configure -CheckOnly 6>$null
    Write-Host 'PASS: original certificate'
    if ($signingFixture.SecretWrites -ne 0) { throw 'CheckOnly attempted to upload a secret.' }
    Assert-SigningFailure 'missing key' { & $configure -KeystorePath (Join-Path $fixturePath 'missing.keystore') -CheckOnly } '缺少原有预览密钥'
    [IO.File]::WriteAllText($invalidKeystore, 'invalid test data', [Text.UTF8Encoding]::new($false))
    Assert-SigningFailure 'invalid key' { & $configure -KeystorePath $invalidKeystore -CheckOnly } '读取预览证书失败'

    # 只保存公开证书的测试密钥库，私钥始终留在内存，不导出到文件。
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    try {
        $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
            'CN=Preview signing test', $rsa, [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $certificate = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-1), [DateTimeOffset]::UtcNow.AddDays(1))
        try { [IO.File]::WriteAllBytes($publicCertificatePath, $certificate.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert)) }
        finally { $certificate.Dispose() }
    } finally { $rsa.Dispose() }
    if (Test-Path -LiteralPath $publicKeystorePath -PathType Leaf) { Remove-Item -LiteralPath $publicKeystorePath }
    $previousPassword = $env:NOVELIA_PREVIEW_STORE_PASSWORD
    try {
        $env:NOVELIA_PREVIEW_STORE_PASSWORD = 'android'
        $importOutput = & keytool -importcert -noprompt -alias androiddebugkey -file $publicCertificatePath -keystore $publicKeystorePath -storepass:env NOVELIA_PREVIEW_STORE_PASSWORD 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Unable to prepare the public certificate fixture.' }
    } finally { [Environment]::SetEnvironmentVariable('NOVELIA_PREVIEW_STORE_PASSWORD', $previousPassword, 'Process') }
    Assert-SigningFailure 'wrong certificate' { & $configure -KeystorePath $publicKeystorePath -CheckOnly } '证书指纹不匹配'

    $signingFixture.Mode = 'unauthenticated'
    Assert-SigningFailure 'missing login' { & $configure } '未登录'
    if ($signingFixture.SecretWrites -ne 0) { throw 'Invalid signing or authentication attempted to upload a secret.' }
    $signingFixture.Mode = 'success'
    & $configure 6>$null
    if ($signingFixture.SecretWrites -ne 1) { throw 'Expected one stdin upload.' }
    Write-Host 'PASS: original key uploaded only through stdin'
    $signingFixture.Mode = 'upload-failure'
    Assert-SigningFailure 'upload failure' { & $configure } '配置 GitHub Secret 失败'

    # 直接运行工作流中的恢复步骤，验证缺失与错误编码均不会生成密钥。
    $workflow = Get-Content -LiteralPath (Join-Path $rootPath '.github/workflows/preview-apk.yml') -Encoding utf8
    $restoreLines = [Collections.Generic.List[string]]::new()
    $inRestore = $false
    $inRun = $false
    foreach ($line in $workflow) {
        if ($line -eq '      - name: Restore shared preview signing key') { $inRestore = $true; continue }
        if (-not $inRestore) { continue }
        if ($line -eq '        run: |') { $inRun = $true; continue }
        if ($inRun) {
            if ($line.StartsWith('          ')) { $restoreLines.Add($line.Substring(10)) }
            else { break }
        }
    }
    if ($restoreLines.Count -eq 0) { throw 'Preview signing restore step is missing.' }
    $restore = [ScriptBlock]::Create($restoreLines -join "`n")
    $env:RUNNER_TEMP = $fixturePath
    $env:GITHUB_ENV = Join-Path $fixturePath 'github-env.txt'
    $env:NOVELIA_PREVIEW_KEYSTORE_BASE64 = $null
    Assert-SigningFailure 'missing Secret' $restore 'Missing NOVELIA_PREVIEW_KEYSTORE_BASE64'
    $env:NOVELIA_PREVIEW_KEYSTORE_BASE64 = 'invalid-base64!'
    Assert-SigningFailure 'invalid Base64' $restore 'not valid Base64'
    $env:NOVELIA_PREVIEW_KEYSTORE_BASE64 = 'A' * (48 * 1024 + 1)
    Assert-SigningFailure 'oversized Secret' $restore 'too large'
    if (Test-Path -LiteralPath (Join-Path $fixturePath 'novelia-preview.keystore')) { throw 'Invalid Secret created a key file.' }
    Write-Host 'Preview signing checks passed: 10 scenarios, no network requests or private key files created.'
} finally {
    if ($hadNativeExitCode) { Set-Variable -Name LASTEXITCODE -Value $previousNativeExitCode -Scope Global }
    else { Remove-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue }
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    foreach ($path in @($invalidKeystore, $publicCertificatePath, $publicKeystorePath)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path }
    }
}
