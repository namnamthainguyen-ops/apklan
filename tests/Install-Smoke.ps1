[CmdletBinding()]
param([switch]$IncludeOnline, [string]$InstallerExe)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($InstallerExe -and $env:GITHUB_ACTIONS -ne 'true') {
    throw 'Packaged EXE installation smoke test is restricted to the disposable GitHub Actions Windows runner.'
}
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
            $stateSignature = Get-TestHmac $Key "state`n$nonce`n$($reply.deviceId)`n$($reply.agentVersion)`n$($reply.uwf)`n$($reply.capabilities)"
            Assert-True ($reply.stateSignature -ceq $stateSignature) 'UWF state and capabilities must be authenticated.'
            return
        }
        catch [Net.Sockets.SocketException] { Start-Sleep -Milliseconds 250 }
        finally { $udp.Dispose() }
    }
    throw 'Installed SYSTEM agent did not answer authenticated loopback discovery.'
}

function Invoke-OfflineTestInstall {
    param([switch]$LegacyManagementKey)
    if (-not $InstallerExe) {
        if ($LegacyManagementKey) { & $offlineInstaller -ManagementKey $key -InstallPath $installPath -TaskName $taskName -Port $port }
        else { & $offlineInstaller -RoomKey $roomKey -InstallPath $installPath -TaskName $taskName -Port $port }
        return
    }
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $packagedExe
    $info.Arguments = ('--ci-install --install-path "{0}" --task-name "{1}" --port {2}' -f $installPath, $taskName, $port)
    $info.WorkingDirectory = $packagedRoot
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw 'Unable to start packaged installer.' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.WriteLine($roomKey)
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(120000)) { $process.Kill(); throw 'Packaged installer timed out.' }
        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result
        if (($stdout + $stderr).Contains($roomKey)) { throw 'Packaged installer exposed the room key in output.' }
        if ($process.ExitCode -ne 0) { throw "Packaged installer failed: $stdout $stderr" }
        Write-Output 'PACKAGED_EXE_INSTALL=PASS (stdin key, isolated single EXE, no key in output)'
    }
    finally { $process.Dispose() }
}

$testId = [Guid]::NewGuid().ToString('N')
$installPath = Join-Path $env:TEMP "LanPowerSmoke-$testId"
$taskName = "LanPowerSmoke-$testId"
$portProbe = [Net.Sockets.UdpClient]::new(0)
$port = ([Net.IPEndPoint]$portProbe.Client.LocalEndPoint).Port
$portProbe.Dispose()
$roomKey = 'RoomA123'
$key = ConvertTo-LanPowerManagementKey -RoomKey $roomKey
$ruleName = Get-LanPowerFirewallRuleName $taskName $port
$offlineInstaller = Join-Path $repoRoot 'client\Install-Agent.ps1'
$uninstaller = Join-Path $repoRoot 'client\Uninstall-Agent.ps1'
$packagedRoot = $null
$packagedExe = $null
try {
    if ($InstallerExe) {
        $sourceExe = (Resolve-Path -LiteralPath $InstallerExe).Path
        if (-not (Test-Path -LiteralPath $sourceExe -PathType Leaf) -or [IO.Path]::GetExtension($sourceExe) -ine '.exe') {
            throw 'InstallerExe must point to the packaged client installer EXE.'
        }
        $packagedRoot = Join-Path $env:TEMP "LanPowerPackaged-$testId"
        New-Item -ItemType Directory -Path $packagedRoot | Out-Null
        $packagedExe = Join-Path $packagedRoot 'RoomTN20-ClientSetup.exe'
        Copy-Item -LiteralPath $sourceExe -Destination $packagedExe
    }
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
    Invoke-OfflineTestInstall
    $config = Get-Content -LiteralPath (Join-Path $installPath 'config.json') -Raw | ConvertFrom-Json
    Assert-True ($config.managementKey -ceq $key) 'Installer stores the derived room key.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $installPath 'config.json') -Raw) -notmatch $roomKey) 'Plain room key is not persisted.'
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
    Invoke-OfflineTestInstall -LegacyManagementKey
    $config = Get-Content -LiteralPath (Join-Path $installPath 'config.json') -Raw | ConvertFrom-Json
    Assert-True ($config.deviceId -eq $deviceId) 'Reinstall preserves the saved Android device ID.'
    Test-AgentDiscovery $port $key $deviceId
    if ($IncludeOnline) {
        & (Join-Path $repoRoot 'install-online.ps1') -RoomKey $roomKey -InstallPath $installPath -TaskName $taskName -Port $port
        $config = Get-Content -LiteralPath (Join-Path $installPath 'config.json') -Raw | ConvertFrom-Json
        Assert-True ($config.deviceId -eq $deviceId) 'Online reinstall preserves device ID.'
        Assert-SecureAcl $installPath
        Test-AgentDiscovery $port $key $deviceId
    }
    & $uninstaller -InstallPath $installPath -TaskName $taskName -Confirm:$false
    Assert-True (-not (Test-Path -LiteralPath $installPath)) 'Uninstall removes the dedicated installation directory.'
    Assert-True (-not (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue)) 'Uninstall removes the task.'
    Assert-True (-not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) 'Uninstall removes the firewall rule.'
    $mode = if ($InstallerExe) { 'single EXE + stdin room key' } else { 'PowerShell + legacy reinstall' }
    Write-Output "INSTALLATION_SMOKE_TEST=PASS ($mode, ACL, SYSTEM task, firewall, authenticated state, stable reinstall, uninstall)"
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
    if ($packagedRoot -and (Test-Path -LiteralPath $packagedRoot)) {
        if ([IO.Path]::GetFullPath($packagedRoot) -ne (Join-Path $env:TEMP "LanPowerPackaged-$testId")) { throw 'Unexpected packaged EXE cleanup path.' }
        if (Test-Path -LiteralPath $packagedExe) { Remove-Item -LiteralPath $packagedExe -Force }
        Remove-Item -LiteralPath $packagedRoot -Force
    }
}
