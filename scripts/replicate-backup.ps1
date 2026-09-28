param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory,
    [string]$EnvFile = '.env',
    [string]$Remote,
    [switch]$DownloadVerify,
    [switch]$ApplyRetention
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'backup-offhost.psm1') -Force
Invoke-LedgerXOffHostBackup -BackupDirectory $BackupDirectory -EnvFile $EnvFile `
    -Remote $Remote -DownloadVerify:$DownloadVerify -ApplyRetention:$ApplyRetention
