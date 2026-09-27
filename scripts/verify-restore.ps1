param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory
)

$ErrorActionPreference = 'Stop'
$backupRoot = (Resolve-Path -LiteralPath $BackupDirectory).Path
$manifest = Get-Content -LiteralPath (Join-Path $backupRoot 'manifest.json') -Raw | ConvertFrom-Json
if ($manifest.format -ne 1) { throw 'Unsupported backup manifest format.' }
$expected = @('ledgerx.dump', 'keycloak.dump')
if (@($manifest.archives).Count -ne 2) { throw 'Both database archives are required.' }
if ((@($manifest.archives.file | Sort-Object) -join ',') -ne 'keycloak.dump,ledgerx.dump') {
    throw 'The manifest must contain one archive for each database.'
}

foreach ($entry in $manifest.archives) {
    if ($entry.file -notin $expected -or $entry.service -notin @('postgres', 'keycloak-postgres')) {
        throw 'Unexpected database archive in manifest.'
    }
    $archive = Join-Path $backupRoot $entry.file
    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $entry.sha256 -or (Get-Item -LiteralPath $archive).Length -ne $entry.bytes) {
        throw "Archive integrity check failed: $($entry.file)"
    }
    $containerName = 'ledgerx-restore-drill-' + [guid]::NewGuid().ToString('N')
    $containerId = ''
    try {
        $containerId = (& docker run -d --name $containerName --network none `
            -e POSTGRES_USER=drill -e POSTGRES_DB=drill -e POSTGRES_HOST_AUTH_METHOD=trust `
            postgres:17.11-alpine3.24).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
            throw 'Could not create isolated PostgreSQL restore container.'
        }
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            & docker exec $containerId pg_isready -U drill -d drill | Out-Null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw 'Restore container did not become ready.' }
        & docker cp $archive "${containerId}:/tmp/restore.dump" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not copy archive into restore container.' }
        & docker exec $containerId pg_restore --exit-on-error --no-owner --no-acl -U drill -d drill /tmp/restore.dump | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Archive restore failed: $($entry.file)" }
        if ($entry.file -eq 'ledgerx.dump') {
            $check = (& docker exec $containerId psql -X -A -t -v ON_ERROR_STOP=1 -U drill -d drill -c `
                'SELECT COUNT(*) FROM ledgerx.flyway_schema_history WHERE success = true').Trim()
            if ($LASTEXITCODE -ne 0 -or [int]$check -lt 14) { throw 'LedgerX migrations were not restored.' }
            & docker exec $containerId psql -X -A -t -v ON_ERROR_STOP=1 -U drill -d drill -c `
                "SELECT COUNT(*) FROM ledgerx.ledger_entries; SELECT COUNT(*) FROM ledgerx.payments; SELECT COUNT(*) FROM ledgerx.outbox_events;" | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'LedgerX financial tables were not restored.' }
            $guard = (& docker exec $containerId psql -X -A -t -v ON_ERROR_STOP=1 -U drill -d drill -c `
                "SELECT COUNT(*) FROM pg_trigger WHERE tgname = 'ledger_entries_immutable' AND NOT tgisinternal").Trim()
            if ($LASTEXITCODE -ne 0 -or [int]$guard -ne 1) { throw 'Immutable journal guard was not restored.' }
        } else {
            $realmCount = (& docker exec $containerId psql -X -A -t -v ON_ERROR_STOP=1 -U drill -d drill -c `
                'SELECT COUNT(*) FROM public.realm').Trim()
            if ($LASTEXITCODE -ne 0 -or [int]$realmCount -lt 1) { throw 'Keycloak realm data was not restored.' }
        }
        Write-Output "Restored and checked $($entry.file)"
    } finally {
        if (-not [string]::IsNullOrWhiteSpace($containerId)) {
            & docker rm -f $containerId | Out-Null
        }
    }
}
