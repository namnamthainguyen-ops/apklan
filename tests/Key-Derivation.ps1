param([Parameter(Mandatory)][string]$RoomKey)
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'client\Install-Core.ps1')
ConvertTo-LanPowerManagementKey -RoomKey $RoomKey
