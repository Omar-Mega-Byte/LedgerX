Set-StrictMode -Version Latest

function Resolve-LedgerXBackupRemote {
    param([string]$Remote, [string]$EnvFile = '.env')

    if ([string]::IsNullOrWhiteSpace($Remote)) { $Remote = $env:LEDGERX_BACKUP_REMOTE }
    if ([string]::IsNullOrWhiteSpace($Remote) -and (Test-Path -LiteralPath $EnvFile)) {
        $settings = @(Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match '^\s*LEDGERX_BACKUP_REMOTE\s*=' })
        if ($settings.Count -gt 1) { throw 'LEDGERX_BACKUP_REMOTE appears more than once in the environment file.' }
        if ($settings.Count -eq 1) {
            $Remote = ($settings[0] -split '=', 2)[1].Trim().Trim('"', "'")
        }
    }
    if ([string]::IsNullOrWhiteSpace($Remote)) { $Remote = 'gdrive:LedgerX-Backups' }
    $Remote = $Remote.Trim().TrimEnd('/')
    if ($Remote -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]*:(?:[A-Za-z0-9_-]+/)*LedgerX-Backups$') {
        throw 'Backup remote must be a named rclone remote ending in the exact LedgerX-Backups folder.'
    }
    return $Remote
}

function Get-LedgerXVerifiedBackup {
    param([Parameter(Mandatory)][string]$BackupDirectory)

    $root = (Resolve-Path -LiteralPath $BackupDirectory -ErrorAction Stop).Path
    $id = Split-Path -Leaf $root
    if ($id -notmatch '^\d{8}T\d{6}Z-[a-f0-9]{8}$') { throw 'Unexpected backup directory name.' }
    $allowed = @('ledgerx.dump', 'keycloak.dump', 'manifest.json', 'ledgerx-backup.json')
    foreach ($item in Get-ChildItem -LiteralPath $root -Force) {
        if ($item.PSIsContainer -or $item.Name -notin $allowed) {
            throw "Unexpected file in backup directory: $($item.Name)"
        }
    }
    $manifestPath = Join-Path $root 'manifest.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    if ($manifest.format -ne 1 -or [string]::IsNullOrWhiteSpace($manifest.verified_at_utc)) {
        throw 'Backup is not marked as restore-verified.'
    }
    $expected = @{'ledgerx.dump' = 'postgres'; 'keycloak.dump' = 'keycloak-postgres'}
    if (@($manifest.archives).Count -ne 2) { throw 'Both database archives are required.' }
    $seen = @{}
    foreach ($entry in $manifest.archives) {
        if (-not $expected.ContainsKey([string]$entry.file) -or $expected[[string]$entry.file] -ne $entry.service -or
            $seen.ContainsKey([string]$entry.file)) { throw 'Unexpected or duplicate database archive in manifest.' }
        $seen[[string]$entry.file] = $true
        $archive = Join-Path $root ([string]$entry.file)
        $actual = Get-Item -LiteralPath $archive -ErrorAction Stop
        if ($actual.Length -ne [long]$entry.bytes -or
            (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) {
            throw "Local archive integrity check failed: $($entry.file)"
        }
    }
    if ($seen.Count -ne 2) { throw 'Both database archives are required.' }
    return [pscustomobject]@{ Id = $id; Root = $root; ManifestPath = $manifestPath; Manifest = $manifest }
}

function Get-LedgerXRetentionPlan {
    param([string[]]$BackupIds, [datetime]$NowUtc = [datetime]::UtcNow)

    $records = @(
        foreach ($id in $BackupIds) {
            if ($id -notmatch '^\d{8}T\d{6}Z-[a-f0-9]{8}$') { continue }
            $stamp = [datetime]::MinValue
            if (-not [datetime]::TryParseExact($id.Substring(0, 16), 'yyyyMMddTHHmmssZ',
                    [Globalization.CultureInfo]::InvariantCulture,
                    [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$stamp)) { continue }
            if ($stamp.ToUniversalTime() -gt $NowUtc.ToUniversalTime().AddMinutes(5)) { continue }
            [pscustomobject]@{ Id = $id; Stamp = $stamp.ToUniversalTime() }
        }
    ) | Sort-Object Stamp -Descending
    $keep = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $days = @{}; $weeks = @{}; $months = @{}
    foreach ($record in $records) {
        $day = $record.Stamp.ToString('yyyy-MM-dd')
        $week = '{0}-{1:00}' -f [Globalization.ISOWeek]::GetYear($record.Stamp),
            [Globalization.ISOWeek]::GetWeekOfYear($record.Stamp)
        $month = $record.Stamp.ToString('yyyy-MM')
        if ($days.Count -lt 7 -and -not $days.ContainsKey($day)) {
            $days[$day] = $true; [void]$keep.Add($record.Id)
        }
        if ($weeks.Count -lt 4 -and -not $weeks.ContainsKey($week)) {
            $weeks[$week] = $true; [void]$keep.Add($record.Id)
        }
        if ($months.Count -lt 6 -and -not $months.ContainsKey($month)) {
            $months[$month] = $true; [void]$keep.Add($record.Id)
        }
    }
    return [pscustomobject]@{
        Keep = @($records | Where-Object { $keep.Contains($_.Id) } | ForEach-Object Id)
        Delete = @($records | Where-Object { -not $keep.Contains($_.Id) } | ForEach-Object Id)
    }
}

function Get-LedgerXRclone {
    $command = Get-Command -Name rclone -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $command) {
        throw 'rclone was not found on PATH. Add its executable folder to PATH for the account running the backup task.'
    }
    return $command.Source
}

function Invoke-LedgerXRclone {
    param([string]$Executable, [string[]]$Arguments, [string]$Operation)
    $output = & $Executable @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) { throw "rclone $Operation failed (exit $LASTEXITCODE)." }
    return $output
}

function Invoke-LedgerXOffHostBackup {
    param(
        [Parameter(Mandatory)][string]$BackupDirectory,
        [string]$Remote,
        [string]$EnvFile = '.env',
        [switch]$DownloadVerify,
        [switch]$ApplyRetention
    )

    $backup = Get-LedgerXVerifiedBackup -BackupDirectory $BackupDirectory
    $destination = Resolve-LedgerXBackupRemote -Remote $Remote -EnvFile $EnvFile
    $rclone = Get-LedgerXRclone
    $remoteBackup = "$destination/$($backup.Id)"
    $markerPath = Join-Path $backup.Root 'ledgerx-backup.json'
    $marker = [ordered]@{
        format = 1
        backup_id = $backup.Id
        manifest_sha256 = (Get-FileHash -LiteralPath $backup.ManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    if (Test-Path -LiteralPath $markerPath) {
        $existing = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
        if ($existing.format -ne 1 -or $existing.backup_id -ne $marker.backup_id -or
            $existing.manifest_sha256 -ne $marker.manifest_sha256) {
            throw 'Existing backup marker does not match the verified local manifest.'
        }
    } else {
        $marker | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8
    }

    Invoke-LedgerXRclone $rclone @('copy', '--immutable', $backup.Root, $remoteBackup) 'upload' | Out-Null
    Invoke-LedgerXRclone $rclone @('check', '--download', $backup.Root, $remoteBackup) 'byte verification' | Out-Null
    Write-Output "Verified off-host backup: $remoteBackup"

    if ($DownloadVerify) {
        $tempRoot = [IO.Path]::GetTempPath()
        $temp = Join-Path $tempRoot ('ledgerx-remote-restore-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $temp -ErrorAction Stop | Out-Null
        try {
            Invoke-LedgerXRclone $rclone @('copy', '--immutable', $remoteBackup, $temp) 'download' | Out-Null
            & (Join-Path $PSScriptRoot 'verify-restore.ps1') -BackupDirectory $temp
            Write-Output "Remote download and isolated restore verified: $remoteBackup"
        } finally {
            if ($temp.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
                (Split-Path -Leaf $temp) -match '^ledgerx-remote-restore-[a-f0-9]{32}$') {
                Remove-Item -LiteralPath $temp -Recurse -Force
            }
        }
    }

    if ($ApplyRetention) {
        $names = @(Invoke-LedgerXRclone $rclone @('lsf', '--dirs-only', '--format', 'p', $destination) 'retention listing')
        $ids = @($names | ForEach-Object { $_.TrimEnd('/') } | Where-Object { $_ -match '^\d{8}T\d{6}Z-[a-f0-9]{8}$' })
        $plan = Get-LedgerXRetentionPlan -BackupIds $ids
        foreach ($id in $plan.Delete) {
            $child = "$destination/$id"
            $remoteMarker = Invoke-LedgerXRclone $rclone @('cat', "$child/ledgerx-backup.json") 'retention marker read' |
                Out-String | ConvertFrom-Json
            if ($remoteMarker.format -ne 1 -or $remoteMarker.backup_id -ne $id -or
                $remoteMarker.manifest_sha256 -notmatch '^[a-f0-9]{64}$') {
                throw "Refusing to prune unrecognized backup: $id"
            }
            Invoke-LedgerXRclone $rclone @('purge', $child) 'retention prune' | Out-Null
            Write-Output "Pruned off-host backup: $id"
        }
    }
}

Export-ModuleMember -Function Resolve-LedgerXBackupRemote, Get-LedgerXVerifiedBackup,
    Get-LedgerXRetentionPlan, Get-LedgerXRclone, Invoke-LedgerXOffHostBackup
