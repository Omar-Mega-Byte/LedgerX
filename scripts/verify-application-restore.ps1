param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory,
    [string]$EnvFile = '.env'
)

$ErrorActionPreference = 'Stop'
$backupRoot = (Resolve-Path -LiteralPath $BackupDirectory).Path
$archive = Join-Path $backupRoot 'ledgerx.dump'
$manifest = Get-Content -LiteralPath (Join-Path $backupRoot 'manifest.json') -Raw | ConvertFrom-Json
$entry = $manifest.archives | Where-Object file -eq 'ledgerx.dump' | Select-Object -First 1
if (-not $entry -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) {
    throw 'The LedgerX archive does not match its manifest.'
}

$settings = @{}
Get-Content -LiteralPath $EnvFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $item = $_ -split '=', 2
        $settings[$item[0]] = $item[1]
    }
$issuer = $settings['LEDGERX_OIDC_ISSUER_URI']
if (-not $issuer.StartsWith('https://')) { throw 'A public HTTPS issuer is required for the production-profile drill.' }

$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$network = "ledgerx-restore-$suffix"
$database = "ledgerx-restore-db-$suffix"
$application = "ledgerx-restore-app-$suffix"
$networkCreated = $false
$databaseCreated = $false
$applicationCreated = $false
try {
    & docker network create $network | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated restore network.' }
    $networkCreated = $true

    & docker run -d --pull never --name $database --network $network -e POSTGRES_USER=drill -e POSTGRES_DB=drill -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17.11-alpine3.24 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated PostgreSQL.' }
    $databaseCreated = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & docker exec $database pg_isready -U drill -d drill | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Isolated PostgreSQL did not become ready.' }
    & docker cp $archive "${database}:/tmp/restore.dump" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the archive to isolated PostgreSQL.' }
    & docker exec $database pg_restore --exit-on-error --no-owner --no-acl -U drill -d drill /tmp/restore.dump | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restore the LedgerX archive.' }
    $before = (& docker exec $database psql -X -A -t -v ON_ERROR_STOP=1 -U drill -d drill -c 'SELECT COUNT(*) FROM ledgerx.reconciliation_runs').Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not read restored reconciliation history.' }

    $appArgs = @(
        'run', '-d', '--pull', 'never', '--name', $application, '--network', $network,
        '--read-only', '--tmpfs', '/tmp:size=64m', '--cap-drop', 'ALL',
        '-e', 'SPRING_PROFILES_ACTIVE=prod',
        '-e', "LEDGERX_DB_URL=jdbc:postgresql://${database}:5432/drill",
        '-e', 'LEDGERX_DB_USERNAME=drill',
        '-e', 'LEDGERX_DB_PASSWORD=unused',
        '-e', 'LEDGERX_KAFKA_BOOTSTRAP_SERVERS=unavailable:9092',
        '-e', 'LEDGERX_KAFKA_CONSUMER_ENABLED=false',
        '-e', 'LEDGERX_OUTBOX_PUBLISHER_ENABLED=false',
        '-e', 'LEDGERX_WEBHOOK_CONSUMER_ENABLED=false',
        '-e', 'LEDGERX_WEBHOOK_DISPATCHER_ENABLED=false',
        '-e', 'LEDGERX_RECONCILIATION_ENABLED=true',
        '-e', 'LEDGERX_RECONCILIATION_CRON=*/10 * * * * *',
        '-e', "LEDGERX_OIDC_ISSUER_URI=$issuer",
        '-e', 'LEDGERX_OIDC_API_CLIENT_ID=ledgerx-api',
        '-e', 'LEDGERX_WEB_CLIENT_ID=ledgerx-web',
        'ledgerx-ledgerx:latest'
    )
    & docker @appArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start LedgerX against the isolated restore.' }
    $applicationCreated = $true

    $healthy = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker exec $application wget -q -O /dev/null http://localhost:8080/actuator/health 2>$null
        if ($LASTEXITCODE -eq 0) { $healthy = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $healthy) { throw 'Restored LedgerX did not become healthy.' }

    $after = $before
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $after = (& docker exec $database psql -X -A -t -v ON_ERROR_STOP=1 -U drill -d drill -c 'SELECT COUNT(*) FROM ledgerx.reconciliation_runs').Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect restored reconciliation evidence.' }
        if ([int]$after -gt [int]$before) { break }
        Start-Sleep -Seconds 2
    }
    if ([int]$after -le [int]$before) { throw 'Reconciliation did not run against the restored database.' }
    $latest = (& docker exec $database psql -X -A -t -v ON_ERROR_STOP=1 -U drill -d drill -c 'SELECT status || '':'' || finding_count FROM ledgerx.reconciliation_runs ORDER BY started_at DESC LIMIT 1').Trim()
    if ($LASTEXITCODE -ne 0 -or $latest -ne 'COMPLETED:0') {
        throw "Restored reconciliation did not complete cleanly: $latest"
    }
    $version = (& docker exec $database psql -X -A -t -v ON_ERROR_STOP=1 -U drill -d drill -c 'SELECT version FROM ledgerx.flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1').Trim()
    if ($LASTEXITCODE -ne 0 -or [int]$version -lt 15) { throw 'The restored app did not validate the current schema.' }
    Write-Output "Isolated production-profile LedgerX healthy on restored schema v$version; reconciliation $latest."
} finally {
    if ($applicationCreated) { & docker rm -f -v $application | Out-Null }
    if ($databaseCreated) { & docker rm -f -v $database | Out-Null }
    if ($networkCreated) { & docker network rm $network | Out-Null }
}
