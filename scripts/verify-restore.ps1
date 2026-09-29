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
            if ($LASTEXITCODE -ne 0 -or [int]$check -lt 15) { throw 'LedgerX migrations were not restored.' }
            $guard = (& docker exec $containerId psql -X -A -t -v ON_ERROR_STOP=1 -U drill -d drill -c `
                "SELECT COUNT(*) FROM pg_trigger WHERE tgname IN ('ledger_entries_immutable', 'refunds_payment_bounds_guard') AND NOT tgisinternal").Trim()
            if ($LASTEXITCODE -ne 0 -or [int]$guard -ne 2) { throw 'Financial integrity triggers were not restored.' }
            $integritySql = @'
WITH journal_net AS (
    SELECT ledger_transaction_id,
           SUM(CASE WHEN side = 'DEBIT' THEN amount ELSE -amount END) AS net
    FROM ledgerx.ledger_entries GROUP BY ledger_transaction_id
), wallet_balances AS (
    SELECT a.id,
           COALESCE(SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE -e.amount END), 0) AS balance
    FROM ledgerx.ledger_accounts a
    LEFT JOIN ledgerx.ledger_entries e ON e.ledger_account_id = a.id
    WHERE a.account_kind = 'WALLET'
    GROUP BY a.id
), refund_totals AS (
    SELECT p.id, p.amount, COALESCE(SUM(r.amount), 0) AS refunded
    FROM ledgerx.payments p
    LEFT JOIN ledgerx.refunds r ON r.payment_id = p.id
    GROUP BY p.id, p.amount
)
SELECT (SELECT COUNT(*) FROM journal_net WHERE net <> 0) || '|' ||
       (SELECT COUNT(*) FROM wallet_balances WHERE balance < 0) || '|' ||
       (SELECT COUNT(*) FROM refund_totals WHERE refunded > amount) || '|' ||
       (SELECT COUNT(*) FROM ledgerx.payments) || '|' ||
       (SELECT COUNT(*) FROM ledgerx.refunds) || '|' ||
       (SELECT COUNT(*) FROM ledgerx.ledger_transactions);
'@
            $integrity = (& docker exec $containerId psql -X -A -t -v ON_ERROR_STOP=1 -U drill -d drill -c $integritySql).Trim()
            if ($LASTEXITCODE -ne 0) { throw 'Could not check restored financial records.' }
            $counts = $integrity -split '\|'
            if ($counts.Count -ne 6 -or @($counts[0..2] | Where-Object { $_ -ne '0' }).Count -ne 0) {
                throw 'Restored financial records violate a ledger, wallet, or refund invariant.'
            }
            Write-Output "Restored financial records: payments=$($counts[3]), refunds=$($counts[4]), journals=$($counts[5]); invariants hold."
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
