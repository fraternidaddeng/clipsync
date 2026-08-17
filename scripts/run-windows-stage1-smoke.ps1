[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$app = Join-Path $repoRoot 'windows\ClipSync.App\bin\Debug\net8.0-windows\ClipSync.App.exe'
$smokeRoot = Join-Path $env:TEMP ("clipsync-stage1-smoke-" + [Guid]::NewGuid().ToString('N'))
$database = Join-Path $smokeRoot 'clipsync.db'
$diagnostics = Join-Path $smokeRoot 'diagnostics.log'
$sqlite = Join-Path $repoRoot '.tools\android-sdk\platform-tools\sqlite3.exe'
$env:CLIPSYNC_DATA_DIR = $smokeRoot
$env:CLIPSYNC_DIAGNOSTICS_PATH = $diagnostics
New-Item -ItemType Directory -Force -Path $smokeRoot | Out-Null

if (-not (Test-Path -LiteralPath $app)) { throw "Build output is missing: $app" }
if (-not (Test-Path -LiteralPath $sqlite)) { throw "sqlite3 is missing: $sqlite" }

$process = Start-Process -FilePath $app -PassThru
try {
    Start-Sleep -Milliseconds 700
    $token = "stage1-smoke-$([Guid]::NewGuid().ToString('N'))"
    $text = "$token`r`nUnicode 文本 😀"
    $startedAt = [DateTimeOffset]::UtcNow
    Set-Clipboard -Value $text

    $deadline = (Get-Date).AddSeconds(5)
    $count = 0
    do {
        Start-Sleep -Milliseconds 50
        if (Test-Path -LiteralPath $database) {
            $count = [int](& $sqlite $database "SELECT COUNT(*) FROM clips WHERE instr(content, '$token') > 0 AND deleted_at IS NULL;")
        }
    } while ($count -ne 1 -and (Get-Date) -lt $deadline)

    if ($count -ne 1) {
        if (Test-Path -LiteralPath $diagnostics) { Get-Content -LiteralPath $diagnostics }
        throw 'Clipboard event was not persisted.'
    }
    $elapsed = [DateTimeOffset]::UtcNow - $startedAt
    if ($elapsed.TotalMilliseconds -gt 500) { throw "Clipboard capture took $([int]$elapsed.TotalMilliseconds) ms." }
    Write-Host "CAPTURE_MS=$([int]$elapsed.TotalMilliseconds)"
}
finally {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Wait-Process -Id $process.Id -Timeout 5 -ErrorAction SilentlyContinue
}

$restarted = Start-Process -FilePath $app -PassThru
try {
    Start-Sleep -Milliseconds 700
    if ($restarted.HasExited) { throw 'Application exited during restart validation.' }
    $persisted = [int](& $sqlite $database 'SELECT COUNT(*) FROM clips WHERE deleted_at IS NULL;')
    if ($persisted -lt 1) { throw 'History was not retained after restart.' }
    Write-Host "PERSISTED=$persisted"
}
finally {
    Stop-Process -Id $restarted.Id -Force -ErrorAction SilentlyContinue
    Wait-Process -Id $restarted.Id -Timeout 5 -ErrorAction SilentlyContinue
}

Write-Host "DATABASE=$database"
