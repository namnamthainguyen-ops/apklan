[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ManagementKey,
    [string]$InstallPath = "$env:ProgramData\LanPowerStandalone",
    [ValidatePattern('^[A-Za-z0-9_.-]{1,100}$')][string]$TaskName = 'LanPowerStandaloneAgent',
    [ValidateRange(1024, 65535)][int]$Port = 17840
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$AgentUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/StandaloneAgent.ps1'
$AgentSha256 = '23FE491784886267053A7AF1AE4CC83AE96DDCA0BDA1BA39C527C803E3240FBB'
$CoreUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/Install-Core.ps1'
$CoreSha256 = '0E451631A697DC0D6CEC8648B2A10A7A4D82192F53443917EB7EF0E2BF6353F3'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run PowerShell as Administrator.' }
try { $keyBytes = [Convert]::FromBase64String($ManagementKey) }
catch { throw 'Management Key is not valid Base64.' }
if ($keyBytes.Length -ne 32) { throw 'Management Key must contain exactly 32 bytes.' }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$tempRoot = Join-Path $env:TEMP ('LanPowerInstall-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $downloadedAgent = Join-Path $tempRoot 'StandaloneAgent.ps1'
    $downloadedCore = Join-Path $tempRoot 'Install-Core.ps1'
    Invoke-WebRequest -UseBasicParsing -Uri $AgentUrl -OutFile $downloadedAgent
    Invoke-WebRequest -UseBasicParsing -Uri $CoreUrl -OutFile $downloadedCore
    if ((Get-FileHash -LiteralPath $downloadedAgent -Algorithm SHA256).Hash -cne $AgentSha256) { throw 'Agent checksum mismatch.' }
    if ((Get-FileHash -LiteralPath $downloadedCore -Algorithm SHA256).Hash -cne $CoreSha256) { throw 'Installer core checksum mismatch.' }
    . $downloadedCore
    Invoke-LanPowerInstall -SourceAgent $downloadedAgent -ManagementKey $ManagementKey -InstallPath $InstallPath -TaskName $TaskName -Port $Port
}
finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
