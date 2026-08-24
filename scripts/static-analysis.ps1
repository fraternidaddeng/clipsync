[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $repoRoot 'android'
$wrapper = Join-Path $androidRoot 'gradlew.bat'
$solution = Join-Path $repoRoot 'windows\ClipSync.sln'

if ($env:DOTNET_ROOT -and (Test-Path -LiteralPath $env:DOTNET_ROOT)) {
    $env:PATH = "$env:DOTNET_ROOT;" + $env:PATH
}

$sdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'JDK 17 is required, but java was not found on PATH.'
}

$javaVersion = (& java -version 2>&1 | Select-Object -First 1) -join ''
if ($javaVersion -notmatch 'version "17[\."]') {
    throw "JDK 17 is required. Detected: $javaVersion"
}

if ($sdkCandidates.Count -eq 0) {
    throw 'Android SDK is required. Set ANDROID_HOME or ANDROID_SDK_ROOT.'
}

if (-not (Test-Path -LiteralPath $wrapper)) {
    throw "Gradle wrapper is missing: $wrapper"
}

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw '.NET 8 SDK is required, but dotnet was not found on PATH.'
}

$sdkList = @(dotnet --list-sdks)
if ($LASTEXITCODE -ne 0 -or $sdkList.Count -eq 0) {
    throw '.NET 8 SDK is required. Only a runtime, or no SDK, is currently installed.'
}

$results = [System.Collections.Generic.List[string]]::new()
$failed = $false

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Action
    )

    Write-Host ""
    Write-Host "=== $Name ==="
    & $Action
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
    if ($code -ne 0) {
        $script:failed = $true
        $script:results.Add("$Name : FAIL (exit $code)")
    }
    else {
        $script:results.Add("$Name : PASS")
    }
}

Push-Location $androidRoot
try {
    Invoke-Step -Name 'Detekt' -Action { & $wrapper detekt --stacktrace }
    Invoke-Step -Name 'Ktlint' -Action { & $wrapper ktlintCheck --stacktrace }
}
finally {
    Pop-Location
}

Invoke-Step -Name '.NET analyzers (build)' -Action {
    dotnet build $solution -c Debug
}

Write-Host ""
Write-Host "=== NuGet vulnerability scan ==="
$previousNative = $PSNativeCommandUseErrorActionPreference
$PSNativeCommandUseErrorActionPreference = $false
try {
    $vulnOutput = dotnet list $solution package --vulnerable --include-transitive 2>&1 | Out-String
}
finally {
    $PSNativeCommandUseErrorActionPreference = $previousNative
}
Write-Host $vulnOutput
$hasVuln = $vulnOutput -match '(?i)vulnerable|易受攻击|GHSA-|CVE-\d'
if ($hasVuln) {
    $results.Add('NuGet vulnerabilities : FINDINGS (see output; majors not bumped)')
}
else {
    $results.Add('NuGet vulnerabilities : PASS (none reported)')
}

$results.Add('OWASP dependency-check : SKIPPED (heavy CVE DB download; not wired)')

Write-Host ""
Write-Host "=== Static analysis summary ==="
foreach ($line in $results) {
    Write-Host $line
}

if ($failed) {
    exit 1
}
exit 0
