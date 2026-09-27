$ErrorActionPreference = 'Stop'

$graphifyCommand = Get-Command graphify -CommandType Application -ErrorAction Stop | Select-Object -First 1
$previousHashSeed = [Environment]::GetEnvironmentVariable('PYTHONHASHSEED', 'Process')
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path

try {
    [Environment]::SetEnvironmentVariable('PYTHONHASHSEED', '0', 'Process')
    Push-Location -LiteralPath $projectRoot
    try {
        & $graphifyCommand.Source update . @args
        if ($LASTEXITCODE -ne 0) {
            throw "Graphify update failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    [Environment]::SetEnvironmentVariable('PYTHONHASHSEED', $previousHashSeed, 'Process')
}
