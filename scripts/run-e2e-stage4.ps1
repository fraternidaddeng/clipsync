[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

$env:PATH = 'D:\paste-tools\dotnet;' + $env:PATH
$env:DOTNET_ROOT = 'D:\paste-tools\dotnet'
$env:ANDROID_HOME = 'D:\paste-tools\android-sdk'

$hostExe = Join-Path $repoRoot 'windows\ClipSync.E2eHost\bin\Debug\net8.0\ClipSync.E2eHost.exe'
$solution = Join-Path $repoRoot 'windows\ClipSync.sln'
$androidRoot = Join-Path $repoRoot 'android'

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
    $buildOutput = & dotnet build $solution -c Debug 2>&1
    $buildExit = $LASTEXITCODE
    if ($buildExit -ne 0) {
        $joined = ($buildOutput | ForEach-Object { $_.ToString() }) -join "`n"
        if (Test-NetworkFlake $joined) {
            Write-Host 'RETRY dotnet-build after network flake'
            & dotnet build $solution -c Debug
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
    foreach ($field in @('port', 'cert_sha256', 'windows_device_id', 'android_device_id', 'pair_secret_b64url', 'trust_epoch')) {
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

    $gradleArgs = @(
        'testDebugUnitTest',
        '--tests', 'com.clipsync.android.e2e.*',
        "-Dclipsync.e2e.enabled=true",
        "-Dclipsync.e2e.port=$($ready.port)",
        "-Dclipsync.e2e.cert=$($ready.cert_sha256)",
        "-Dclipsync.e2e.windowsDeviceId=$($ready.windows_device_id)",
        "-Dclipsync.e2e.androidDeviceId=$($ready.android_device_id)",
        "-Dclipsync.e2e.pairSecretB64url=$($ready.pair_secret_b64url)",
        "-Dclipsync.e2e.secretB64url=$($ready.pair_secret_b64url)",
        "-Dclipsync.e2e.trustEpoch=$($ready.trust_epoch)",
        '--no-daemon',
        '--console=plain'
    )

    Push-Location $androidRoot
    try {
        $gradleExit = 1
        $gradleOutput = @()
        $gradleText = ''
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $gradleOutput = & .\gradlew.bat @gradleArgs 2>&1 | ForEach-Object { $_.ToString() }
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
