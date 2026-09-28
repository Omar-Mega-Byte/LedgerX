$ErrorActionPreference = 'Stop'
$modulePath = (Resolve-Path (Join-Path $PSScriptRoot '..\backup-offhost.psm1')).Path
Import-Module $modulePath -Force

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Test failed: $Message" }
}

$tempRoot = [IO.Path]::GetTempPath()
$testRoot = Join-Path $tempRoot ('ledgerx-backup-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$oldPath = $env:PATH
$oldRemote = $env:LEDGERX_BACKUP_REMOTE
try {
    $env:LEDGERX_BACKUP_REMOTE = $null
    Assert-True ((Resolve-LedgerXBackupRemote -Remote 'gdrive:LedgerX-Backups') -eq 'gdrive:LedgerX-Backups') 'valid remote'
    foreach ($bad in @('gdrive:', 'gdrive:Other', 'gdrive:LedgerX-Backups/..', 'gdrive:LedgerX-Backups/other')) {
        $rejected = $false
        try { Resolve-LedgerXBackupRemote -Remote $bad | Out-Null } catch { $rejected = $true }
        Assert-True $rejected "unsafe remote rejected: $bad"
    }
    $envFile = Join-Path $testRoot 'settings.env'
    Set-Content -LiteralPath $envFile -Value 'LEDGERX_BACKUP_REMOTE=mock:LedgerX-Backups'
    Assert-True ((Resolve-LedgerXBackupRemote -EnvFile $envFile) -eq 'mock:LedgerX-Backups') 'environment file remote'

    $fixedNow = [datetime]::SpecifyKind([datetime]'2026-09-28T21:00:00', [DateTimeKind]::Utc)
    $ids = @(0..220 | ForEach-Object {
        '{0}-00000000' -f $fixedNow.Date.AddDays(-$_).ToString('yyyyMMddT000000Z')
    })
    $plan = Get-LedgerXRetentionPlan -BackupIds ($ids + 'unmanaged-folder' + '20990101T000000Z-ffffffff') -NowUtc $fixedNow
    Assert-True ($plan.Keep -contains $ids[0]) 'newest backup retained'
    Assert-True ($plan.Delete -contains $ids[220]) 'old backup selected for pruning'
    Assert-True ($plan.Keep.Count -le 17) 'bounded daily weekly monthly selection'
    foreach ($id in $ids[0..6]) { Assert-True ($plan.Keep -contains $id) 'latest daily backups retained' }
    $weekly = @($ids | Group-Object {
        $stamp = [datetime]::ParseExact($_.Substring(0, 16), 'yyyyMMddTHHmmssZ',
            [Globalization.CultureInfo]::InvariantCulture)
        '{0}-{1:00}' -f [Globalization.ISOWeek]::GetYear($stamp), [Globalization.ISOWeek]::GetWeekOfYear($stamp)
    } | Sort-Object Name -Descending | Select-Object -First 4)
    foreach ($group in $weekly) { Assert-True ($plan.Keep -contains $group.Group[0]) 'latest weekly backups retained' }
    $monthly = @($ids | Group-Object { $_.Substring(0, 6) } | Sort-Object Name -Descending | Select-Object -First 6)
    foreach ($group in $monthly) { Assert-True ($plan.Keep -contains $group.Group[0]) 'latest monthly backups retained' }
    Assert-True ($plan.Delete -notcontains 'unmanaged-folder') 'unmanaged directory ignored'

    $backupId = '20260801T000000Z-deadbeef'
    $backup = Join-Path $testRoot $backupId
    New-Item -ItemType Directory -Path $backup | Out-Null
    Set-Content -LiteralPath (Join-Path $backup 'ledgerx.dump') -Value 'financial data'
    Set-Content -LiteralPath (Join-Path $backup 'keycloak.dump') -Value 'identity data'
    $archives = @(
        foreach ($name in @('ledgerx.dump', 'keycloak.dump')) {
            $path = Join-Path $backup $name
            [ordered]@{
                service = if ($name -eq 'ledgerx.dump') { 'postgres' } else { 'keycloak-postgres' }
                file = $name
                sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
                bytes = (Get-Item -LiteralPath $path).Length
            }
        }
    )
    [ordered]@{format=1; created_at_utc='2026-08-01T00:00:00Z'; archives=$archives; verified_at_utc='2026-08-01T00:01:00Z'} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup 'manifest.json')
    Assert-True ((Get-LedgerXVerifiedBackup -BackupDirectory $backup).Id -eq $backupId) 'valid local backup'
    Add-Content -LiteralPath (Join-Path $backup 'ledgerx.dump') -Value 'corruption'
    $rejected = $false
    try { Get-LedgerXVerifiedBackup -BackupDirectory $backup | Out-Null } catch { $rejected = $true }
    Assert-True $rejected 'corrupt local archive rejected'
    Set-Content -LiteralPath (Join-Path $backup 'ledgerx.dump') -Value 'financial data'

    $stub = Join-Path $testRoot 'rclone.cmd'
    Set-Content -LiteralPath $stub -Value "@echo off`r`nexit /b 17`r`n" -NoNewline
    $env:PATH = "$testRoot;$oldPath"
    $scriptPath = (Resolve-Path (Join-Path $PSScriptRoot '..\replicate-backup.ps1')).Path
    & (Get-Process -Id $PID).Path -NoProfile -File $scriptPath -BackupDirectory $backup -Remote 'mock:LedgerX-Backups' *> $null
    Assert-True ($LASTEXITCODE -ne 0) 'failed upload exits nonzero'
    Assert-True ((Test-Path -LiteralPath (Join-Path $backup 'ledgerx.dump')) -and
        (Test-Path -LiteralPath (Join-Path $backup 'keycloak.dump'))) 'failed upload preserves local archives'
    Write-Output 'Backup off-host tests passed.'
} finally {
    $env:PATH = $oldPath
    $env:LEDGERX_BACKUP_REMOTE = $oldRemote
    if ($testRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $testRoot) -match '^ledgerx-backup-tests-[a-f0-9]{32}$') {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
exit 0
