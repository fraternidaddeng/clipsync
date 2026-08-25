#Requires -Version 5.1
<#
Cross-client stage-4 E2E: builds the solution, starts the headless
ClipSync.E2eHost listener (real Kestrel + TLS + protocol v1/v2 routes), seeds a
Windows-side text clip and a fixture image, then drives the Android JVM dialer
suite (com.clipsync.android.e2e.*) against it over a real pinned WebSocket:
the v1 leg converges text both ways, the v2 leg converges the shared media
fixtures both ways (begin/chunk/end streaming, protocol v2). Passes only when
every direction converges exactly once. Prints E2E-PASS on success.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if ($env:DOTNET_ROOT) {
    $env:PATH = $env:DOTNET_ROOT + ';' + $env:PATH
}

$isWindowsHost = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows
$hostExeName = if ($isWindowsHost) { 'ClipSync.E2eHost.exe' } else { 'ClipSync.E2eHost' }
$hostExe = [IO.Path]::Combine($repoRoot, 'windows', 'ClipSync.E2eHost', 'bin', 'Debug', 'net8.0', $hostExeName)
$solution = [IO.Path]::Combine($repoRoot, 'windows', 'ClipSync.sln')
$androidRoot = Join-Path $repoRoot 'android'
# Non-Windows hosts compile-check the WPF projects via EnableWindowsTargeting
# (same convention as scripts/build-windows.ps1); the E2eHost itself is net8.0.
$buildArgs = @($solution, '-c', 'Debug')
if (-not $isWindowsHost) {
    $buildArgs += '-p:EnableWindowsTargeting=true'
}

$proc = $null
$resultPrinted = $false

function Write-E2eResult {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('PASS', 'FAIL')][string]$Status,
        [string]$Reason
    )
    if ($script:resultPrinted) {
        return
    }
    $script:resultPrinted = $true
    if ($Status -eq 'PASS') {
        Write-Host 'E2E-PASS'
    }
    else {
        Write-Host "E2E RESULT: FAIL $Reason"
    }
}

function Test-NetworkFlake {
    param([string]$Text)
    return [bool]($Text -match 'SSL peer shut down|Could not GET|Connection reset|Unknown host|timed out downloading|maven|nuget|SocketTimeout|502 |503 |connection refused to repo|Couldn''t delete|daemon has been stopped|bundleDebugClassesToCompileJar')
}

function ConvertTo-Base64Url {
    param([byte[]]$Bytes)
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Read-HostLine {
    param(
        [System.IO.StreamReader]$Reader,
        [int]$TimeoutMs,
        [string]$What
    )
    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
    while ([DateTime]::UtcNow -lt $deadline) {
        $remaining = [int][Math]::Max(1, ($deadline - [DateTime]::UtcNow).TotalMilliseconds)
        $task = $Reader.ReadLineAsync()
        if (-not $task.Wait($remaining)) {
            throw "timeout waiting for host $What"
        }
        $line = $task.Result
        if ($null -eq $line) {
            throw "host stdout closed before $What"
        }
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            return $line
        }
    }
    throw "timeout waiting for host $What"
}

try {
    $buildOutput = & dotnet build @buildArgs 2>&1
    $buildExit = $LASTEXITCODE
    if ($buildExit -ne 0) {
        $joined = ($buildOutput | ForEach-Object { $_.ToString() }) -join "`n"
        if (Test-NetworkFlake $joined) {
            Write-Host 'RETRY dotnet-build after network flake'
            & dotnet build @buildArgs
            $buildExit = $LASTEXITCODE
        }
        if ($buildExit -ne 0) {
            Write-E2eResult -Status FAIL -Reason 'dotnet build failed'
            exit 1
        }
    }

    if (-not (Test-Path -LiteralPath $hostExe)) {
        Write-E2eResult -Status FAIL -Reason "E2eHost exe missing: $hostExe"
        exit 1
    }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $hostExe
    $psi.WorkingDirectory = Split-Path -Parent $hostExe
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $false
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    if (-not $proc.Start()) {
        Write-E2eResult -Status FAIL -Reason 'E2eHost failed to start'
        exit 1
    }

    $readyLine = Read-HostLine -Reader $proc.StandardOutput -TimeoutMs 30000 -What 'ready JSON'
    try {
        $ready = $readyLine | ConvertFrom-Json
    }
    catch {
        Write-E2eResult -Status FAIL -Reason 'host ready line was not JSON'
        exit 1
    }
    foreach ($field in @('port', 'cert_sha256', 'windows_device_id', 'android_device_id', 'pair_secret_b64url', 'trust_epoch', 'android_image_device_id', 'image_trust_epoch')) {
        if ($ready.PSObject.Properties.Name -notcontains $field) {
            Write-E2eResult -Status FAIL -Reason "ready JSON missing $field"
            exit 1
        }
    }

    $windowsText = 'e2e-from-windows'
    $androidText = 'e2e-from-android'
    $captureB64 = ConvertTo-Base64Url -Bytes ([Text.Encoding]::UTF8.GetBytes($windowsText))
    $proc.StandardInput.WriteLine("capture $captureB64")
    $proc.StandardInput.Flush()
    $captureReply = Read-HostLine -Reader $proc.StandardOutput -TimeoutMs 10000 -What 'capture ok'
    if ($captureReply -ne 'ok') {
        Write-E2eResult -Status FAIL -Reason "host capture did not reply ok"
        exit 1
    }

    # The v2 image leg: seed the shared png-8x8 media fixture as a Windows-side image clip.
    # The Android image dialer pulls it over begin/chunk/end and pushes jpeg-1x1 back; the
    # fixtures differ on purpose so hash dedup cannot mask a broken direction.
    $mediaDir = [IO.Path]::Combine($repoRoot, 'protocol', 'v2', 'fixtures', 'media')
    $mediaManifest = Get-Content -LiteralPath (Join-Path $mediaDir 'manifest.json') -Raw | ConvertFrom-Json
    $pngB64 = ConvertTo-Base64Url -Bytes ([IO.File]::ReadAllBytes((Join-Path $mediaDir 'png-8x8.png')))
    $proc.StandardInput.WriteLine("capture-image $pngB64")
    $proc.StandardInput.Flush()
    $captureImageReply = Read-HostLine -Reader $proc.StandardOutput -TimeoutMs 10000 -What 'capture-image ok'
    if ($captureImageReply -ne 'ok') {
        Write-E2eResult -Status FAIL -Reason "host capture-image did not reply ok"
        exit 1
    }

    $gradleArgs = @(
        'testDebugUnitTest',
        '--tests', 'com.clipsync.android.e2e.*',
        "-Dclipsync.e2e.enabled=true",
        "-Dclipsync.e2e.port=$($ready.port)",
        "-Dclipsync.e2e.cert=$($ready.cert_sha256)",
        "-Dclipsync.e2e.windowsDeviceId=$($ready.windows_device_id)",
        "-Dclipsync.e2e.androidDeviceId=$($ready.android_device_id)",
        "-Dclipsync.e2e.pairSecretB64url=$($ready.pair_secret_b64url)",
        "-Dclipsync.e2e.trustEpoch=$($ready.trust_epoch)",
        "-Dclipsync.e2e.androidImageDeviceId=$($ready.android_image_device_id)",
        "-Dclipsync.e2e.imageTrustEpoch=$($ready.image_trust_epoch)",
        '--no-daemon',
        '--console=plain'
    )

    Push-Location $androidRoot
    try {
        $gradleExit = 1
        $gradleOutput = @()
        $gradleText = ''
        $gradlew = if ($isWindowsHost) { '.\gradlew.bat' } else { './gradlew' }
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $gradleOutput = & $gradlew @gradleArgs 2>&1 | ForEach-Object { $_.ToString() }
            $gradleExit = $LASTEXITCODE
            $gradleText = $gradleOutput -join "`n"
            Write-Host $gradleText
            if ($gradleExit -eq 0) {
                break
            }
            if ($attempt -eq 3) {
                break
            }
            if ($gradleText -match 'failed to open file|daemon has been stopped|Couldn''t delete|bundleDebugClassesToCompileJar') {
                Write-Host "RETRY gradle attempt $($attempt + 1) after transient build race"
                Start-Sleep -Seconds 8
                continue
            }
            if (Test-NetworkFlake $gradleText) {
                Write-Host "RETRY gradle attempt $($attempt + 1) after network flake"
                Start-Sleep -Seconds 3
                continue
            }
            break
        }
    }
    finally {
        Pop-Location
    }

    if ($gradleExit -ne 0) {
        $hint = ($gradleOutput | Select-String -Pattern 'AssertionError|timed out|lastError|TRUST_|AUTH_|CERTIFICATE|FAILED' | Select-Object -Last 8 | ForEach-Object { $_.Line.Trim() }) -join ' | '
        if ([string]::IsNullOrWhiteSpace($hint)) {
            $hint = 'gradle testDebugUnitTest failed'
        }
        Write-E2eResult -Status FAIL -Reason $hint
        exit 1
    }

    if ($proc.HasExited) {
        Write-E2eResult -Status FAIL -Reason "E2eHost exited early with code $($proc.ExitCode)"
        exit 1
    }

    $proc.StandardInput.WriteLine('list')
    $proc.StandardInput.Flush()
    $listLine = Read-HostLine -Reader $proc.StandardOutput -TimeoutMs 10000 -What 'list JSON'
    try {
        $list = $listLine | ConvertFrom-Json
    }
    catch {
        Write-E2eResult -Status FAIL -Reason 'host list line was not JSON'
        exit 1
    }
    $androidHits = @($list.texts | Where-Object { $_ -eq $androidText })
    if ($androidHits.Count -ne 1) {
        Write-E2eResult -Status FAIL -Reason "windows list expected $androidText exactly once, found $($androidHits.Count)"
        exit 1
    }

    $proc.StandardInput.WriteLine('list-images')
    $proc.StandardInput.Flush()
    $imagesLine = Read-HostLine -Reader $proc.StandardOutput -TimeoutMs 10000 -What 'list-images JSON'
    try {
        $images = $imagesLine | ConvertFrom-Json
    }
    catch {
        Write-E2eResult -Status FAIL -Reason 'host list-images line was not JSON'
        exit 1
    }
    $androidImageHits = @($images.image_hashes | Where-Object { $_ -eq $mediaManifest.jpeg_1x1_sha256 })
    if ($androidImageHits.Count -ne 1) {
        Write-E2eResult -Status FAIL -Reason "windows list-images expected the android jpeg hash exactly once, found $($androidImageHits.Count)"
        exit 1
    }
    $windowsImageHits = @($images.image_hashes | Where-Object { $_ -eq $mediaManifest.png_8x8_sha256 })
    if ($windowsImageHits.Count -ne 1) {
        Write-E2eResult -Status FAIL -Reason "windows list-images expected the seeded png hash exactly once, found $($windowsImageHits.Count)"
        exit 1
    }

    $proc.StandardInput.WriteLine('quit')
    $proc.StandardInput.Flush()
    if (-not $proc.WaitForExit(10000)) {
        Write-E2eResult -Status FAIL -Reason 'E2eHost did not exit after quit'
        exit 1
    }

    Write-E2eResult -Status PASS
    exit 0
}
catch {
    Write-E2eResult -Status FAIL -Reason $_.Exception.Message
    exit 1
}
finally {
    if ($null -ne $proc -and -not $proc.HasExited) {
        try { $null = $proc.Kill($true) } catch { }
        try { $proc.WaitForExit(5000) } catch { }
    }
    if ($null -ne $proc) {
        $proc.Dispose()
    }
}
