[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$validator = Join-Path $PSScriptRoot 'validate-protocol.py'

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw 'Python 3 is required to validate protocol schema and semantic fixtures.'
}

python $validator $repoRoot
exit $LASTEXITCODE
