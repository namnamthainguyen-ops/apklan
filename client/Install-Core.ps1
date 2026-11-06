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
    catch {
        $nativeRoot = Get-LanPowerNativeSystemDirectory
        if (Test-Path -LiteralPath (Join-Path $nativeRoot 'uwfmgr.exe')) {
            throw 'UWF_STATE_UNAVAILABLE: UWF tools exist but its protection state cannot be read. Restart Windows or check locally before reinstalling.'
        }
        return # UWF is optional and its tools/provider are absent on ordinary Windows.
    }
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

function Test-LanPowerAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    try { return ([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator) }
    finally { $identity.Dispose() }
}

function Get-LanPowerNativeSystemDirectory {
    $windows = [Environment]::GetFolderPath([Environment+SpecialFolder]::Windows)
    if ([Environment]::Is64BitOperatingSystem -and -not [Environment]::Is64BitProcess) { return Join-Path $windows 'Sysnative' }
    return Join-Path $windows 'System32'
}

function Get-LanPowerWindowsEdition {
    $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, [Microsoft.Win32.RegistryView]::Registry64)
    try {
        $key = $base.OpenSubKey('SOFTWARE\Microsoft\Windows NT\CurrentVersion')
        if (-not $key) { return 'unknown' }
        try { return [string]$key.GetValue('EditionID', 'unknown') }
        finally { $key.Dispose() }
    }
    catch { return 'unknown' }
    finally { $base.Dispose() }
}

function Invoke-LanPowerDism {
    param([string[]]$Arguments)
    $executable = Join-Path (Get-LanPowerNativeSystemDirectory) 'dism.exe'
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw "UWF_DISM_MISSING: $executable" }
    $quoted = foreach ($argument in $Arguments) {
        if ($argument.Contains('"') -or $argument.Contains("`r") -or $argument.Contains("`n")) { throw 'Invalid DISM argument.' }
        '"' + $argument + '"'
    }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $executable
    $start.Arguments = $quoted -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'UWF_DISM_START_FAILED' }
        # Drain both streams concurrently; hosted PowerShell has no console host
        # and sequential reads can deadlock a verbose servicing process.
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        return [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult() }
    }
    finally { $process.Dispose() }
}

function Get-LanPowerUwfFeatureInfo {
    param([string]$LogPath)
    $arguments = @('/English', '/Online', '/Get-FeatureInfo', '/FeatureName:Client-UnifiedWriteFilter')
    if ($LogPath) { $arguments += '/LogPath:' + $LogPath }
    $result = Invoke-LanPowerDism -Arguments $arguments
    $state = 'Unknown'
    if ($result.ExitCode -eq 0 -and $result.Output -match '(?im)^\s*State\s*:\s*([^\r\n]+)') {
        $state = switch ($Matches[1].Trim()) {
            'Enabled' { 'Enabled' }
            'Enable Pending' { 'EnablePending' }
            'Disable Pending' { 'DisablePending' }
            'Disabled' { 'Disabled' }
            'Disabled with Payload Removed' { 'DisabledWithPayloadRemoved' }
            default { 'Unknown' }
        }
    }
    return [pscustomobject]@{ State = $state; ExitCode = $result.ExitCode; Output = $result.Output }
}

function Test-LanPowerUwfRuntime {
    if (-not (Test-Path -LiteralPath (Join-Path (Get-LanPowerNativeSystemDirectory) 'uwfmgr.exe') -PathType Leaf)) { return $false }
    try {
        $filter = Get-CimInstance -Namespace 'root\standardcimv2\embedded' -ClassName UWF_Filter -OperationTimeoutSec 5 -ErrorAction Stop
        return $null -ne $filter
    }
    catch { return $false }
}

function Get-LanPowerDismFailure {
    param([int]$ExitCode, [string]$Edition, [string]$LogPath)
    $hex = '0x' + ([BitConverter]::ToUInt32([BitConverter]::GetBytes([int32]$ExitCode), 0)).ToString('X8')
    $hint = switch ($hex) {
        '0x800F080C' { 'The Windows image does not contain the UWF feature. UWF requires Enterprise, Education or IoT Enterprise.' }
        '0x800F081F' { 'Windows component source files are missing. Supply a repair source matching this Windows build or allow Windows Update.' }
        '0x800F0906' { 'Windows could not download the feature source. Check Internet access and Windows Update policy.' }
        '0x800F0954' { 'Windows servicing/WSUS policy prevented downloading the feature source. Check the optional-component repair policy.' }
        '0x00000BC2' { 'A restart is required to finish Windows servicing.' }
        default { 'Windows servicing did not complete. Check the DISM log for the underlying component error.' }
    }
    return "DISM exit $ExitCode ($hex). Edition: $Edition. $hint Log: $LogPath"
}

function Initialize-LanPowerUwfFeature {
    param([string]$InstallPath = "$env:ProgramData\LanPowerStandalone")
    if (-not (Test-LanPowerAdministrator)) { throw 'Run PowerShell as Administrator.' }
    Assert-LanPowerWritableSystem
    $edition = Get-LanPowerWindowsEdition
    $windows = [Environment]::GetFolderPath([Environment+SpecialFolder]::Windows)
    $logPath = Join-Path $windows 'Logs\DISM\dism.log'
    # The installed directory already has a SYSTEM/Administrators-only ACL.
    # Reuse it for diagnostics, without creating or changing an arbitrary path.
    if (Test-Path -LiteralPath (Join-Path $InstallPath 'config.json') -PathType Leaf) {
        $safeRoot = Get-LanPowerInstallPath $InstallPath
        $logPath = Join-Path $safeRoot 'uwf-preparation.log'
    }
    if ($edition -match '^(Core|Professional|Server)') {
        throw "UWF_UNSUPPORTED_EDITION: $edition. UWF requires Windows Enterprise, Education or IoT Enterprise. The LAN power agent remains usable. Log: $logPath"
    }
    $feature = Get-LanPowerUwfFeatureInfo -LogPath $logPath
    if ($feature.ExitCode -ne 0) { throw ('UWF_FEATURE_QUERY_FAILED: ' + (Get-LanPowerDismFailure $feature.ExitCode $edition $logPath)) }
    if ($feature.State -eq 'Enabled') {
        $restart = -not (Test-LanPowerUwfRuntime)
        return [pscustomobject]@{ Status = $(if ($restart) { 'restart-required' } else { 'ready' }); RestartRequired = $restart;
            Message = $(if ($restart) { 'UWF is installed but its runtime is not available yet. Restart Windows before using Freeze.' } else { 'UWF feature is already installed. C: protection has not been changed.' });
            Edition = $edition; FeatureState = 'Enabled'; LogPath = $logPath }
    }
    if ($feature.State -in @('EnablePending', 'DisablePending')) {
        return [pscustomobject]@{ Status = 'restart-required'; RestartRequired = $true; Message = 'UWF_RESTART_REQUIRED: Restart Windows manually, then run the installer again if UWF was being disabled. C: protection has not been changed.'; Edition = $edition; FeatureState = $feature.State; LogPath = $logPath }
    }
    if ($feature.State -notin @('Disabled', 'DisabledWithPayloadRemoved')) { throw "UWF_FEATURE_STATE_UNKNOWN: Edition $edition. Log: $logPath" }
    # /All restores the parent dependency without naming an unrelated feature.
    # /NoRestart is mandatory: preparing UWF never reboots or protects C:.
    $result = Invoke-LanPowerDism -Arguments @('/English', '/Online', '/Enable-Feature', '/FeatureName:Client-UnifiedWriteFilter', '/All', '/NoRestart', ('/LogPath:' + $logPath))
    if ($result.ExitCode -notin @(0, 3010)) { throw ('UWF_PREPARE_FAILED: ' + (Get-LanPowerDismFailure $result.ExitCode $edition $logPath)) }
    $verified = Get-LanPowerUwfFeatureInfo -LogPath $logPath
    if ($verified.ExitCode -ne 0) { throw ('UWF_VERIFY_FAILED: ' + (Get-LanPowerDismFailure $verified.ExitCode $edition $logPath)) }
    if ($verified.State -notin @('Enabled', 'EnablePending')) { throw "UWF_VERIFY_FAILED: Feature state is $($verified.State) after preparation. Edition: $edition. Log: $logPath" }
    $restart = $result.ExitCode -eq 3010 -or $verified.State -eq 'EnablePending' -or -not (Test-LanPowerUwfRuntime)
    $message = if ($restart) { 'UWF_RESTART_REQUIRED: UWF was prepared. Restart Windows manually before using Freeze. C: protection has not been changed.' }
        else { 'UWF feature is installed and ready. C: protection has not been changed.' }
    return [pscustomobject]@{ Status = $(if ($restart) { 'restart-required' } else { 'ready' }); RestartRequired = $restart; Message = $message; Edition = $edition; FeatureState = $verified.State; LogPath = $logPath }
}

function Invoke-LanPowerUwfPreparation {
    param([switch]$SkipUwfPreparation, [string]$InstallPath = "$env:ProgramData\LanPowerStandalone")
    if ($SkipUwfPreparation) { return }
    try { $result = Initialize-LanPowerUwfFeature -InstallPath $InstallPath; if ($result) { Write-Host $result.Message } }
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
            if ($item.PSIsContainer -or $item.Name -notin @('StandaloneAgent.ps1', 'Install-Core.ps1', 'Update-Agent.ps1', 'Update-PublicKey.xml', 'config.json', 'update-status.json', 'uwf-preparation.log', 'uwf-preparation.log.bak') -or
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

function Get-LanPowerHandshakeHmac {
    param([string]$Key, [string]$Text)
    $bytes = [Convert]::FromBase64String($Key)
    $hmac = [Security.Cryptography.HMACSHA256]::new($bytes)
    try { return [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))) }
    finally { $hmac.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
}

function Test-LanPowerHandshakeSignature {
    param([string]$Key, [string]$Text, [string]$Signature)
    try {
        $actual = [Convert]::FromBase64String($Signature)
        $expected = [Convert]::FromBase64String((Get-LanPowerHandshakeHmac $Key $Text))
        if ($actual.Length -ne 32 -or $expected.Length -ne 32) { return $false }
        $difference = 0
        for ($index = 0; $index -lt 32; $index++) { $difference = $difference -bor ($actual[$index] -bxor $expected[$index]) }
        return $difference -eq 0
    }
    catch { return $false }
}

function Wait-LanPowerInstalledAgent {
    param([string]$InstallPath, [string]$RoomKey, [int]$Port = 17840,
        [string]$ExpectedVersion = '1.2.3', [int]$ExpectedVersionCode = 8,
        [ValidateRange(1, 60)][int]$TimeoutSeconds = 20, [string]$ManagementKey)
    if ($RoomKey) { $ManagementKey = ConvertTo-LanPowerManagementKey $RoomKey }
    try {
        if ([Convert]::FromBase64String($ManagementKey).Length -ne 32) { throw 'Invalid installed key.' }
        $config = [IO.File]::ReadAllText((Join-Path $InstallPath 'config.json')) | ConvertFrom-Json
        $deviceId = [string]$config.deviceId
        $parsedId = [Guid]::Empty
        if (-not [Guid]::TryParse($deviceId, [ref]$parsedId) -or [string]$config.managementKey -cne $ManagementKey -or
            [int]$config.port -ne $Port -or [string]$config.version -cne $ExpectedVersion -or [int]$config.versionCode -ne $ExpectedVersionCode) {
            throw 'Installed identity, key, port or version differs from the requested installation.'
        }
    }
    catch { throw 'CLIENT_CONFIG_VERIFY_FAILED: Khong xac minh duoc cau hinh client vua cai. Hay chay lai bo cai.' }
    $socket = [Net.Sockets.UdpClient]::new()
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $socket.Connect('127.0.0.1', $Port)
        $socket.Client.ReceiveTimeout = 500
        while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
            $nonce = [Guid]::NewGuid().ToString('N')
            $request = @{ type = 'discover'; nonce = $nonce; signature = (Get-LanPowerHandshakeHmac $ManagementKey "discover`n$nonce") } | ConvertTo-Json -Compress
            $requestBytes = [Text.Encoding]::UTF8.GetBytes($request)
            [void]$socket.Send($requestBytes, $requestBytes.Length)
            try {
                $remote = [Net.IPEndPoint]::new([Net.IPAddress]::Loopback, $Port)
                $bytes = $socket.Receive([ref]$remote)
                if ($bytes.Length -gt 8192) { continue }
                $reply = [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
                if ($reply.type -cne 'discovery' -or $reply.requestNonce -cne $nonce -or $reply.deviceId -cne $deviceId) { continue }
                if (-not (Test-LanPowerHandshakeSignature $ManagementKey "discovery`n$nonce`n$deviceId`n$($reply.hostname)`n$($reply.mac)" ([string]$reply.signature))) { continue }
                if ($reply.agentVersion -cne $ExpectedVersion -or [int]$reply.clientVersionCode -ne $ExpectedVersionCode) { continue }
                if (-not (Test-LanPowerHandshakeSignature $ManagementKey "state`n$nonce`n$deviceId`n$($reply.agentVersion)`n$($reply.uwf)`n$($reply.capabilities)" ([string]$reply.stateSignature))) { continue }
                if (-not (Test-LanPowerHandshakeSignature $ManagementKey "update`n$nonce`n$deviceId`n$($reply.clientVersionCode)`n$($reply.latestVersion)`n$($reply.updateStatus)`n$($reply.updateMessage)" ([string]$reply.updateSignature))) { continue }
                return [pscustomobject]@{ Ready = $true; DeviceId = $deviceId; Version = $ExpectedVersion; VersionCode = $ExpectedVersionCode }
            }
            catch { } # A startup delay or unauthenticated packet cannot count as success.
            Start-Sleep -Milliseconds 100
        }
        throw 'CLIENT_NOT_RESPONDING: Da ghi tep nhung client chua phan hoi hop le. Chon Cai / Cai de de thu lai; chua bao cai dat thanh cong.'
    }
    finally { $watch.Stop(); $socket.Dispose() }
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
        version = '1.2.3'
        versionCode = 8
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
    Invoke-LanPowerUwfPreparation -SkipUwfPreparation:$SkipUwfPreparation -InstallPath $InstallPath
    [void](Wait-LanPowerInstalledAgent -InstallPath $InstallPath -ManagementKey $ManagementKey -Port $Port)
    Write-Host "Standalone agent installed. UDP $Port is open to LocalSubnet." -ForegroundColor Green
    }
    finally { $installMutex.ReleaseMutex(); $installMutex.Dispose() }
}
