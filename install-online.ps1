[CmdletBinding(DefaultParameterSetName = 'RoomKey')]
param(
    [Parameter(Mandatory, ParameterSetName = 'RoomKey')][string]$RoomKey,
    [Parameter(Mandatory, ParameterSetName = 'ManagementKey')][string]$ManagementKey,
    [string]$InstallPath = "$env:ProgramData\LanPowerStandalone",
    [ValidatePattern('^[A-Za-z0-9_.-]{1,100}$')][string]$TaskName = 'LanPowerStandaloneAgent',
    [ValidateRange(1024, 65535)][int]$Port = 17840,
    [switch]$PrepareUwf,
    [switch]$SkipUwfPreparation,
    [string[]]$VpnNetworks
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$AgentUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/StandaloneAgent.ps1'
$AgentSha256 = '26054B7E0B3A684D43BF33FBC225FC88F5459C094293E7125EAAA13791C76CAF'
$CoreUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/Install-Core.ps1'
$CoreSha256 = '4C3449EF636475E7B98F5B6EBC1752A0F9D67DE05374E4B11DF93C3458BCFEF4'
$UpdaterUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/Update-Agent.ps1'
$UpdaterSha256 = '9E0B25020138259E77333FE048E85B899AB895F85284CCE536E9E716656701D1'
$PublicKeyUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/Update-PublicKey.xml'
$PublicKeySha256 = '050AB33ED6D45DDEF846DDE7D5882E2B1F698154C8306860DC70531A23EE0333'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run PowerShell as Administrator.' }
if ($PSCmdlet.ParameterSetName -eq 'RoomKey') {
    if ($RoomKey -cnotmatch '\A[\x21-\x7E]{8}\z') { throw 'Room Key must contain exactly 8 printable ASCII characters without spaces (case-sensitive).' }
}
else {
    try { $keyBytes = [Convert]::FromBase64String($ManagementKey) }
    catch { throw 'Management Key is not valid Base64.' }
    if ($keyBytes.Length -ne 32) { throw 'Management Key must contain exactly 32 bytes.' }
}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$tempRoot = Join-Path $env:TEMP ('LanPowerInstall-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $downloadedAgent = Join-Path $tempRoot 'StandaloneAgent.ps1'
    $downloadedCore = Join-Path $tempRoot 'Install-Core.ps1'
    $downloadedUpdater = Join-Path $tempRoot 'Update-Agent.ps1'
    $downloadedKey = Join-Path $tempRoot 'Update-PublicKey.xml'
    Invoke-WebRequest -UseBasicParsing -Uri $AgentUrl -OutFile $downloadedAgent
    Invoke-WebRequest -UseBasicParsing -Uri $CoreUrl -OutFile $downloadedCore
    Invoke-WebRequest -UseBasicParsing -Uri $UpdaterUrl -OutFile $downloadedUpdater
    Invoke-WebRequest -UseBasicParsing -Uri $PublicKeyUrl -OutFile $downloadedKey
    if ((Get-FileHash -LiteralPath $downloadedAgent -Algorithm SHA256).Hash -cne $AgentSha256) { throw 'Agent checksum mismatch.' }
    if ((Get-FileHash -LiteralPath $downloadedCore -Algorithm SHA256).Hash -cne $CoreSha256) { throw 'Installer core checksum mismatch.' }
    if ((Get-FileHash -LiteralPath $downloadedUpdater -Algorithm SHA256).Hash -cne $UpdaterSha256) { throw 'Updater checksum mismatch.' }
    if ((Get-FileHash -LiteralPath $downloadedKey -Algorithm SHA256).Hash -cne $PublicKeySha256) { throw 'Update trust key checksum mismatch.' }
    . $downloadedCore
    $installParameters = @{SourceAgent = $downloadedAgent; InstallPath = $InstallPath; TaskName = $TaskName; Port = $Port; PrepareUwf = $PrepareUwf; SkipUwfPreparation = $SkipUwfPreparation}
    if ($PSCmdlet.ParameterSetName -eq 'RoomKey') { $installParameters.RoomKey = $RoomKey }
    else { $installParameters.ManagementKey = $ManagementKey }
    if ($PSBoundParameters.ContainsKey('VpnNetworks')) { $installParameters.VpnNetworks = $VpnNetworks }
    Invoke-LanPowerInstall @installParameters
}
finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
