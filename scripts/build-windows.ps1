[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$solution = Join-Path $repoRoot 'windows\ClipSync.sln'

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw '.NET 8 SDK is required, but dotnet was not found on PATH.'
}

$sdkList = @(dotnet --list-sdks)
if ($LASTEXITCODE -ne 0 -or $sdkList.Count -eq 0) {
    throw '.NET 8 SDK is required. Only a runtime, or no SDK, is currently installed.'
}

dotnet restore $solution
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

dotnet build $solution --configuration $Configuration --no-restore
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

dotnet test $solution --configuration $Configuration --no-build
exit $LASTEXITCODE
