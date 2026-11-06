Set-StrictMode -Version Latest

function ConvertTo-LanPowerManagementKey {
    param([Parameter(Mandatory)][string]$RoomKey)
    if ($RoomKey -cnotmatch '\A[\x21-\x7E]{8}\z') {
        throw 'Room Key must contain exactly 8 printable ASCII characters without spaces (case-sensitive).'
    }
    $salt = [Text.Encoding]::UTF8.GetBytes('LanPower/RoomKey/v1')
    $password = [Text.Encoding]::UTF8.GetBytes($RoomKey)
    $derive = [Security.Cryptography.Rfc2898DeriveBytes]::new($password, $salt, 210000, [Security.Cryptography.HashAlgorithmName]::SHA256)
    try { return [Convert]::ToBase64String($derive.GetBytes(32)) }
    finally { $derive.Dispose(); [Array]::Clear($password, 0, $password.Length) }
}

function Assert-LanPowerWritableSystem {
    try {
        $filter = Get-CimInstance -Namespace 'root\standardcimv2\embedded' -ClassName UWF_Filter -ErrorAction Stop
    }
    catch { return } # UWF is optional; its provider is absent on ordinary Windows.
    $active = $false
    if ($filter.CurrentEnabled) {
        try {
            $volumes = @(Get-CimInstance -Namespace 'root\standardcimv2\embedded' -ClassName UWF_Volume -ErrorAction Stop)
            if (@($volumes | Where-Object { $_.CurrentSession -and $_.DriveLetter -in @('C:', 'C') }).Count -eq 0) {
                throw 'C: volume state is missing.'
            }
            $active = @($volumes | Where-Object { $_.CurrentSession -and $_.Protected -and $_.DriveLetter -in @('C:', 'C') }).Count -gt 0
        }
        catch {
            throw 'UWF_STATE_UNAVAILABLE: UWF is active but C: protection could not be checked. Check locally before installing the agent.'
        }
    }
    if ($active) {
        throw 'UWF_ACTIVE_DISABLE_AND_RESTART: Disable UWF and restart before installing or changing the agent, otherwise changes may disappear after reboot.'
    }
}

function Initialize-LanPowerUwfFeature {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run PowerShell as Administrator.' }
    Assert-LanPowerWritableSystem
    $feature = Get-WindowsOptionalFeature -Online -FeatureName 'Client-UnifiedWriteFilter' -ErrorAction Stop
    if ($feature.State -eq 'Enabled') {
        Write-Host 'UWF feature is already installed. C: protection has not been changed.'
        return
    }
    if ($feature.State -eq 'EnablePending') {
        Write-Host 'UWF_RESTART_REQUIRED: Restart manually to finish installing the UWF feature.'
        return
    }
    $dism = Join-Path $env:WINDIR 'System32\dism.exe'
    $process = Start-Process -FilePath $dism -ArgumentList '/online','/enable-feature','/featureName:Client-DeviceLockdown','/featureName:Client-UnifiedWriteFilter','/All','/NoRestart' -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010)) { throw "UWF_PREPARE_FAILED: DISM exit code $($process.ExitCode). Check whether this Windows edition supports UWF." }
    Write-Host 'UWF feature prepared. Restart manually before using remote Freeze. C: protection has not been changed.'
}

function Invoke-LanPowerUwfPreparation {
    param([switch]$SkipUwfPreparation)
    if ($SkipUwfPreparation) { return }
    try { Initialize-LanPowerUwfFeature }
    catch { Write-Warning ('The power agent was installed, but UWF preparation could not finish: ' + $_.Exception.Message) }
}

function Get-LanPowerInstallPath {
    param([Parameter(Mandatory)][string]$Path)
    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $blocked = @([IO.Path]::GetPathRoot($fullPath), $env:ProgramData, $env:WINDIR,
        $env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:USERPROFILE, $env:TEMP)
    foreach ($entry in $blocked) {
        if ($entry -and $fullPath -ieq $entry.TrimEnd('\')) {
            throw 'InstallPath must be a dedicated agent directory, not a system or profile root.'
        }
    }
    $ancestor = $fullPath
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -LiteralPath $ancestor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'InstallPath and its ancestors must not be reparse points.'
            }
        }
        $parent = [IO.Path]::GetDirectoryName($ancestor)
        if ($parent -eq $ancestor) { break }
        $ancestor = $parent
    }
    if (Test-Path -LiteralPath $fullPath) {
        if (-not (Get-Item -LiteralPath $fullPath -Force).PSIsContainer) {
            throw 'InstallPath must be a directory.'
        }
        foreach ($item in @(Get-ChildItem -LiteralPath $fullPath -Force)) {
            if ($item.PSIsContainer -or $item.Name -notin @('StandaloneAgent.ps1', 'Install-Core.ps1', 'Update-Agent.ps1', 'Update-PublicKey.xml', 'config.json', 'update-status.json') -or
                ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw 'InstallPath contains unrelated files or reparse points; choose a dedicated directory.'
            }
        }
    }
    return $fullPath
}

function Set-LanPowerDirectorySecurity {
    param([Parameter(Mandatory)][string]$Path)
    $administrators = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($administrators)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @($administrators, $system)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl',
            'ContainerInherit,ObjectInherit', 'None', 'Allow')
        [void]$acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    foreach ($item in @(Get-ChildItem -LiteralPath $Path -Force)) {
        $fileAcl = [Security.AccessControl.FileSecurity]::new()
        $fileAcl.SetOwner($administrators)
        $fileAcl.SetAccessRuleProtection($true, $false)
        foreach ($sid in @($administrators, $system)) {
            [void]$fileAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow'))
        }
        Set-Acl -LiteralPath $item.FullName -AclObject $fileAcl
    }
}

function Get-LanPowerFirewallRuleName {
    param([string]$TaskName, [int]$Port)
    if ($TaskName -eq 'LanPowerStandaloneAgent' -and $Port -eq 17840) {
        return 'LanPower Standalone Agent UDP 17840'
    }
    return "LanPower Standalone Agent $TaskName UDP $Port"
}

function ConvertTo-LanPowerVpnNetworks {
    param([string[]]$Networks)
    $result = @()
    foreach ($value in @($Networks)) {
        foreach ($entry in ([string]$value -split '[,;\s]+')) {
            if (-not $entry) { continue }
            $parts = $entry.Split('/')
            $address = $null
            if ($parts.Count -gt 2 -or -not [Net.IPAddress]::TryParse($parts[0], [ref]$address) -or
                $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw "Invalid VPN IPv4 network: $entry" }
            $prefix = 32
            if ($parts.Count -eq 2 -and (-not [int]::TryParse($parts[1], [ref]$prefix) -or $prefix -lt 0 -or $prefix -gt 32)) { throw "Invalid VPN prefix: $entry" }
            $bytes = $address.GetAddressBytes()
            $minimumPrefix = if ($bytes[0] -eq 10) { 8 }
                elseif ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) { 12 }
                elseif ($bytes[0] -eq 192 -and $bytes[1] -eq 168) { 16 }
                elseif ($bytes[0] -eq 100 -and $bytes[1] -ge 64 -and $bytes[1] -le 127) { 10 }
                else { throw 'VPN ranges must be private IPv4 (RFC1918) or CGNAT 100.64.0.0/10.' }
            if ($prefix -lt $minimumPrefix) { throw "VPN range is too broad: $entry" }
            for ($index = 0; $index -lt 4; $index++) {
                $bits = [Math]::Min(8, [Math]::Max(0, $prefix - 8 * $index))
                $mask = if ($bits -eq 0) { 0 } else { (255 -shl (8 - $bits)) -band 255 }
                $bytes[$index] = $bytes[$index] -band $mask
            }
            $normalized = ([Net.IPAddress]::new($bytes)).ToString() + '/' + $prefix
            if ($normalized -notin $result) { $result += $normalized }
        }
    }
    if ($result.Count -gt 32) { throw 'At most 32 VPN ranges can be configured.' }
    return $result
}

function Set-LanPowerAtomicFile {
    param([string]$Path, [byte[]]$Bytes)
    $temporary = $Path + '.new-' + [Guid]::NewGuid().ToString('N')
    try {
        [IO.File]::WriteAllBytes($temporary, $Bytes)
        # Windows PowerShell 5.1 converts $null to an empty string for this
        # .NET string argument. NullString supplies the actual null backup path.
        if (Test-Path -LiteralPath $Path) { [IO.File]::Replace($temporary, $Path, [NullString]::Value) }
        else { [IO.File]::Move($temporary, $Path) }
    }
    finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

function Enter-LanPowerInstallLock {
    param([string]$InstallPath)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $name = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes([IO.Path]::GetFullPath($InstallPath).ToUpperInvariant()))).Replace('-', '') }
    finally { $sha.Dispose() }
    $mutex = [Threading.Mutex]::new($false, 'Global\RoomTN20Install-' + $name)
    try {
        try { $locked = $mutex.WaitOne(30000) } catch [Threading.AbandonedMutexException] { $locked = $true }
        if (-not $locked) { throw 'Another installer or updater is working on this client. Try again shortly.' }
        return $mutex
    }
    catch { $mutex.Dispose(); throw }
}

function Invoke-LanPowerInstall {
    [CmdletBinding(DefaultParameterSetName = 'RoomKey')]
    param(
        [Parameter(Mandatory)][string]$SourceAgent,
        [Parameter(Mandatory, ParameterSetName = 'RoomKey')][string]$RoomKey,
        [Parameter(Mandatory, ParameterSetName = 'ManagementKey')][string]$ManagementKey,
        [Parameter(Mandatory)][string]$InstallPath,
        [ValidatePattern('^[A-Za-z0-9_.-]{1,100}$')][string]$TaskName = 'LanPowerStandaloneAgent',
        [ValidateRange(1024, 65535)][int]$Port = 17840,
        [switch]$PrepareUwf,
        [switch]$SkipUwfPreparation,
        [string[]]$VpnNetworks,
        [string]$SourceCore,
        [string]$SourceUpdater,
        [string]$SourcePublicKey
    )
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run PowerShell as Administrator.'
    }
    if ($PSCmdlet.ParameterSetName -eq 'RoomKey') {
        $ManagementKey = ConvertTo-LanPowerManagementKey $RoomKey
    }
    try { $keyBytes = [Convert]::FromBase64String($ManagementKey) }
    catch { throw 'Management Key is not valid Base64.' }
    if ($keyBytes.Length -ne 32) { throw 'Management Key must contain exactly 32 bytes.' }
    if (-not (Test-Path -LiteralPath $SourceAgent -PathType Leaf)) { throw 'StandaloneAgent.ps1 was not found.' }
    $payloadRoot = Split-Path -Parent $SourceAgent
    if (-not $SourceCore) { $SourceCore = Join-Path $payloadRoot 'Install-Core.ps1' }
    if (-not $SourceUpdater) { $SourceUpdater = Join-Path $payloadRoot 'Update-Agent.ps1' }
    if (-not $SourcePublicKey) { $SourcePublicKey = Join-Path $payloadRoot 'Update-PublicKey.xml' }
    foreach ($payload in @($SourceCore, $SourceUpdater, $SourcePublicKey)) {
        if (-not (Test-Path -LiteralPath $payload -PathType Leaf)) { throw "Missing update payload: $([IO.Path]::GetFileName($payload))" }
    }
    $InstallPath = Get-LanPowerInstallPath $InstallPath
    $installMutex = Enter-LanPowerInstallLock $InstallPath
    try {
    $configPath = Join-Path $InstallPath 'config.json'
    $deviceId = [Guid]::NewGuid().ToString()
    $previous = $null
    if (Test-Path -LiteralPath $configPath) {
        $previous = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        $parsedId = [Guid]::Empty
        if (-not [Guid]::TryParse([string]$previous.deviceId, [ref]$parsedId)) {
            throw 'Existing config.json has an invalid deviceId; preserve it and investigate before reinstalling.'
        }
        $deviceId = $parsedId.ToString()
    }
    if (-not $PSBoundParameters.ContainsKey('VpnNetworks') -and $previous -and $previous.PSObject.Properties['vpnNetworks']) {
        $VpnNetworks = @($previous.vpnNetworks)
    }
    $VpnNetworks = @(ConvertTo-LanPowerVpnNetworks $VpnNetworks)
    Assert-LanPowerWritableSystem
    New-Item -ItemType Directory -Path $InstallPath -Force | Out-Null
    Set-LanPowerDirectorySecurity $InstallPath
    $existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($existingTask) {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction Stop
        for ($attempt = 0; $attempt -lt 50; $attempt++) {
            if ((Get-ScheduledTask -TaskName $TaskName).State -ne 'Running') { break }
            Start-Sleep -Milliseconds 100
        }
        if ((Get-ScheduledTask -TaskName $TaskName).State -eq 'Running') { throw 'Existing agent did not stop.' }
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    }
    $updateTaskName = $TaskName + 'Updater'
    foreach ($entry in @(@($SourceAgent, 'StandaloneAgent.ps1'), @($SourceCore, 'Install-Core.ps1'), @($SourceUpdater, 'Update-Agent.ps1'), @($SourcePublicKey, 'Update-PublicKey.xml'))) {
        $destination = Join-Path $InstallPath $entry[1]
        if ([IO.Path]::GetFullPath($entry[0]) -ine [IO.Path]::GetFullPath($destination)) { Set-LanPowerAtomicFile $destination ([IO.File]::ReadAllBytes($entry[0])) }
    }
    $newConfig = [ordered]@{
        deviceId = $deviceId
        managementKey = $ManagementKey
        port = $Port
        vpnNetworks = @($VpnNetworks)
        agentTaskName = $TaskName
        updateTaskName = $updateTaskName
        version = '1.2.2'
        versionCode = 7
        installedAt = [DateTime]::UtcNow.ToString('o')
    } | ConvertTo-Json
    Set-LanPowerAtomicFile $configPath ([Text.Encoding]::ASCII.GetBytes($newConfig))
    Set-LanPowerDirectorySecurity $InstallPath
    $agentPath = Join-Path $InstallPath 'StandaloneAgent.ps1'
    $arguments = ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}"' -f $agentPath)
    $powershellPath = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $action = New-ScheduledTaskAction -Execute $powershellPath -Argument $arguments
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 1)
    $taskPrincipal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $taskPrincipal -Force | Out-Null
    $ruleName = Get-LanPowerFirewallRuleName $TaskName $Port
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    $remoteAddresses = @('LocalSubnet') + @($VpnNetworks)
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol UDP -LocalPort $Port -RemoteAddress $remoteAddresses -Action Allow -Program $powershellPath | Out-Null
    $updateAction = New-ScheduledTaskAction -Execute $powershellPath -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}"' -f (Join-Path $InstallPath 'Update-Agent.ps1'))
    $updateStartup = New-ScheduledTaskTrigger -AtStartup -RandomDelay (New-TimeSpan -Minutes 10)
    $updatePeriodic = New-ScheduledTaskTrigger -Once -At ([DateTime]::Now.AddMinutes(15)) -RepetitionInterval (New-TimeSpan -Hours 6)
    $updateSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Minutes 20) -MultipleInstances IgnoreNew -StartWhenAvailable
    Register-ScheduledTask -TaskName $updateTaskName -Action $updateAction -Trigger @($updateStartup, $updatePeriodic) -Settings $updateSettings -Principal $taskPrincipal -Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName
    Invoke-LanPowerUwfPreparation -SkipUwfPreparation:$SkipUwfPreparation
    Write-Host "Standalone agent installed. UDP $Port is open to LocalSubnet." -ForegroundColor Green
    }
    finally { $installMutex.ReleaseMutex(); $installMutex.Dispose() }
}
