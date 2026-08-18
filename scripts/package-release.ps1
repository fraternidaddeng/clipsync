[CmdletBinding()]
param(
    [string]$Version,
    [switch]$Prune
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if ($env:DOTNET_ROOT -and (Test-Path -LiteralPath $env:DOTNET_ROOT)) {
    $env:PATH = "$env:DOTNET_ROOT;" + $env:PATH
}

$sdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    'D:\paste-tools\android-sdk'
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

if ($sdkCandidates.Count -gt 0 -and -not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = $sdkCandidates[0]
}

$defaultKeystore = 'D:\paste-tools\clipsync-release.keystore'
$keystorePath = if ($env:CLIPSYNC_KEYSTORE -and $env:CLIPSYNC_KEYSTORE.Trim()) {
    $env:CLIPSYNC_KEYSTORE.Trim()
} else {
    $defaultKeystore
}
$keyAlias = if ($env:CLIPSYNC_KEY_ALIAS -and $env:CLIPSYNC_KEY_ALIAS.Trim()) {
    $env:CLIPSYNC_KEY_ALIAS.Trim()
} else {
    'clipsync'
}

function Get-WindowsComponentVersion {
    param([string]$CsprojPath)
    $text = Get-Content -LiteralPath $CsprojPath -Raw
    foreach ($tag in @('Version', 'AssemblyVersion', 'InformationalVersion', 'FileVersion')) {
        $match = [regex]::Match($text, "<$tag>([^<]+)</$tag>")
        if ($match.Success) {
            return $match.Groups[1].Value.Trim()
        }
    }
    return '0.2.0'
}

function Get-AndroidVersionName {
    param([string]$GradlePath)
    $text = Get-Content -LiteralPath $GradlePath -Raw
    $match = [regex]::Match($text, 'versionName\s*=\s*"([^"]+)"')
    if (-not $match.Success) {
        throw "Could not read versionName from $GradlePath"
    }
    return $match.Groups[1].Value.Trim()
}

function Write-Sha256Sidecar {
    param([string]$Path)
    $hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    $leaf = Split-Path -Leaf $Path
    $line = "$hash  $leaf"
    Set-Content -LiteralPath "$Path.sha256" -Value $line -Encoding utf8
    return [pscustomobject]@{ Name = $leaf; Hash = $hash; Path = $Path }
}

function Get-KeystoreFingerprint {
    param(
        [string]$StorePath,
        [string]$Alias
    )
    if (-not $env:CLIPSYNC_KEYSTORE_PASSWORD) {
        return $null
    }
    $previousNative = $PSNativeCommandUseErrorActionPreference
    $PSNativeCommandUseErrorActionPreference = $false
    try {
        $output = & keytool -list -v -keystore $StorePath -alias $Alias -storepass $env:CLIPSYNC_KEYSTORE_PASSWORD 2>&1 | Out-String
    }
    finally {
        $PSNativeCommandUseErrorActionPreference = $previousNative
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Warning 'keytool -list failed; fingerprint not recorded (password not printed).'
        return $null
    }
    $match = [regex]::Match($output, 'SHA256:\s*([0-9A-Fa-f:]+)')
    if ($match.Success) {
        return $match.Groups[1].Value.Trim()
    }
    return $null
}

function New-KeytoolCreateInstruction {
    param([string]$StorePath, [string]$Alias)
    return @"
keytool -genkeypair -v -alias $Alias -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12 -keystore `"$StorePath`"
Then set (do not commit):
  `$env:CLIPSYNC_KEYSTORE = '$StorePath'
  `$env:CLIPSYNC_KEYSTORE_PASSWORD = '<the store password you just chose>'
  `$env:CLIPSYNC_KEY_ALIAS = '$Alias'
  `$env:CLIPSYNC_KEY_PASSWORD = '<key password, or omit if same as store>'
Re-run this script. The keystore stays outside the repo; this script never generates it.
"@
}

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw '.NET 8 SDK is required, but dotnet was not found on PATH. Set DOTNET_ROOT (e.g. D:\paste-tools\dotnet).'
}

$sdkList = @(dotnet --list-sdks)
if ($LASTEXITCODE -ne 0 -or $sdkList.Count -eq 0) {
    throw '.NET 8 SDK is required. Only a runtime, or no SDK, is currently installed.'
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'JDK 17 is required, but java was not found on PATH.'
}

if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
    throw 'Android SDK is required. Set ANDROID_HOME or ANDROID_SDK_ROOT.'
}

$appCsproj = Join-Path $repoRoot 'windows\ClipSync.App\ClipSync.App.csproj'
$gradleFile = Join-Path $repoRoot 'android\app\build.gradle.kts'
$androidRoot = Join-Path $repoRoot 'android'
$wrapper = Join-Path $androidRoot 'gradlew.bat'
$solution = Join-Path $repoRoot 'windows\ClipSync.sln'

if (-not (Test-Path -LiteralPath $appCsproj)) { throw "Missing $appCsproj" }
if (-not (Test-Path -LiteralPath $gradleFile)) { throw "Missing $gradleFile" }
if (-not (Test-Path -LiteralPath $wrapper)) { throw "Gradle wrapper is missing: $wrapper" }

$windowsVersion = Get-WindowsComponentVersion -CsprojPath $appCsproj
$androidVersion = Get-AndroidVersionName -GradlePath $gradleFile
$releaseVersion = if ($Version -and $Version.Trim()) { $Version.Trim() } else { $androidVersion }

Write-Host "Windows component version: $windowsVersion"
Write-Host "Android versionName:       $androidVersion"
Write-Host "Release folder version:    $releaseVersion"
if ($windowsVersion -ne $androidVersion) {
    Write-Host "Note: component versions differ. Artifact names use $releaseVersion (override with -Version)."
}

$releasesRoot = Join-Path $repoRoot 'releases'
$versionDir = Join-Path $releasesRoot $releaseVersion
New-Item -ItemType Directory -Force -Path $versionDir | Out-Null

$keystoreExists = Test-Path -LiteralPath $keystorePath
$passwordSet = -not [string]::IsNullOrWhiteSpace($env:CLIPSYNC_KEYSTORE_PASSWORD)
$willSign = $keystoreExists -and $passwordSet
$keytoolInstruction = New-KeytoolCreateInstruction -StorePath $keystorePath -Alias $keyAlias

if ($willSign) {
    Write-Host "Android signing: release keystore present and CLIPSYNC_KEYSTORE_PASSWORD is set."
} else {
    Write-Host 'Android signing: UNSIGNED / debug-keyed (no crash). Create a personal keystore once:'
    Write-Host $keytoolInstruction
    if (-not $keystoreExists) {
        Write-Host "Keystore not found at: $keystorePath"
    }
    if (-not $passwordSet) {
        Write-Host 'CLIPSYNC_KEYSTORE_PASSWORD is not set (value never printed).'
    }
}

# --- Windows portable ZIP ---
$publishDir = Join-Path $versionDir '.windows-publish'
if (Test-Path -LiteralPath $publishDir) {
    Remove-Item -LiteralPath $publishDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $publishDir | Out-Null

Write-Host ''
Write-Host '=== Publishing Windows portable (win-x64, self-contained) ==='
dotnet publish $appCsproj -c Release -r win-x64 --self-contained true --output $publishDir
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$exePath = Join-Path $publishDir 'ClipSync.App.exe'
if (-not (Test-Path -LiteralPath $exePath)) {
    throw "Publish succeeded but ClipSync.App.exe was not found in $publishDir"
}

$readmePath = Join-Path $publishDir 'README.txt'
@(
    'ClipSync for Windows (portable)',
    "Version: $releaseVersion (Windows component $windowsVersion)",
    '',
    'This is a personal-use P2P clipboard sync client. No installer, no admin, no cloud.',
    '',
    'How to run',
    '  1. Unzip this folder anywhere you can write (for example %LOCALAPPDATA%\Programs\ClipSync).',
    '  2. Double-click ClipSync.App.exe. A tray icon appears; the peer endpoint binds without elevation.',
    '  3. Open the window from the tray, choose "Pair new device…", and scan the QR code from Android.',
    '',
    'Where data lives',
    '  History, pairing keys, device id, and the TLS certificate live in:',
    '    %LOCALAPPDATA%\ClipSync',
    '      clipsync.db          clipboard history / outbox / paired devices',
    '      device-id            stable local identity',
    '      peer-certificate.bin DPAPI-protected TLS identity',
    '  Moving the unzipped program folder does not move this data.',
    '  To use a throwaway data directory (tests): set CLIPSYNC_DATA_DIR before launching.',
    '',
    'Autostart (current user, no admin)',
    '  Run from the repo:  pwsh scripts/install-windows.ps1 -ZipPath <this-zip> -EnableAutostart',
    '  Or add ClipSync.App.exe to HKCU\Software\Microsoft\Windows\CurrentVersion\Run yourself.',
    '  Scheduled-task autostart is not provided.',
    '',
    'How to uninstall',
    '  1. Exit ClipSync from the tray.',
    '  2. Run:  pwsh scripts/uninstall-windows.ps1',
    '  3. The script removes the Run key, then ASKS before deleting %LOCALAPPDATA%\ClipSync.',
    '     Use -ExportTo <folder> to copy clipsync.db first. Data is never deleted silently.',
    '  4. Delete the unzipped program folder if you no longer want the binaries.',
    '',
    'Rollback',
    '  Keep the previous ZIP. Unzip it over the program folder (or beside it and point autostart at the new exe).',
    '  %LOCALAPPDATA%\ClipSync is not inside the ZIP, so swapping folders leaves history and pairing intact.',
    '',
    'See docs/distribution.md in the source tree for pairing, Android sideload, and troubleshooting.'
) | Set-Content -LiteralPath $readmePath -Encoding utf8

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipName = "ClipSync-Windows-$releaseVersion-win-x64.zip"
$zipPath = Join-Path $versionDir $zipName
if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
[System.IO.Compression.ZipFile]::CreateFromDirectory($publishDir, $zipPath)
Remove-Item -LiteralPath $publishDir -Recurse -Force

# --- Android APK ---
Write-Host ''
Write-Host '=== Building Android assembleRelease ==='
Push-Location $androidRoot
try {
    & $wrapper :app:assembleRelease --stacktrace
    $androidExit = $LASTEXITCODE
}
finally {
    Pop-Location
}
if ($androidExit -ne 0) { exit $androidExit }

$apkSourceCandidates = @(
    (Join-Path $androidRoot 'app\build\outputs\apk\release\app-release.apk'),
    (Join-Path $androidRoot 'app\build\outputs\apk\release\app-release-unsigned.apk')
)
$apkSource = $apkSourceCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $apkSource) {
    throw 'assembleRelease finished but no APK was found under android/app/build/outputs/apk/release/.'
}

$apkSigned = $willSign
$apkLabel = if ($apkSigned) { '' } else { '-unsigned' }
$apkName = "ClipSync-Android-$releaseVersion$apkLabel.apk"
$apkPath = Join-Path $versionDir $apkName
Copy-Item -LiteralPath $apkSource -Destination $apkPath -Force

$fingerprint = $null
if ($apkSigned) {
    $fingerprint = Get-KeystoreFingerprint -StorePath $keystorePath -Alias $keyAlias
    if ($fingerprint) {
        Write-Host "Release keystore SHA-256 fingerprint: $fingerprint"
    }
} else {
    Write-Host "Copied debug-keyed/unsigned APK as $apkName"
}

# --- Checksums ---
$artifactInfos = @(
    (Write-Sha256Sidecar -Path $zipPath),
    (Write-Sha256Sidecar -Path $apkPath)
)
$sumsPath = Join-Path $versionDir 'SHA256SUMS.txt'
$artifactInfos | ForEach-Object { "$($_.Hash)  $($_.Name)" } | Set-Content -LiteralPath $sumsPath -Encoding utf8

# --- Release notes ---
$notesPath = Join-Path $versionDir 'RELEASE_NOTES.txt'
$signingNotes = if ($apkSigned) {
    @(
        'Android APK: signed with the personal release keystore.',
        $(if ($fingerprint) { "Keystore SHA-256 fingerprint: $fingerprint" } else { 'Keystore fingerprint: (keytool -list did not return SHA256)' })
    ) -join [Environment]::NewLine
} else {
    @(
        'Android APK: UNSIGNED / debug-keyed. Sideload is fine for personal testing; create a',
        'release keystore once and re-run this script for a signed artifact.',
        '',
        'Create the keystore (print-only; this script never generates keys):',
        $keytoolInstruction
    ) -join [Environment]::NewLine
}

@(
    "ClipSync $releaseVersion",
    "Built: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    "Windows component: $windowsVersion",
    "Android versionName: $androidVersion",
    '',
    $signingNotes,
    '',
    'Keystore location (outside the repo):',
    "  $keystorePath",
    '  or $env:CLIPSYNC_KEYSTORE. Passwords come from CLIPSYNC_KEYSTORE_PASSWORD only.',
    '',
    'Rollback: this script keeps the two most recent version folders under releases\.',
    'Pass -Prune to delete older folders (they are listed first, never deleted silently).',
    'Windows rollback = swap the unzipped folder; %LOCALAPPDATA%\ClipSync is untouched.',
    'Android: installing an older APK over a newer one may require uninstall first —',
    'uninstalling the Android app deletes on-device history and pairing.',
    '',
    'No automatic update. No MSIX. No scheduled-task autostart (HKCU Run only).'
) | Set-Content -LiteralPath $notesPath -Encoding utf8

# --- Retention ---
$versionDirs = @(Get-ChildItem -LiteralPath $releasesRoot -Directory | Sort-Object LastWriteTime -Descending)
$keep = @($versionDirs | Select-Object -First 2)
$stale = @($versionDirs | Select-Object -Skip 2)
Write-Host ''
Write-Host '=== Rollback retention (keep two most recent version folders) ==='
if ($stale.Count -eq 0) {
    Write-Host 'Nothing to prune.'
} else {
    foreach ($dir in $stale) {
        if ($Prune) {
            Write-Host "Deleting old release folder: $($dir.FullName)"
            Remove-Item -LiteralPath $dir.FullName -Recurse -Force
        } else {
            Write-Host "Would delete (pass -Prune to remove): $($dir.FullName)"
        }
    }
}
Write-Host ('Keeping: ' + (($keep | ForEach-Object { $_.Name }) -join ', '))

# --- Summary table ---
Write-Host ''
Write-Host '=== Release artifacts ==='
Write-Host ('{0,-48} {1,12} {2}' -f 'Artifact', 'Size', 'SHA256 (first 16)')
Write-Host ('-' * 80)
foreach ($info in $artifactInfos) {
    $item = Get-Item -LiteralPath $info.Path
    $prefix = $info.Hash.Substring(0, 16)
    Write-Host ('{0,-48} {1,10:N0} B {2}' -f $info.Name, $item.Length, $prefix)
}
Write-Host ''
Write-Host "Output: $versionDir"
Write-Host "Signing: $(if ($apkSigned) { 'signed release' } else { 'unsigned / debug-keyed' })"

# Do not build or run tests here; the caller re-runs the green suites separately.
exit 0
