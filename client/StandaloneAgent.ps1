[CmdletBinding()]
param(
    [switch]$DebugMode,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$AgentVersion = '1.3.2'
$AgentVersionCode = 12
$AgentRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ConfigPath = Join-Path $AgentRoot 'config.json'
$Port = 17840
$usedNonces = @{}
$script:UwfState = 'unknown'
$script:UwfAvailable = $false
$script:LastUwfCheck = [DateTime]::MinValue
$script:DisplayRuntime = $null
$script:RuntimeStatus = 'not-configured'
$script:RuntimeMessage = 'Media data on D: has not been prepared. Reinstall the current client with D: available and unprotected.'
$script:RuntimeLastTry = [DateTime]::MinValue

function Write-DebugLog {
    param([string]$Message)
    if ($DebugMode) { Write-Output $Message }
}

function Initialize-LanPowerRuntime {
    param([pscustomobject]$Config)
    $script:RuntimeLastTry = [DateTime]::UtcNow
    if (-not $Config.PSObject.Properties['runtimeDataRoot'] -or -not [string]$Config.runtimeDataRoot) {
        if ($Config.PSObject.Properties['runtimeSetupMessage'] -and [string]$Config.runtimeSetupMessage) {
            $detail = ([string]$Config.runtimeSetupMessage).Replace([string]$Config.managementKey, '[hidden]') -replace '[\r\n\x00]', ' '
            $script:RuntimeMessage = if ($detail.Length -gt 256) { $detail.Substring(0, 256) } else { $detail }
        }
        return
    }
    try {
        $library = Join-Path $AgentRoot 'RoomTN20.Runtime.dll'
        $certificate = Join-Path $AgentRoot 'media-certificate.pfx'
        $player = Join-Path $AgentRoot 'Display\RoomTN20.Player.exe'
        if (-not (Test-Path -LiteralPath $library -PathType Leaf) -or -not (Test-Path -LiteralPath $certificate -PathType Leaf) -or -not (Test-Path -LiteralPath $player -PathType Leaf)) { throw 'Runtime files or media certificate are missing.' }
        [void][Reflection.Assembly]::LoadFrom($library)
        $runtimePort = if ($Config.PSObject.Properties['runtimePort']) { [int]$Config.runtimePort } else { 17842 }
        $script:DisplayRuntime = [RoomTN20.Runtime.Controller]::Start([string]$Config.runtimeDataRoot, [string]$Config.managementKey, [string]$Config.deviceId, $runtimePort, $certificate, $player)
        $script:RuntimeStatus = 'ready'
        $script:RuntimeMessage = 'Media receiver is ready.'
    }
    catch {
        $script:RuntimeStatus = 'error'
        $detail = $_.Exception.GetBaseException().Message.Replace([string]$Config.managementKey, '[hidden]') -replace '[\r\n\x00]', ' '
        $script:RuntimeMessage = if ($detail.Length -gt 256) { $detail.Substring(0, 256) } else { $detail }
        Write-DebugLog ('Display/media runtime unavailable: ' + $script:RuntimeMessage)
        $script:DisplayRuntime = $null
    }
}

function Get-LanPowerRuntimeSnapshot {
    $result = @{ lockState = 'ui-offline'; mediaVersion = 'none'; mediaPort = 0; mediaCertSha256 = ''; available = $false }
    if ($script:DisplayRuntime) {
        try {
            $snapshot = $script:DisplayRuntime.Snapshot()
            $result.lockState = [string]$snapshot.LockState
            $result.mediaVersion = [string]$snapshot.MediaVersion
            $result.mediaPort = [int]$snapshot.MediaPort
            $result.mediaCertSha256 = [string]$snapshot.MediaCertSha256
            $result.available = $true
        }
        catch { }
    }
    return $result
}

function Invoke-LanPowerSessionLock {
    param([ValidateSet('lock', 'unlock')][string]$Action)
    if (-not $script:DisplayRuntime) { throw 'LOCK_RUNTIME_UNAVAILABLE: Check the D: data volume, runtime files and interactive player.' }
    return $script:DisplayRuntime.SetLocked($Action -eq 'lock')
}

$created = $false
$mutex = [Threading.Mutex]::new($false, 'Global\LanPowerStandaloneAgent', [ref]$created)
if (-not $created) { exit 0 }

function Get-HmacBase64 {
    param([string]$SecretBase64, [string]$Value)
    $key = [Convert]::FromBase64String($SecretBase64)
    $hmac = [Security.Cryptography.HMACSHA256]::new($key)
    try {
        return [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))
    }
    finally { $hmac.Dispose() }
}

function Test-FixedTimeEqual {
    param([string]$Left, [string]$Right)
    try {
        $a = [Convert]::FromBase64String($Left)
        $b = [Convert]::FromBase64String($Right)
    }
    catch { return $false }
    if ($a.Length -ne $b.Length) { return $false }
    $difference = 0
    for ($index = 0; $index -lt $a.Length; $index++) {
        $difference = $difference -bor ($a[$index] -bxor $b[$index])
    }
    return $difference -eq 0
}

function Get-PrimaryMacAddress {
    $adapter = Get-NetAdapter -Physical -ErrorAction SilentlyContinue |
        Sort-Object @{ Expression = { if ($_.Status -eq 'Up') { 0 } else { 1 } } }, ifIndex |
        Select-Object -First 1
    if ($adapter) {
        $value = $adapter.MacAddress
    }
    else {
        $network = [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
            Where-Object {
                $_.NetworkInterfaceType -ne [Net.NetworkInformation.NetworkInterfaceType]::Loopback -and
                $_.OperationalStatus -eq [Net.NetworkInformation.OperationalStatus]::Up -and
                $_.GetPhysicalAddress().GetAddressBytes().Length -eq 6
            } |
            Select-Object -First 1
        $value = if ($network) { $network.GetPhysicalAddress().ToString() } else { $null }
    }
    $hex = ([string]$value -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
    if ($hex.Length -ne 12) { throw 'Cannot determine the Ethernet MAC address.' }
    return (($hex -split '(.{2})' | Where-Object { $_ }) -join ':')
}

function Get-UwfState {
    if (([DateTime]::UtcNow - $script:LastUwfCheck).TotalSeconds -lt 20) {
        return $script:UwfState
    }
    try {
        $uwf = Join-Path $env:WINDIR 'System32\uwfmgr.exe'
        if (-not (Test-Path -LiteralPath $uwf)) { throw 'UWF_NOT_INSTALLED' }
        $filter = Get-CimInstance -Namespace 'root\standardcimv2\embedded' -ClassName UWF_Filter -ErrorAction Stop
        $volumes = @(Get-CimInstance -Namespace 'root\standardcimv2\embedded' -ClassName UWF_Volume -ErrorAction Stop |
            Where-Object { $_.DriveLetter -in @('C:', 'C') })
        $currentProtected = @($volumes | Where-Object { $_.CurrentSession -and $_.Protected }).Count -gt 0
        $nextProtected = @($volumes | Where-Object { -not $_.CurrentSession -and $_.Protected }).Count -gt 0
        $current = if ($filter.CurrentEnabled -and $currentProtected) { 'ON' } else { 'OFF' }
        $next = if ($filter.NextEnabled -and $nextProtected) { 'ON' } else { 'OFF' }
        $script:UwfState = "$current (next $next)"
        $script:UwfAvailable = $true
    }
    catch { $script:UwfState = 'UWF_NOT_INSTALLED'; $script:UwfAvailable = $false }
    $script:LastUwfCheck = [DateTime]::UtcNow
    return $script:UwfState
}

function Get-UwfCapabilities {
    [void](Get-UwfState)
    if ($script:UwfAvailable) { return 'power,uwf,wolrelay' }
    return 'power,wolrelay'
}

function Invoke-WakeRelay {
    param([string]$Mac)
    if ($Mac -cnotmatch '\A(?:[0-9A-F]{2}:){5}[0-9A-F]{2}\z') { throw 'Invalid target MAC.' }
    $macBytes = @($Mac.Split(':') | ForEach-Object { [Convert]::ToByte($_, 16) })
    $packet = New-Object byte[] 102
    for ($index = 0; $index -lt 6; $index++) { $packet[$index] = 255 }
    for ($index = 6; $index -lt 102; $index++) { $packet[$index] = $macBytes[($index - 6) % 6] }
    $broadcasts = @('255.255.255.255')
    foreach ($adapter in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
        if ($adapter.OperationalStatus -ne [Net.NetworkInformation.OperationalStatus]::Up -or
            $adapter.NetworkInterfaceType -notin @([Net.NetworkInformation.NetworkInterfaceType]::Ethernet, [Net.NetworkInformation.NetworkInterfaceType]::Wireless80211)) { continue }
        foreach ($unicast in $adapter.GetIPProperties().UnicastAddresses) {
            if ($unicast.Address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or -not $unicast.IPv4Mask) { continue }
            $address = $unicast.Address.GetAddressBytes()
            $mask = $unicast.IPv4Mask.GetAddressBytes()
            $broadcast = New-Object byte[] 4
            for ($index = 0; $index -lt 4; $index++) { $broadcast[$index] = $address[$index] -bor (255 -bxor $mask[$index]) }
            $value = ([Net.IPAddress]::new($broadcast)).ToString()
            if ($value -notin $broadcasts) { $broadcasts += $value }
        }
    }
    $sent = 0
    $sender = [Net.Sockets.UdpClient]::new()
    try {
        $sender.EnableBroadcast = $true
        foreach ($broadcast in $broadcasts) {
            try { [void]$sender.Send($packet, $packet.Length, $broadcast, 9); $sent++ } catch { }
        }
    }
    finally { $sender.Dispose() }
    if ($sent -eq 0) { throw 'Wake relay could not send any local broadcast packet.' }
    return "Wake packet relayed for $Mac. Packet delivery does not confirm the target booted."
}

function Get-AgentUpdateStatus {
    $result = @{ latestVersion = 'unknown'; status = 'unknown'; message = 'The updater has not checked yet.' }
    $path = Join-Path $AgentRoot 'update-status.json'
    try {
        if (Test-Path -LiteralPath $path) {
            $status = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
            if ($status.status -in @('current', 'available', 'updating', 'blocked-uwf', 'error', 'disabled', 'unknown')) {
                $result.latestVersion = [string]$status.latestVersion
                $result.status = [string]$status.status
                $result.message = [string]$status.message
            }
        }
    } catch { }
    return $result
}

function Invoke-UwfCommand {
    param([string]$Tool, [string[]]$Arguments)
    $output = & $Tool @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "UWF_COMMAND_FAILED: $($Arguments -join ' ') (exit $LASTEXITCODE)."
    }
}

function Invoke-UwfAction {
    param([ValidateSet('freeze', 'unfreeze')][string]$Action)
    $uwf = Join-Path $env:WINDIR 'System32\uwfmgr.exe'
    if (-not (Test-Path -LiteralPath $uwf)) {
        throw 'UWF_NOT_INSTALLED: Reinstall the current Room TN20 client to prepare UWF automatically, then restart if requested.'
    }
    try {
        $filter = Get-CimInstance -Namespace 'root\standardcimv2\embedded' -ClassName UWF_Filter -ErrorAction Stop
        $volumes = @(Get-CimInstance -Namespace 'root\standardcimv2\embedded' -ClassName UWF_Volume -ErrorAction Stop |
            Where-Object { $_.DriveLetter -in @('C:', 'C') })
    }
    catch { throw 'UWF_NOT_INSTALLED: Reinstall the current Room TN20 client to prepare its Windows feature, then restart if requested.' }
    $nextProtected = @($volumes | Where-Object { -not $_.CurrentSession -and $_.Protected }).Count -gt 0
    if ($Action -eq 'freeze') {
        # Preserve an administrator's existing overlay policy. Initialize only an
        # unusably small overlay while the filter is currently disabled.
        if (-not $filter.CurrentEnabled) {
            $overlay = $null
            try {
                $overlay = Get-CimInstance -Namespace 'root\standardcimv2\embedded' -ClassName UWF_OverlayConfig -Filter 'CurrentSession = false' -ErrorAction Stop |
                    Select-Object -First 1
            }
            catch { } # Default UWF overlay configuration remains in effect.
            if ($overlay -and [int64]$overlay.MaximumSize -lt 512) {
                Invoke-UwfCommand -Tool $uwf -Arguments @('overlay', 'set-size', '1024')
            }
        }
        if (-not $nextProtected) { Invoke-UwfCommand -Tool $uwf -Arguments @('volume', 'protect', 'C:') }
        if (-not $filter.NextEnabled) { Invoke-UwfCommand -Tool $uwf -Arguments @('filter', 'enable') }
    }
    elseif ($nextProtected) {
        # Do not disable the global filter: other protected volumes keep their policy.
        Invoke-UwfCommand -Tool $uwf -Arguments @('volume', 'unprotect', 'C:')
    }
    $script:LastUwfCheck = [DateTime]::MinValue
    $state = Get-UwfState
    $expectedNext = if ($Action -eq 'freeze') { 'ON' } else { 'OFF' }
    if ($state -notlike "*next $expectedNext)*") {
        throw "UWF_VERIFY_FAILED: C: is $state after $Action. Check the device locally before restarting."
    }
    return "UWF $Action scheduled for C:. Restart required; no automatic reboot. C: $state"
}

function Send-JsonDatagram {
    param([Net.Sockets.UdpClient]$Client, [Net.IPEndPoint]$Remote, [object]$Value)
    $json = $Value | ConvertTo-Json -Compress -Depth 6
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    [void]$Client.Send($bytes, $bytes.Length, $Remote)
}

function Send-Acknowledgement {
    param(
        [Net.Sockets.UdpClient]$Client,
        [Net.IPEndPoint]$Remote,
        [pscustomobject]$Config,
        [string]$RequestId,
        [string]$Status,
        [string]$Message
    )
    $signature = Get-HmacBase64 $Config.managementKey "ack`n$RequestId`n$Status"
    $detailSignature = Get-HmacBase64 $Config.managementKey "ack-detail`n$RequestId`n$Status`n$Message"
    Send-JsonDatagram -Client $Client -Remote $Remote -Value ([ordered]@{
        type = 'ack'
        requestId = $RequestId
        status = $Status
        message = $Message
        signature = $signature
        detailSignature = $detailSignature
    })
}

function Invoke-PowerAction {
    param([ValidateSet('shutdown', 'restart')][string]$Action)
    Start-Sleep -Milliseconds 900
    $uwf = Join-Path $env:WINDIR 'System32\uwfmgr.exe'
    if (Test-Path -LiteralPath $uwf) {
        if ($Action -eq 'shutdown') { & $uwf filter shutdown }
        else { & $uwf filter restart }
        if ($LASTEXITCODE -eq 0) { return }
    }
    $arguments = if ($Action -eq 'shutdown') { '/s /t 0 /f' } else { '/r /t 0 /f' }
    Start-Process -FilePath "$env:WINDIR\System32\shutdown.exe" -ArgumentList $arguments -WindowStyle Hidden
}

if ($SelfTest) {
    $testKey = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('key'))
    $actual = Get-HmacBase64 $testKey 'The quick brown fox jumps over the lazy dog'
    $expected = '97yD9DBThCSxMpjmqm+xQ+9NWaFJRhdZl0edvC0aPNg='
    if (-not (Test-FixedTimeEqual $actual $expected)) { throw 'HMAC-SHA256 self-test failed.' }
    Write-Output 'HMAC-SHA256 self-test passed.'
    exit 0
}

try {
    if (-not (Test-Path -LiteralPath $ConfigPath)) { throw "Missing configuration: $ConfigPath" }
    $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    $keyBytes = [Convert]::FromBase64String($config.managementKey)
    if ($keyBytes.Length -ne 32) { throw 'Management Key must contain exactly 32 bytes.' }
    $Port = [int]$config.port
    $hostname = $env:COMPUTERNAME
    $mac = Get-PrimaryMacAddress
    Initialize-LanPowerRuntime -Config $config
    $udp = [Net.Sockets.UdpClient]::new($Port)
    Write-DebugLog "Listening on UDP $Port"
    try {
        while ($true) {
            $remote = [Net.IPEndPoint]::new([Net.IPAddress]::Any, 0)
            $bytes = $udp.Receive([ref]$remote)
            Write-DebugLog "Received $($bytes.Length) bytes from $remote"
            if ($bytes.Length -gt 8192) { continue }
            try {
                $message = [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json -ErrorAction Stop
                if ($message.type -eq 'discover') {
                    Write-DebugLog 'Processing discovery request'
                    if (-not $message.nonce -or -not $message.signature) { continue }
                    $expected = Get-HmacBase64 $config.managementKey "discover`n$($message.nonce)"
                    if (-not (Test-FixedTimeEqual $message.signature $expected)) {
                        Write-DebugLog 'Discovery signature mismatch'
                        continue
                    }
                    $responseSignature = Get-HmacBase64 $config.managementKey "discovery`n$($message.nonce)`n$($config.deviceId)`n$hostname`n$mac"
                    if (-not $script:DisplayRuntime -and ([DateTime]::UtcNow - $script:RuntimeLastTry).TotalSeconds -ge 30) { Initialize-LanPowerRuntime -Config $config }
                    $uwfState = Get-UwfState
                    $capabilities = Get-UwfCapabilities
                    $display = Get-LanPowerRuntimeSnapshot
                    if ($display.available) { $capabilities += ',sessionlock,media' }
                    $stateSignature = Get-HmacBase64 $config.managementKey "state`n$($message.nonce)`n$($config.deviceId)`n$AgentVersion`n$uwfState`n$capabilities"
                    $update = Get-AgentUpdateStatus
                    $updateSignature = Get-HmacBase64 $config.managementKey "update`n$($message.nonce)`n$($config.deviceId)`n$AgentVersionCode`n$($update.latestVersion)`n$($update.status)`n$($update.message)"
                    $lockSignature = Get-HmacBase64 $config.managementKey "lockstate`n$($message.nonce)`n$($config.deviceId)`n$($display.lockState)`n$($display.mediaVersion)`n$($display.mediaPort)`n$($display.mediaCertSha256)"
                    $runtimeSignature = Get-HmacBase64 $config.managementKey "runtime`n$($message.nonce)`n$($config.deviceId)`n$script:RuntimeStatus`n$script:RuntimeMessage"
                    Send-JsonDatagram -Client $udp -Remote $remote -Value ([ordered]@{
                        type = 'discovery'
                        requestNonce = $message.nonce
                        deviceId = $config.deviceId
                        hostname = $hostname
                        mac = $mac
                        agentVersion = $AgentVersion
                        uwf = $uwfState
                        capabilities = $capabilities
                        runtimeStatus = $script:RuntimeStatus
                        runtimeMessage = $script:RuntimeMessage
                        runtimeSignature = $runtimeSignature
                        stateSignature = $stateSignature
                        clientVersionCode = $AgentVersionCode
                        latestVersion = $update.latestVersion
                        updateStatus = $update.status
                        updateMessage = $update.message
                        updateSignature = $updateSignature
                        lockState = $display.lockState
                        mediaVersion = $display.mediaVersion
                        mediaPort = $display.mediaPort
                        mediaCertSha256 = $display.mediaCertSha256
                        lockSignature = $lockSignature
                        signature = $responseSignature
                    })
                    continue
                }

                if ($message.type -ne 'command') { continue }
                if (-not $message.requestId -or -not $message.deviceId -or -not $message.action -or -not $message.timestamp -or -not $message.nonce -or -not $message.signature) { continue }
                if ($message.deviceId -ne $config.deviceId) { continue }
                $timestamp = [int64]$message.timestamp
                $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
                if ([Math]::Abs($now - $timestamp) -gt 120) { continue }

                foreach ($knownNonce in @($usedNonces.Keys)) {
                    if ($now - [int64]$usedNonces[$knownNonce] -gt 600) { $usedNonces.Remove($knownNonce) }
                }
                if ($usedNonces.ContainsKey([string]$message.nonce)) { continue }

                $canonical = "command`n$($message.requestId)`n$($message.deviceId)`n$($message.action)`n$timestamp`n$($message.nonce)"
                if ($message.action -eq 'wake') {
                    if (-not $message.PSObject.Properties['targetMac'] -or [string]$message.targetMac -cnotmatch '\A(?:[0-9A-F]{2}:){5}[0-9A-F]{2}\z') { continue }
                    $canonical += "`n$($message.targetMac)"
                }
                $expected = Get-HmacBase64 $config.managementKey $canonical
                if (-not (Test-FixedTimeEqual $message.signature $expected)) { continue }
                $usedNonces[[string]$message.nonce] = $now

                if ($message.action -notin @('shutdown', 'restart', 'freeze', 'unfreeze', 'wake', 'lock', 'unlock')) {
                    Send-Acknowledgement -Client $udp -Remote $remote -Config $config -RequestId $message.requestId -Status 'rejected' -Message 'Unsupported action.'
                    continue
                }
                if ($message.action -in @('freeze', 'unfreeze', 'wake', 'lock', 'unlock')) {
                    try {
                        $result = if ($message.action -eq 'wake') { Invoke-WakeRelay -Mac $message.targetMac }
                            elseif ($message.action -in @('lock', 'unlock')) { Invoke-LanPowerSessionLock -Action $message.action }
                            else { Invoke-UwfAction -Action $message.action }
                        Send-Acknowledgement -Client $udp -Remote $remote -Config $config -RequestId $message.requestId -Status 'accepted' -Message $result
                    }
                    catch {
                        $script:LastUwfCheck = [DateTime]::MinValue
                        $failure = [string]$_.Exception.Message
                        if ($failure.Length -gt 512) { $failure = $failure.Substring(0, 512) }
                        Send-Acknowledgement -Client $udp -Remote $remote -Config $config -RequestId $message.requestId -Status 'rejected' -Message $failure
                    }
                    continue
                }
                Send-Acknowledgement -Client $udp -Remote $remote -Config $config -RequestId $message.requestId -Status 'accepted' -Message 'Power action accepted.'
                Invoke-PowerAction -Action $message.action
                exit 0
            }
            catch {
                # Invalid or unauthenticated datagrams are ignored deliberately.
                Write-DebugLog $_.Exception.Message
                continue
            }
        }
    }
    finally { $udp.Dispose() }
}
finally {
    if ($script:DisplayRuntime) { $script:DisplayRuntime.Dispose() }
    if ($mutex) { $mutex.Dispose() }
}
