[CmdletBinding()]
param(
    [ValidateRange(512, 8192)]
    [int]$OverlaySizeMB = 1024,
    [switch]$Restart
)

$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run PowerShell as Administrator.' }
if (-not (Get-ScheduledTask -TaskName 'LanPowerStandaloneAgent' -ErrorAction SilentlyContinue)) {
    throw 'LanPowerStandaloneAgent is not installed. Install and test it before protecting C:.'
}

$feature = Get-WindowsOptionalFeature -Online -FeatureName 'Client-UnifiedWriteFilter' -ErrorAction SilentlyContinue
if (-not $feature -or $feature.State -ne 'Enabled') {
    $process = Start-Process -FilePath dism.exe -ArgumentList '/online','/enable-feature','/featureName:Client-DeviceLockdown','/featureName:Client-UnifiedWriteFilter','/All','/NoRestart' -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010)) { throw "DISM failed with exit code $($process.ExitCode)." }
    Write-Host 'UWF installed. Restart, then run this script again to protect C:.' -ForegroundColor Green
    if ($Restart) { Restart-Computer -Force }
    exit 0
}

$uwfmgr = Join-Path $env:WINDIR 'System32\uwfmgr.exe'
& $uwfmgr volume protect C:
if ($LASTEXITCODE -ne 0) { throw 'Unable to protect C:.' }
& $uwfmgr overlay set-size $OverlaySizeMB
if ($LASTEXITCODE -ne 0) { throw 'Unable to configure the UWF overlay.' }
& $uwfmgr filter enable
if ($LASTEXITCODE -ne 0) { throw 'Unable to enable UWF.' }
Write-Host 'UWF will protect C: after the next restart.' -ForegroundColor Green
if ($Restart) { & $uwfmgr filter restart }
