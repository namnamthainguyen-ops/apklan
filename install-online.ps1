[CmdletBinding(DefaultParameterSetName = 'RoomKey')]
param(
    [Parameter(Mandatory, ParameterSetName = 'RoomKey')][string]$RoomKey,
    [Parameter(Mandatory, ParameterSetName = 'ManagementKey')][string]$ManagementKey,
    [string]$InstallPath = "$env:ProgramData\LanPowerStandalone",
    [ValidatePattern('^[A-Za-z0-9_.-]{1,100}$')][string]$TaskName = 'LanPowerStandaloneAgent',
    [ValidateRange(1024, 65535)][int]$Port = 17840,
    [switch]$PrepareUwf,
    [switch]$SkipUwfPreparation,
    [switch]$SkipMediaSetup,
    [string[]]$VpnNetworks
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$AgentUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/StandaloneAgent.ps1'
$AgentSha256 = '081F687274BE2493715E63FEACFA6438A603159E5F31D9CBA48129EF698A5DF4'
$CoreUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/Install-Core.ps1'
$CoreSha256 = '3A95E2A45D9BDF34B5A430186C9AA1479E2B339035B4507CBBAD3FBCA8AAE5B2'
$UpdaterUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/Update-Agent.ps1'
$UpdaterSha256 = '8B9DBD1BD8E79E8856B988D51D4AC898B7875D26A1725BB43F8345AA99D4C3E7'
$PublicKeyUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/Update-PublicKey.xml'
$PublicKeySha256 = '050AB33ED6D45DDEF846DDE7D5882E2B1F698154C8306860DC70531A23EE0333'
$RuntimeUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20.Runtime-1.3.2.dll'
$RuntimeSha256 = '3D6552D55B7646E703AF217B0527AA3F94C99C805D4A098EA11D52223A9A23B1'
$PlayerUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20.Player-1.3.2.exe'
$PlayerSha256 = '555932A6DF0BBB55260AC1CC07FC9E2231CB27C0C519D31CF40ED5EB7413C13F'
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
    $downloadedRuntime = Join-Path $tempRoot 'RoomTN20.Runtime.dll'
    $downloadedPlayer = Join-Path $tempRoot 'RoomTN20.Player.exe'
    Invoke-WebRequest -UseBasicParsing -Uri $AgentUrl -OutFile $downloadedAgent
    Invoke-WebRequest -UseBasicParsing -Uri $CoreUrl -OutFile $downloadedCore
    Invoke-WebRequest -UseBasicParsing -Uri $UpdaterUrl -OutFile $downloadedUpdater
    Invoke-WebRequest -UseBasicParsing -Uri $PublicKeyUrl -OutFile $downloadedKey
    Invoke-WebRequest -UseBasicParsing -Uri $RuntimeUrl -OutFile $downloadedRuntime
    Invoke-WebRequest -UseBasicParsing -Uri $PlayerUrl -OutFile $downloadedPlayer
    if ((Get-FileHash -LiteralPath $downloadedAgent -Algorithm SHA256).Hash -cne $AgentSha256) { throw 'Agent checksum mismatch.' }
    if ((Get-FileHash -LiteralPath $downloadedCore -Algorithm SHA256).Hash -cne $CoreSha256) { throw 'Installer core checksum mismatch.' }
    if ((Get-FileHash -LiteralPath $downloadedUpdater -Algorithm SHA256).Hash -cne $UpdaterSha256) { throw 'Updater checksum mismatch.' }
    if ((Get-FileHash -LiteralPath $downloadedKey -Algorithm SHA256).Hash -cne $PublicKeySha256) { throw 'Update trust key checksum mismatch.' }
    if ((Get-FileHash -LiteralPath $downloadedRuntime -Algorithm SHA256).Hash -cne $RuntimeSha256) { throw 'Runtime checksum mismatch.' }
    if ((Get-FileHash -LiteralPath $downloadedPlayer -Algorithm SHA256).Hash -cne $PlayerSha256) { throw 'Display checksum mismatch.' }
    . $downloadedCore
    $installParameters = @{SourceAgent = $downloadedAgent; InstallPath = $InstallPath; TaskName = $TaskName; Port = $Port; PrepareUwf = $PrepareUwf; SkipUwfPreparation = $SkipUwfPreparation; SkipMediaSetup = $SkipMediaSetup}
    if ($PSCmdlet.ParameterSetName -eq 'RoomKey') { $installParameters.RoomKey = $RoomKey }
    else { $installParameters.ManagementKey = $ManagementKey }
    if ($PSBoundParameters.ContainsKey('VpnNetworks')) { $installParameters.VpnNetworks = $VpnNetworks }
    Invoke-LanPowerInstall @installParameters
}
finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
