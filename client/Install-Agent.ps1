[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ManagementKey,
    [string]$InstallPath = "$env:ProgramData\LanPowerStandalone",
    [ValidatePattern('^[A-Za-z0-9_.-]{1,100}$')][string]$TaskName = 'LanPowerStandaloneAgent',
    [ValidateRange(1024, 65535)][int]$Port = 17840
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Install-Core.ps1')
Invoke-LanPowerInstall -SourceAgent (Join-Path $PSScriptRoot 'StandaloneAgent.ps1') -ManagementKey $ManagementKey -InstallPath $InstallPath -TaskName $TaskName -Port $Port
