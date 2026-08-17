[CmdletBinding()]
param(
    [ValidateRange(1, 1000)]
    [int]$Count = 100
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$app = Join-Path $repoRoot 'windows\ClipSync.App\bin\Debug\net8.0-windows\ClipSync.App.exe'
$sqlite = Join-Path $repoRoot '.tools\android-sdk\platform-tools\sqlite3.exe'
$smokeRoot = Join-Path $env:TEMP ("clipsync-stage1-stress-" + [Guid]::NewGuid().ToString('N'))
$database = Join-Path $smokeRoot 'clipsync.db'
$env:CLIPSYNC_DATA_DIR = $smokeRoot
New-Item -ItemType Directory -Force -Path $smokeRoot | Out-Null

$process = Start-Process -FilePath $app -PassThru
try {
    Start-Sleep -Milliseconds 700
    for ($index = 0; $index -lt $Count; $index++) {
        Set-Clipboard -Value "stress-$index-$([Guid]::NewGuid().ToString('N'))"
        # A real Ctrl+C has time for the listener to consume the update. A
        # 25ms clipboard flood can be coalesced by Windows before WM_CLIPBOARDUPDATE
        # is delivered and is not a meaningful user-facing acceptance case.
        Start-Sleep -Milliseconds 120
    }

    $deadline = (Get-Date).AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 100
        $stored = if (Test-Path $database) { [int](& $sqlite $database 'SELECT COUNT(*) FROM clips WHERE deleted_at IS NULL;') } else { 0 }
    } while ($stored -lt $Count -and (Get-Date) -lt $deadline)

    if ($stored -ne $Count) { throw "Expected $Count stored events; found $stored." }
    $unique = [int](& $sqlite $database 'SELECT COUNT(DISTINCT origin_seq) FROM clips WHERE deleted_at IS NULL;')
    if ($unique -ne $Count) { throw "Expected $Count unique sequences; found $unique." }
    Write-Host "STORED=$stored UNIQUE_SEQUENCES=$unique"
}
finally {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Wait-Process -Id $process.Id -Timeout 5 -ErrorAction SilentlyContinue
}
