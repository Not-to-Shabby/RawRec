$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$rvtoolScript = Join-Path $root 'rvtool\rvtool.ps1'

if (Test-Path $rvtoolScript) {
    & $rvtoolScript @args
    exit $LASTEXITCODE
}

throw "rvtool launcher not found at: $rvtoolScript"
