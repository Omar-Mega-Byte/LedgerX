param([string]$EnvFile = '.env')

$ErrorActionPreference = 'Stop'
$settings = @{}
Get-Content -LiteralPath $EnvFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $entry = $_ -split '=', 2
        $settings[$entry[0]] = $entry[1]
    }

$base = 'https://' + $settings['KEYCLOAK_PUBLIC_DOMAIN']
$webOrigin = 'https://' + $settings['LEDGERX_PUBLIC_DOMAIN']
if ($settings['LEDGERX_OIDC_ISSUER_URI'] -ne "$base/realms/ledgerx") {
    throw 'The configured LedgerX issuer does not match the public Keycloak realm.'
}

$token = Invoke-RestMethod -Method Post -Uri "$base/realms/master/protocol/openid-connect/token" -Body @{
    client_id = 'admin-cli'
    grant_type = 'password'
    username = $settings['KC_BOOTSTRAP_ADMIN_USERNAME']
    password = $settings['KC_BOOTSTRAP_ADMIN_PASSWORD']
}
$headers = @{ Authorization = 'Bearer ' + $token.access_token }
$realmApi = "$base/admin/realms/ledgerx"
function Get-RealmResource([string]$Path) {
    Invoke-RestMethod -Uri "$realmApi/$Path" -Headers $headers
}
function Post-RealmResource([string]$Path, $Body) {
    Invoke-RestMethod -Method Post -Uri "$realmApi/$Path" -Headers $headers -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 30)
}
function Put-RealmResource([string]$Path, $Body) {
    Invoke-RestMethod -Method Put -Uri "$realmApi/$Path" -Headers $headers -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 30)
}

$scopes = Get-RealmResource 'client-scopes'
$scope = $scopes | Where-Object name -eq 'ledgerx-owner' | Select-Object -First 1
if (-not $scope) { throw 'The deployed realm lacks the ledgerx-owner client scope.' }
$mapperNames = @((Get-RealmResource "client-scopes/$($scope.id)/protocol-mappers/models").name)
foreach ($required in @('ledgerx-owner-id', 'ledgerx-api-audience')) {
    if ($required -notin $mapperNames) { throw "The deployed realm lacks the $required mapper." }
}
if ('ledgerx-realm-roles' -notin $mapperNames) {
    Post-RealmResource "client-scopes/$($scope.id)/protocol-mappers/models" @{
        name = 'ledgerx-realm-roles'
        protocol = 'openid-connect'
        protocolMapper = 'oidc-usermodel-realm-role-mapper'
        consentRequired = $false
        config = @{
            multivalued = 'true'
            'claim.name' = 'realm_access.roles'
            'jsonType.label' = 'String'
            'id.token.claim' = 'false'
            'access.token.claim' = 'true'
        }
    } | Out-Null
    Write-Output 'Added missing realm-role access-token mapper.'
}
if ('ledgerx-subject' -notin $mapperNames) {
    Post-RealmResource "client-scopes/$($scope.id)/protocol-mappers/models" @{
        name = 'ledgerx-subject'
        protocol = 'openid-connect'
        protocolMapper = 'oidc-sub-mapper'
        consentRequired = $false
        config = @{
            'access.token.claim' = 'true'
            'id.token.claim' = 'true'
            'userinfo.token.claim' = 'true'
        }
    } | Out-Null
    Write-Output 'Added missing subject claim mapper.'
}

$roles = @((Get-RealmResource 'roles').name)
if ('ledgerx-operator' -notin $roles) {
    Post-RealmResource 'roles' @{
        name = 'ledgerx-operator'
        description = 'May provision wallet owners and inspect LedgerX operational evidence.'
    } | Out-Null
    Write-Output 'Added missing operator role.'
}

$profile = Get-RealmResource 'users/profile'
if ('ledgerx_owner_id' -notin @($profile.attributes.name)) {
    $profile.attributes = @($profile.attributes) + @([pscustomobject]@{
        name = 'ledgerx_owner_id'
        displayName = 'LedgerX owner ID'
        multivalued = $false
        permissions = @{
            view = @('admin')
            edit = @('admin')
        }
    })
    Put-RealmResource 'users/profile' $profile | Out-Null
    Write-Output 'Added admin-only owner ID profile attribute.'
}

$clients = Get-RealmResource 'clients?max=100'
$webClient = $clients | Where-Object clientId -eq 'ledgerx-web' | Select-Object -First 1
if (-not $webClient) {
    Post-RealmResource 'clients' @{
        clientId = 'ledgerx-web'
        name = 'LedgerX Workbench'
        description = 'Public browser client using authorization code and PKCE.'
        protocol = 'openid-connect'
        enabled = $true
        publicClient = $true
        standardFlowEnabled = $true
        implicitFlowEnabled = $false
        directAccessGrantsEnabled = $false
        serviceAccountsEnabled = $false
        redirectUris = @("$webOrigin/")
        webOrigins = @($webOrigin)
        attributes = @{
            'pkce.code.challenge.method' = 'S256'
            'post.logout.redirect.uris' = "$webOrigin/?signed-out=1"
        }
    } | Out-Null
    Write-Output 'Added missing public PKCE workbench client.'
    $newClients = Get-RealmResource 'clients?clientId=ledgerx-web'
    $webClient = $newClients | Select-Object -First 1
}

if (-not $webClient.publicClient -or -not $webClient.standardFlowEnabled -or
    $webClient.directAccessGrantsEnabled -or
    @($webClient.redirectUris).Count -ne 1 -or $webClient.redirectUris[0] -ne "$webOrigin/" -or
    $webClient.attributes.'pkce.code.challenge.method' -ne 'S256') {
    throw 'The deployed workbench client has unexpected security settings.'
}
$defaults = @((Get-RealmResource "clients/$($webClient.id)/default-client-scopes").name)
if ('ledgerx-owner' -notin $defaults) {
    Put-RealmResource "clients/$($webClient.id)/default-client-scopes/$($scope.id)" @{} | Out-Null
    Write-Output 'Attached the owner, audience, and role scope to the workbench client.'
}
$finalProfile = Get-RealmResource 'users/profile'
$ownerAttribute = $finalProfile.attributes | Where-Object name -eq 'ledgerx_owner_id' | Select-Object -First 1
$finalMappers = @((Get-RealmResource "client-scopes/$($scope.id)/protocol-mappers/models").name)
$finalRoles = @((Get-RealmResource 'roles').name)
$finalDefaults = @((Get-RealmResource "clients/$($webClient.id)/default-client-scopes").name)
if (-not $ownerAttribute -or ($ownerAttribute.permissions.edit -join ',') -ne 'admin' -or
    'ledgerx-realm-roles' -notin $finalMappers -or 'ledgerx-subject' -notin $finalMappers -or
    'ledgerx-operator' -notin $finalRoles -or 'ledgerx-owner' -notin $finalDefaults) {
    throw 'Realm verification failed after reconciliation.'
}
Write-Output 'Production realm browser client, subject, owner claim, audience, and operator role verified.'
