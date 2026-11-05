[CmdletBinding()]
param([switch]$Restart)

$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run PowerShell as Administrator.' }
$uwfmgr = Join-Path $env:WINDIR 'System32\uwfmgr.exe'
if (-not (Test-Path -LiteralPath $uwfmgr)) { throw 'uwfmgr.exe was not found.' }
& $uwfmgr filter disable
if ($LASTEXITCODE -ne 0) { throw 'Unable to disable UWF.' }
Write-Host 'UWF will be disabled after the next restart.' -ForegroundColor Yellow
if ($Restart) { & $uwfmgr filter restart }
