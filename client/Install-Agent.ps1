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
. (Join-Path $PSScriptRoot 'Install-Core.ps1')
$installParameters = @{SourceAgent = (Join-Path $PSScriptRoot 'StandaloneAgent.ps1'); InstallPath = $InstallPath; TaskName = $TaskName; Port = $Port; PrepareUwf = $PrepareUwf; SkipUwfPreparation = $SkipUwfPreparation; SkipMediaSetup = $SkipMediaSetup}
if ($PSCmdlet.ParameterSetName -eq 'RoomKey') { $installParameters.RoomKey = $RoomKey }
else { $installParameters.ManagementKey = $ManagementKey }
if ($PSBoundParameters.ContainsKey('VpnNetworks')) { $installParameters.VpnNetworks = $VpnNetworks }
Invoke-LanPowerInstall @installParameters
