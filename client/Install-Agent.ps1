[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ManagementKey,
    [string]$InstallPath = "$env:ProgramData\LanPowerStandalone",
    [string]$TaskName = 'LanPowerStandaloneAgent'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run PowerShell as Administrator.'
}

try { $keyBytes = [Convert]::FromBase64String($ManagementKey) }
catch { throw 'Management Key is not valid Base64.' }
if ($keyBytes.Length -ne 32) { throw 'Management Key must contain exactly 32 bytes.' }

$sourceAgent = Join-Path $PSScriptRoot 'StandaloneAgent.ps1'
if (-not (Test-Path -LiteralPath $sourceAgent)) { throw 'StandaloneAgent.ps1 was not found.' }
$existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existingTask) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}

New-Item -ItemType Directory -Path $InstallPath -Force | Out-Null
Copy-Item -LiteralPath $sourceAgent -Destination (Join-Path $InstallPath 'StandaloneAgent.ps1') -Force
$config = [ordered]@{
    deviceId = [Guid]::NewGuid().ToString()
    managementKey = $ManagementKey
    port = 17840
    installedAt = [DateTime]::UtcNow.ToString('o')
}
$config | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $InstallPath 'config.json') -Encoding UTF8
& icacls.exe $InstallPath /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Unable to secure the agent directory ACL.' }

$agentPath = Join-Path $InstallPath 'StandaloneAgent.ps1'
$arguments = ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}"' -f $agentPath)
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 1)
$taskPrincipal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $taskPrincipal -Force | Out-Null

$ruleName = 'LanPower Standalone Agent UDP 17840'
if (-not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol UDP -LocalPort 17840 -RemoteAddress LocalSubnet -Action Allow | Out-Null
}
Start-ScheduledTask -TaskName $TaskName
Write-Host 'Standalone agent installed. UDP 17840 is open to LocalSubnet.' -ForegroundColor Green
