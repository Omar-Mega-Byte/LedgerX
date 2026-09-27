[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [string]$EventIdsPath,
    [Parameter(Mandatory = $true)]
    [string]$ApiBaseUrl,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]{1,40}$')]
    [string]$IncidentId,
    [ValidateRange(0, 60000)]
    [int]$DelayMilliseconds = 750
)

$ErrorActionPreference = 'Stop'
$token = $env:LEDGERX_OPERATOR_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) { throw 'Set LEDGERX_OPERATOR_TOKEN in the current private shell.' }
$base = $null
if (-not [Uri]::TryCreate($ApiBaseUrl, [UriKind]::Absolute, [ref]$base) -or
    $base.Scheme -ne 'https' -or -not [string]::IsNullOrWhiteSpace($base.UserInfo) -or
    $base.AbsolutePath -ne '/') {
    throw 'ApiBaseUrl must be an HTTPS origin without a path or URL userinfo.'
}
$ids = [Collections.Generic.List[guid]]::new()
$seen = [Collections.Generic.HashSet[guid]]::new()
foreach ($line in Get-Content -LiteralPath $EventIdsPath) {
    $value = $line.Trim()
    if ($value.Length -eq 0) { continue }
    $id = [guid]::Empty
    if (-not [guid]::TryParse($value, [ref]$id)) { throw "Invalid event ID in $EventIdsPath." }
    if (-not $seen.Add($id)) { throw "Duplicate event ID in $EventIdsPath." }
    $ids.Add($id)
}
if ($ids.Count -eq 0) { throw 'The event ID list is empty.' }

$reason = "Kafka broker-loss recovery $IncidentId"
$submitted = 0
foreach ($id in $ids) {
    if (-not $PSCmdlet.ShouldProcess($id.ToString(), 'Request audited outbox replay')) { continue }
    $headers = @{
        Authorization = "Bearer $token"
        'Idempotency-Key' = "kafka-loss-$IncidentId-$id"
    }
    $url = '{0}/api/v1/operations/outbox-events/{1}/replay' -f $ApiBaseUrl.TrimEnd('/'), $id
    $body = @{ reason = $reason } | ConvertTo-Json -Compress
    Invoke-RestMethod -Uri $url -Method Post -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 30 | Out-Null
    $submitted++
    if ($DelayMilliseconds -gt 0) { Start-Sleep -Milliseconds $DelayMilliseconds }
}
Write-Output "Submitted $submitted of $($ids.Count) audited replay requests for $IncidentId."
