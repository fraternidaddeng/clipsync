[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $repoRoot 'android'
$isWindowsHost = $IsWindows -or ($env:OS -eq 'Windows_NT')
$wrapperName = if ($isWindowsHost) { 'gradlew.bat' } else { 'gradlew' }
$wrapper = Join-Path $androidRoot $wrapperName
$sdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT
)
if ($env:LOCALAPPDATA) {
    $sdkCandidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
}
$sdkCandidates = $sdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

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

Push-Location $androidRoot
try {
    if ($isWindowsHost) {
        & $wrapper testDebugUnitTest assembleDebug --stacktrace
    }
    else {
        & sh $wrapper testDebugUnitTest assembleDebug --stacktrace
    }
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
