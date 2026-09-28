param(
    [string] $Path = (Join-Path (Split-Path $PSScriptRoot -Parent) 'secrets/grafana-admin-password')
)

$ErrorActionPreference = 'Stop'

if (Test-Path -LiteralPath $Path -PathType Container) {
    throw "Grafana secret path is a directory, not a file: $Path. Stop Grafana and remove the empty directory before retrying."
}

if (Test-Path -LiteralPath $Path -PathType Leaf) {
    if ((Get-Item -LiteralPath $Path).Length -lt 32) {
        throw "Grafana secret file is too short: $Path"
    }
    Write-Output "Existing Grafana secret file retained: $Path"
    return
}

$directory = Split-Path $Path -Parent
if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

$bytes = New-Object byte[] 48
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $generator.GetBytes($bytes)
} finally {
    $generator.Dispose()
}
$password = [Convert]::ToBase64String($bytes)
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($Path, $password, $encoding)
Write-Output "Created ignored Grafana secret file: $Path"
