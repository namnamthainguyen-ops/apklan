[CmdletBinding()]
param([switch]$IncludeOnline)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $repoRoot 'client\Install-Core.ps1')
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Installation smoke test requires an elevated Windows PowerShell session.'
}
function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Assertion failed: $Message" }
}
function Assert-SecureAcl {
    param([string]$Path)
    $acl = Get-Acl -LiteralPath $Path
    $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
    Assert-True ($owner -in @('S-1-5-18', 'S-1-5-32-544')) 'Directory owner must be SYSTEM or Administrators.'
    foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -eq 'Allow') {
            Assert-True ($rule.IdentityReference.Value -in @('S-1-5-18', 'S-1-5-32-544')) "Unexpected file permission on $Path."
        }
    }
}
function Get-TestHmac {
    param([string]$Key, [string]$Value)
    $hmac = [Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($Key))
    try { return [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))) }
    finally { $hmac.Dispose() }
}
function Test-AgentDiscovery {
    param([int]$Port, [string]$Key, [string]$ExpectedDeviceId)
    $nonce = [Guid]::NewGuid().ToString('N')
    $message = @{type = 'discover'; nonce = $nonce; signature = Get-TestHmac $Key "discover`n$nonce"} | ConvertTo-Json -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($message)
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $udp = [Net.Sockets.UdpClient]::new()
        $udp.Client.ReceiveTimeout = 500
        try {
            [void]$udp.Send($bytes, $bytes.Length, '127.0.0.1', $Port)
            $remote = [Net.IPEndPoint]::new([Net.IPAddress]::Any, 0)
            $reply = [Text.Encoding]::UTF8.GetString($udp.Receive([ref]$remote)) | ConvertFrom-Json
            Assert-True ($reply.deviceId -eq $ExpectedDeviceId) 'Discovery returns the installation device ID.'
            $expected = Get-TestHmac $Key "discovery`n$nonce`n$($reply.deviceId)`n$($reply.hostname)`n$($reply.mac)"
            Assert-True ($reply.signature -ceq $expected) 'Discovery signature must match.'
            return
        }
        catch [Net.Sockets.SocketException] { Start-Sleep -Milliseconds 250 }
        finally { $udp.Dispose() }
    }
    throw 'Installed SYSTEM agent did not answer authenticated loopback discovery.'
}

$testId = [Guid]::NewGuid().ToString('N')
$installPath = Join-Path $env:TEMP "LanPowerSmoke-$testId"
$taskName = "LanPowerSmoke-$testId"
$portProbe = [Net.Sockets.UdpClient]::new(0)
$port = ([Net.IPEndPoint]$portProbe.Client.LocalEndPoint).Port
$portProbe.Dispose()
$keyBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($keyBytes)
$rng.Dispose()
$key = [Convert]::ToBase64String($keyBytes)
$ruleName = Get-LanPowerFirewallRuleName $taskName $port
$offlineInstaller = Join-Path $repoRoot 'client\Install-Agent.ps1'
$uninstaller = Join-Path $repoRoot 'client\Uninstall-Agent.ps1'
try {
    foreach ($blocked in @([IO.Path]::GetPathRoot($installPath), $env:ProgramData, $env:WINDIR)) {
        $failed = $false
        try { Get-LanPowerInstallPath $blocked | Out-Null } catch { $failed = $true }
        Assert-True $failed "Broad path must be rejected: $blocked"
    }
    # Deliberately pre-create a permissive directory: installation must replace
    # explicit user ACEs and ownership before writing the secret or SYSTEM script.
    New-Item -ItemType Directory -Path $installPath | Out-Null
    $permissiveAcl = Get-Acl -LiteralPath $installPath
    [void]$permissiveAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        [Security.Principal.SecurityIdentifier]::new('S-1-5-32-545'), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    Set-Acl -LiteralPath $installPath -AclObject $permissiveAcl
    & $offlineInstaller -ManagementKey $key -InstallPath $installPath -TaskName $taskName -Port $port
    $config = Get-Content -LiteralPath (Join-Path $installPath 'config.json') -Raw | ConvertFrom-Json
    $deviceId = $config.deviceId
    $task = Get-ScheduledTask -TaskName $taskName
    Assert-True ($task.Principal.UserId -in @('SYSTEM', 'S-1-5-18')) 'Agent task must run as SYSTEM.'
    Assert-True ($task.Settings.ExecutionTimeLimit -eq 'PT0S') 'Agent task must have no execution time limit.'
    Assert-True ($task.Actions[0].Execute -like '*WindowsPowerShell*v1.0*powershell.exe') 'Task uses an absolute Windows PowerShell path.'
    $rule = Get-NetFirewallRule -DisplayName $ruleName
    Assert-True ($rule.Action -eq 'Allow' -and $rule.Direction -eq 'Inbound') 'Agent firewall rule is inbound allow.'
    $address = $rule | Get-NetFirewallAddressFilter
    Assert-True ('LocalSubnet' -in $address.RemoteAddress) 'Firewall access is limited to LocalSubnet.'
    Assert-SecureAcl $installPath
    Assert-SecureAcl (Join-Path $installPath 'config.json')
    Assert-SecureAcl (Join-Path $installPath 'StandaloneAgent.ps1')
    Test-AgentDiscovery $port $key $deviceId
    & $offlineInstaller -ManagementKey $key -InstallPath $installPath -TaskName $taskName -Port $port
    $config = Get-Content -LiteralPath (Join-Path $installPath 'config.json') -Raw | ConvertFrom-Json
    Assert-True ($config.deviceId -eq $deviceId) 'Reinstall preserves the saved Android device ID.'
    Test-AgentDiscovery $port $key $deviceId
    if ($IncludeOnline) {
        & (Join-Path $repoRoot 'install-online.ps1') -ManagementKey $key -InstallPath $installPath -TaskName $taskName -Port $port
        $config = Get-Content -LiteralPath (Join-Path $installPath 'config.json') -Raw | ConvertFrom-Json
        Assert-True ($config.deviceId -eq $deviceId) 'Online reinstall preserves device ID.'
        Assert-SecureAcl $installPath
        Test-AgentDiscovery $port $key $deviceId
    }
    & $uninstaller -InstallPath $installPath -TaskName $taskName -Confirm:$false
    Assert-True (-not (Test-Path -LiteralPath $installPath)) 'Uninstall removes the dedicated installation directory.'
    Assert-True (-not (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue)) 'Uninstall removes the task.'
    Assert-True (-not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) 'Uninstall removes the firewall rule.'
    Write-Output 'INSTALLATION_SMOKE_TEST=PASS (ACL, SYSTEM task, firewall, discovery, stable reinstall, uninstall)'
}
finally {
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    if (Test-Path -LiteralPath $installPath) {
        $resolved = [IO.Path]::GetFullPath($installPath)
        if ($resolved -ne (Join-Path $env:TEMP "LanPowerSmoke-$testId")) { throw 'Unexpected cleanup path.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
