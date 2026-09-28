param(
    [Parameter(Mandatory = $true)]
    [string]$Destination,
    [string]$EnvFile = '.env',
    [string]$Remote,
    [switch]$DownloadVerify,
    [switch]$ApplyRetention
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repoRoot 'compose.production.yaml'
$environmentFile = (Resolve-Path -LiteralPath $EnvFile).Path
$destinationRoot = (Resolve-Path -LiteralPath $Destination).Path
if ($destinationRoot.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    $destinationRoot.Equals($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backups must be written outside the repository.'
}

$backupId = '{0}-{1}' -f (Get-Date -AsUTC -Format 'yyyyMMddTHHmmssZ'), ([guid]::NewGuid().ToString('N').Substring(0, 8))
$backupDirectory = Join-Path $destinationRoot $backupId
New-Item -ItemType Directory -Path $backupDirectory -ErrorAction Stop | Out-Null
$composeArgs = @('compose', '--env-file', $environmentFile, '-f', $composeFile)
$archives = @()

foreach ($entry in @(@{ Service = 'postgres'; File = 'ledgerx.dump' }, @{ Service = 'keycloak-postgres'; File = 'keycloak.dump' })) {
    $service = $entry.Service
    $containerId = (& docker @composeArgs ps -q $service).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
        throw "Database service $service is not running."
    }
    $containerFile = "/tmp/ledgerx-backup-$backupId.dump"
    $archivePath = Join-Path $backupDirectory $entry.File
    try {
        & docker @composeArgs exec -T $service sh -c 'pg_dump -Fc --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$1"' sh $containerFile
        if ($LASTEXITCODE -ne 0) { throw "pg_dump failed for $service." }
        & docker @composeArgs exec -T $service pg_restore --list $containerFile | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "pg_restore could not list $service archive." }
        & docker cp "${containerId}:$containerFile" $archivePath | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "docker cp failed for $service." }
        $archives += [ordered]@{
            service = $service
            file = $entry.File
            sha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
            bytes = (Get-Item -LiteralPath $archivePath).Length
        }
    } finally {
        & docker @composeArgs exec -T $service rm -f $containerFile | Out-Null
    }
}

$manifest = [ordered]@{
    format = 1
    created_at_utc = (Get-Date).ToUniversalTime().ToString('o')
    archives = $archives
}
$manifestPath = Join-Path $backupDirectory 'manifest.json'
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8
& (Join-Path $PSScriptRoot 'verify-restore.ps1') -BackupDirectory $backupDirectory
if ($LASTEXITCODE -ne 0) { throw 'Disposable restore verification failed.' }
$manifest['verified_at_utc'] = (Get-Date).ToUniversalTime().ToString('o')
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8
Write-Output "Verified local backup: $backupDirectory"
try {
    & (Join-Path $PSScriptRoot 'replicate-backup.ps1') -BackupDirectory $backupDirectory `
        -EnvFile $environmentFile -Remote $Remote -DownloadVerify:$DownloadVerify `
        -ApplyRetention:$ApplyRetention
} catch {
    throw "Off-host replication failed; verified local backup preserved at $backupDirectory. $($_.Exception.Message)"
}
