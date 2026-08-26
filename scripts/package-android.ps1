<#
.SYNOPSIS
Assembles a release-ready ClipSync APK into dist/ (stage 7 minimal
distribution chain).

.DESCRIPTION
Default is a signed Release build written to dist/ClipSync-android.apk plus a
.sha256 file. Signing material is taken from environment variables only; the
keystore and its passwords must never enter the repository (*.jks/*.keystore
are gitignored):

  CLIPSYNC_ANDROID_KEYSTORE           path to the release keystore (.jks/.p12)
  CLIPSYNC_ANDROID_KEYSTORE_PASSWORD  keystore password
  CLIPSYNC_ANDROID_KEY_ALIAS          key alias inside the keystore
  CLIPSYNC_ANDROID_KEY_PASSWORD       key password

Create the keystore once (keep it and the passwords out of the repository,
e.g. in a password manager):

  keytool -genkeypair -v -keystore clipsync-release.jks -storetype PKCS12 `
      -alias clipsync -keyalg RSA -keysize 4096 -validity 3650

Without the variables, Release fails fast unless -AllowUnsignedRelease is
given (produces a not-installable dist/ClipSync-android-unsigned.apk, useful
only for build verification). -Variant Debug builds a debug-signed
dist/ClipSync-android-debug.apk for testing; its signature differs from the
release key, so it cannot upgrade a release install.

-Version (the release tag without the leading "v": X.Y.Z or X.Y.Z-rc.N) stamps
the APK so tagged builds can never ship the stale defaults hardcoded in
android/app/build.gradle.kts: versionName is the given string verbatim, and
versionCode is derived deterministically as

  major*1000000 + minor*10000 + patch*100 + (N for -rc.N, 99 for a final)

so upgrade ordering is always monotonic: every rc sorts below its final
release and above the previous patch (0.1.0-rc.1 -> 10001, 0.1.0 -> 10099,
0.1.1-rc.1 -> 10101, 0.1.1 -> 10199). Android refuses versionCode downgrades,
so rc installs upgrade cleanly to the final APK. Without -Version, local
builds keep the build.gradle.kts development defaults.

.EXAMPLE
pwsh ./scripts/package-android.ps1
pwsh ./scripts/package-android.ps1 -Version 0.1.0
pwsh ./scripts/package-android.ps1 -Variant Debug
#>
[CmdletBinding()]
param(
    [ValidateSet('Release', 'Debug')]
    [string]$Variant = 'Release',
    # Release version from the tag (without the leading 'v'), e.g. 0.1.0 or
    # 0.1.0-rc.1. Sets the APK versionName/versionCode (see .DESCRIPTION).
    [string]$Version,
    # Build an unsigned Release APK when no signing environment is configured.
    [switch]$AllowUnsignedRelease
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $repoRoot 'android'
$distDir = Join-Path $repoRoot 'dist'

# $IsWindows does not exist on Windows PowerShell 5.1, which is always Windows.
$onWindows = $PSVersionTable.PSVersion.Major -lt 6 -or $IsWindows

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'JDK 17 or newer is required, but java was not found on PATH.'
}
$javaVersionLine = (& java -version 2>&1 | Select-Object -First 1) -join ''
if ($javaVersionLine -match 'version "(\d+)') {
    if ([int]$Matches[1] -lt 17) {
        throw "JDK 17 or newer is required. Detected: $javaVersionLine"
    }
}
else {
    throw "Could not parse the Java version. Detected: $javaVersionLine"
}

$sdkCandidates = @(
    @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        $(if ($onWindows -and $env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android\Sdk' })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
)
$localProperties = Join-Path $androidRoot 'local.properties'
if ($sdkCandidates.Count -eq 0 -and -not (Test-Path -LiteralPath $localProperties)) {
    throw 'Android SDK is required. Set ANDROID_HOME or ANDROID_SDK_ROOT, or provide android/local.properties.'
}

$signingVariables = @(
    'CLIPSYNC_ANDROID_KEYSTORE',
    'CLIPSYNC_ANDROID_KEYSTORE_PASSWORD',
    'CLIPSYNC_ANDROID_KEY_ALIAS',
    'CLIPSYNC_ANDROID_KEY_PASSWORD'
)
$missingVariables = @($signingVariables | Where-Object { -not [Environment]::GetEnvironmentVariable($_) })
$signingConfigured = $missingVariables.Count -eq 0

if ($Variant -eq 'Release') {
    if ($signingConfigured) {
        # Gradle receives an absolute keystore path regardless of the caller's cwd.
        $keystore = Resolve-Path -LiteralPath $env:CLIPSYNC_ANDROID_KEYSTORE -ErrorAction SilentlyContinue
        if (-not $keystore) {
            throw "CLIPSYNC_ANDROID_KEYSTORE points to a missing file: $env:CLIPSYNC_ANDROID_KEYSTORE"
        }
        $env:CLIPSYNC_ANDROID_KEYSTORE = $keystore.Path
    }
    elseif (-not $AllowUnsignedRelease) {
        throw ("Release signing is not configured; missing: $($missingVariables -join ', '). " +
            'See the script header or docs/install.md for the keytool command and variable list. ' +
            'Use -Variant Debug for a testable debug build, or -AllowUnsignedRelease for a build-only check.')
    }
}

# Version stamping from the release tag (scheme documented in .DESCRIPTION).
$versionArgs = @()
if ($Version) {
    if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?$') {
        throw ("Unsupported -Version '$Version'. Expected X.Y.Z or X.Y.Z-rc.N (the release tag " +
            'without the leading "v"); other pre-release suffixes have no versionCode mapping.')
    }
    $major = [int]$Matches[1]
    $minor = [int]$Matches[2]
    $patch = [int]$Matches[3]
    $rc = if ($Matches[4]) { [int]$Matches[4] } else { $null }
    # Bounds keep the versionCode scheme injective and monotonic, and below
    # Android's hard cap of 2100000000 (major <= 2099 covers any realistic tag).
    if ($major -gt 2099 -or $minor -gt 99 -or $patch -gt 99) {
        throw "-Version '$Version' overflows the versionCode scheme (major <= 2099, minor/patch <= 99)."
    }
    if ($null -ne $rc -and ($rc -lt 1 -or $rc -gt 98)) {
        throw "-Version '$Version': rc number must be 1..98 so the final release (offset 99) stays above every rc."
    }
    $versionCode = $major * 1000000 + $minor * 10000 + $patch * 100 + $(if ($null -ne $rc) { $rc } else { 99 })
    $versionArgs = @("-PversionName=$Version", "-PversionCode=$versionCode")
    Write-Host "Version  : $Version (versionCode $versionCode)"
}

$task = if ($Variant -eq 'Release') { ':app:assembleRelease' } else { ':app:assembleDebug' }
Push-Location $androidRoot
try {
    if ($onWindows) {
        & (Join-Path $androidRoot 'gradlew.bat') $task --stacktrace @versionArgs
    }
    else {
        & bash (Join-Path $androidRoot 'gradlew') $task --stacktrace @versionArgs
    }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}

if ($Variant -eq 'Release' -and $signingConfigured) {
    $apkSource = Join-Path $androidRoot 'app/build/outputs/apk/release/app-release.apk'
    $apkName = 'ClipSync-android.apk'
}
elseif ($Variant -eq 'Release') {
    $apkSource = Join-Path $androidRoot 'app/build/outputs/apk/release/app-release-unsigned.apk'
    $apkName = 'ClipSync-android-unsigned.apk'
}
else {
    $apkSource = Join-Path $androidRoot 'app/build/outputs/apk/debug/app-debug.apk'
    $apkName = 'ClipSync-android-debug.apk'
}

if (-not (Test-Path -LiteralPath $apkSource)) {
    throw "Expected APK was not produced: $apkSource"
}

New-Item -ItemType Directory -Path $distDir -Force | Out-Null
$apkPath = Join-Path $distDir $apkName
Copy-Item -LiteralPath $apkSource -Destination $apkPath -Force

$hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$apkPath.sha256" -Value "$hash *$apkName" -Encoding ascii

$sizeMb = [math]::Round((Get-Item -LiteralPath $apkPath).Length / 1MB, 1)
Write-Host "APK      : $apkPath ($sizeMb MB)"
Write-Host "SHA-256  : $hash"
if ($Variant -eq 'Release' -and -not $signingConfigured) {
    Write-Warning 'This APK is UNSIGNED and cannot be installed. Configure the CLIPSYNC_ANDROID_* variables for a distributable build.'
}
elseif ($Variant -eq 'Debug') {
    Write-Host 'Debug-signed build for testing; not for distribution.'
}
