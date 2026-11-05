[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [string]$InstallPath = "$env:ProgramData\LanPowerStandalone",
    [ValidatePattern('^[A-Za-z0-9_.-]{1,100}$')][string]$TaskName = 'LanPowerStandaloneAgent'
)

$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run PowerShell as Administrator.'
}
. (Join-Path $PSScriptRoot 'Install-Core.ps1')
$InstallPath = Get-LanPowerInstallPath $InstallPath
if (-not (Test-Path -LiteralPath $InstallPath)) { return }
$configPath = Join-Path $InstallPath 'config.json'
$agentPath = Join-Path $InstallPath 'StandaloneAgent.ps1'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $agentPath -PathType Leaf)) {
    throw 'InstallPath is not a recognized standalone agent installation.'
}
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$parsedId = [Guid]::Empty
if (-not [Guid]::TryParse([string]$config.deviceId, [ref]$parsedId) -or
    [int]$config.port -lt 1024 -or [int]$config.port -gt 65535) {
    throw 'Invalid installation configuration; refusing to remove directory.'
}

if ($PSCmdlet.ShouldProcess($InstallPath, 'Remove standalone agent and firewall rule')) {
    Assert-LanPowerWritableSystem
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    $ruleName = Get-LanPowerFirewallRuleName $TaskName ([int]$config.port)
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    if (Test-Path -LiteralPath $InstallPath) { Remove-Item -LiteralPath $InstallPath -Recurse -Force }
    Write-Host 'LanPower Standalone Agent removed.' -ForegroundColor Green
}
