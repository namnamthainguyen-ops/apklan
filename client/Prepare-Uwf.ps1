[CmdletBinding()]
param([switch]$Restart)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Install-Core.ps1')
Initialize-LanPowerUwfFeature
if ($Restart) { Restart-Computer -Force }
