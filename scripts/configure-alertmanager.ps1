$ErrorActionPreference = 'Stop'
$url = $env:LEDGERX_ALERT_WEBHOOK_URL
if ([string]::IsNullOrWhiteSpace($url)) {
    throw 'Set LEDGERX_ALERT_WEBHOOK_URL to an HTTPS alert receiver before running this script.'
}
$uri = $null
if (-not [Uri]::TryCreate($url, [UriKind]::Absolute, [ref]$uri) -or
    $uri.Scheme -ne 'https' -or [string]::IsNullOrWhiteSpace($uri.Host) -or
    -not [string]::IsNullOrWhiteSpace($uri.UserInfo)) {
    throw 'The alert receiver must be an absolute HTTPS URL without URL userinfo.'
}
$config = [ordered]@{
    route = [ordered]@{
        receiver = 'operator-webhook'
        group_by = @('alertname', 'severity')
        group_wait = '30s'
        group_interval = '5m'
        repeat_interval = '4h'
    }
    receivers = @([ordered]@{
        name = 'operator-webhook'
        webhook_configs = @([ordered]@{ url = $url; send_resolved = $true })
    })
}
$configPath = Join-Path $PSScriptRoot '../monitoring/alertmanager.local.yml'
$config | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $configPath -Encoding utf8
Write-Output 'Wrote ignored monitoring/alertmanager.local.yml. Set LEDGERX_ALERTMANAGER_CONFIG=./monitoring/alertmanager.local.yml and restart Alertmanager.'
