[CmdletBinding()]
param(
    [switch]$DebugMode,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$AgentVersion = '1.0.1'
$AgentRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ConfigPath = Join-Path $AgentRoot 'config.json'
$Port = 17840
$usedNonces = @{}
$script:UwfState = 'unknown'
$script:LastUwfCheck = [DateTime]::MinValue

function Write-DebugLog {
    param([string]$Message)
    if ($DebugMode) { Write-Output $Message }
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
    if (([DateTime]::UtcNow - $script:LastUwfCheck).TotalMinutes -lt 10) {
        return $script:UwfState
    }
    try {
        $filter = Get-CimInstance -Namespace 'root\standardcimv2\embedded' -ClassName UWF_Filter -ErrorAction Stop
        if ($filter.CurrentEnabled) { $script:UwfState = 'ON' }
        elseif ($filter.NextEnabled) { $script:UwfState = 'OFF (next ON)' }
        else { $script:UwfState = 'OFF' }
    }
    catch { $script:UwfState = 'not available' }
    $script:LastUwfCheck = [DateTime]::UtcNow
    return $script:UwfState
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
    Send-JsonDatagram -Client $Client -Remote $Remote -Value ([ordered]@{
        type = 'ack'
        requestId = $RequestId
        status = $Status
        message = $Message
        signature = $signature
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
                    Send-JsonDatagram -Client $udp -Remote $remote -Value ([ordered]@{
                        type = 'discovery'
                        requestNonce = $message.nonce
                        deviceId = $config.deviceId
                        hostname = $hostname
                        mac = $mac
                        agentVersion = $AgentVersion
                        uwf = Get-UwfState
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
                $expected = Get-HmacBase64 $config.managementKey $canonical
                if (-not (Test-FixedTimeEqual $message.signature $expected)) { continue }
                $usedNonces[[string]$message.nonce] = $now

                if ($message.action -notin @('shutdown', 'restart')) {
                    Send-Acknowledgement -Client $udp -Remote $remote -Config $config -RequestId $message.requestId -Status 'rejected' -Message 'Unsupported action.'
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
    if ($mutex) { $mutex.Dispose() }
}
