[CmdletBinding(DefaultParameterSetName = 'Update')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Verify')][string]$VerifyManifestPath,
    [Parameter(Mandatory, ParameterSetName = 'Verify')][string]$PublicKeyPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$env:PSModulePath = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\Modules'

function Test-RoomTN20DownloadUrl {
    param([string]$Url)
    $uri = $null
    if (-not [Uri]::TryCreate($Url, [UriKind]::Absolute, [ref]$uri)) { return $false }
    return $uri.Scheme -ceq 'https' -and $uri.DnsSafeHost -ieq 'raw.githubusercontent.com' -and
        $uri.IsDefaultPort -and -not $uri.UserInfo -and -not $uri.Query -and -not $uri.Fragment -and
        $uri.AbsolutePath -cmatch '\A/namnamthainguyen-ops/apklan/(?:[0-9a-f]{40}|main)/(?:client|release)/[A-Za-z0-9_.-]+\z'
}

function Test-RoomTN20NewerVersion {
    param([int]$InstalledVersionCode, [int]$CandidateVersionCode)
    return $CandidateVersionCode -gt $InstalledVersionCode -and $CandidateVersionCode -gt 0
}

function Get-RoomTN20FileHash {
    param([string]$Path)
    $hash = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($Path)
    try { return [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-', '') }
    finally { $stream.Dispose(); $hash.Dispose() }
}

function Read-RoomTN20Manifest {
    param([string]$EnvelopeJson, [string]$PublicKeyXml)
    if ($EnvelopeJson.Length -gt 262144) { throw 'UPDATE_MANIFEST_TOO_LARGE' }
    $envelope = $EnvelopeJson | ConvertFrom-Json
    if ($envelope.algorithm -cne 'RS256') { throw 'UPDATE_SIGNATURE_ALGORITHM_INVALID' }
    $payloadBytes = [Convert]::FromBase64String([string]$envelope.payload)
    $signature = [Convert]::FromBase64String([string]$envelope.signature)
    $rsa = [Security.Cryptography.RSACryptoServiceProvider]::new()
    try {
        $rsa.PersistKeyInCsp = $false
        $rsa.FromXmlString($PublicKeyXml)
        if ($rsa.KeySize -lt 3072 -or -not $rsa.VerifyData($payloadBytes, [Security.Cryptography.CryptoConfig]::MapNameToOID('SHA256'), $signature)) {
            throw 'UPDATE_SIGNATURE_INVALID'
        }
    }
    finally { $rsa.Dispose() }
    $utf8 = [Text.UTF8Encoding]::new($false, $true)
    $payload = $utf8.GetString($payloadBytes) | ConvertFrom-Json
    if ([int]$payload.schemaVersion -ne 1 -or $payload.channel -cne 'stable') { throw 'UPDATE_SCHEMA_INVALID' }
    $published = [DateTimeOffset]::Parse([string]$payload.publishedUtc)
    if ($published -gt [DateTimeOffset]::UtcNow.AddHours(24)) { throw 'UPDATE_PUBLISHED_DATE_INVALID' }
    if ([string]$payload.windows.version -cnotmatch '\A\d+\.\d+\.\d+\z' -or [int]$payload.windows.versionCode -lt 1) { throw 'UPDATE_VERSION_INVALID' }
    foreach ($name in @('agent', 'core', 'updater')) {
        $file = $payload.windows.$name
        if (-not (Test-RoomTN20DownloadUrl ([string]$file.url)) -or [string]$file.sha256 -cnotmatch '\A[0-9A-Fa-f]{64}\z') { throw "UPDATE_FILE_INVALID: $name" }
    }
    return $payload
}

function Write-RoomTN20UpdateStatus {
    param([string]$Root, [int]$CurrentVersionCode, [string]$LatestVersion, [string]$Status, [string]$Message)
    if ($Message.Length -gt 512) { $Message = $Message.Substring(0, 512) }
    $json = [ordered]@{ currentVersionCode = $CurrentVersionCode; latestVersion = $LatestVersion; status = $Status;
        message = $Message; checkedUtc = [DateTime]::UtcNow.ToString('o') } | ConvertTo-Json
    Set-LanPowerAtomicFile (Join-Path $Root 'update-status.json') ([Text.Encoding]::ASCII.GetBytes($json))
}

function Save-RoomTN20Download {
    param([string]$Url, [string]$Path, [int]$MaximumBytes = 2097152)
    if (-not (Test-RoomTN20DownloadUrl $Url)) { throw 'UPDATE_URL_INVALID' }
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.AllowAutoRedirect = $false
    $request.Timeout = 60000
    $response = $request.GetResponse()
    try {
        if ([int]$response.StatusCode -ne 200 -or $response.ContentLength -gt $MaximumBytes) { throw 'UPDATE_DOWNLOAD_SIZE_OR_STATUS_INVALID' }
        $stream = $response.GetResponseStream()
        try {
            $output = [IO.File]::Create($Path)
            try {
                $buffer = New-Object byte[] 16384
                $total = 0
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $total += $read
                    if ($total -gt $MaximumBytes) { throw 'UPDATE_DOWNLOAD_TOO_LARGE' }
                    $output.Write($buffer, 0, $read)
                }
            }
            finally { $output.Dispose() }
        }
        finally { $stream.Dispose() }
    }
    finally { $response.Dispose() }
}

function Read-RoomTN20BoundedUtf8 {
    param([IO.Stream]$Stream, [ValidateRange(1, 262144)][int]$MaximumBytes = 262144)
    $output = [IO.MemoryStream]::new()
    try {
        $buffer = New-Object byte[] 16384
        $total = 0
        while ($true) {
            # Read at most one byte beyond the limit to detect an oversized
            # response even when Content-Length is missing or dishonest.
            $count = [Math]::Min($buffer.Length, $MaximumBytes - $total + 1)
            $read = $Stream.Read($buffer, 0, $count)
            if ($read -eq 0) { break }
            $total += $read
            if ($total -gt $MaximumBytes) { throw 'UPDATE_MANIFEST_TOO_LARGE' }
            $output.Write($buffer, 0, $read)
        }
        return ([Text.UTF8Encoding]::new($false, $true)).GetString($output.ToArray())
    }
    finally { $output.Dispose() }
}

function Get-RoomTN20ManifestJson {
    $request = [Net.HttpWebRequest]::Create('https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/updates/stable.json')
    $request.AllowAutoRedirect = $false
    $request.Timeout = 30000
    $request.ReadWriteTimeout = 30000
    $response = $request.GetResponse()
    try {
        if ([int]$response.StatusCode -ne 200) { throw 'UPDATE_MANIFEST_STATUS_INVALID' }
        if ($response.ContentLength -gt 262144) { throw 'UPDATE_MANIFEST_TOO_LARGE' }
        $stream = $response.GetResponseStream()
        try { return Read-RoomTN20BoundedUtf8 -Stream $stream -MaximumBytes 262144 }
        finally { $stream.Dispose() }
    }
    finally { $response.Dispose() }
}

function Invoke-RoomTN20Update {
    $root = $PSScriptRoot
    $configPath = Join-Path $root 'config.json'
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $currentCode = if ($config.PSObject.Properties['versionCode']) { [int]$config.versionCode } else { 0 }
    $latest = 'unknown'
    $staging = $null
    $installMutex = $null
    $mutex = [Threading.Mutex]::new($false, 'Global\RoomTN20Update-' + [string]$config.deviceId)
    $locked = $false
    try {
        $locked = $mutex.WaitOne(0)
        if (-not $locked) { return }
        . (Join-Path $root 'Install-Core.ps1')
        [void](Get-LanPowerInstallPath $root)
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $manifest = Read-RoomTN20Manifest -EnvelopeJson (Get-RoomTN20ManifestJson) -PublicKeyXml (Get-Content -LiteralPath (Join-Path $root 'Update-PublicKey.xml') -Raw)
        $latest = [string]$manifest.windows.version
        if (-not (Test-RoomTN20NewerVersion $currentCode ([int]$manifest.windows.versionCode))) {
            Write-RoomTN20UpdateStatus $root $currentCode $latest 'current' 'The installed client is current.'
            return
        }
        try { Assert-LanPowerWritableSystem }
        catch {
            Write-RoomTN20UpdateStatus $root $currentCode $latest 'blocked-uwf' 'Update pending: C: is protected or its UWF state is unavailable. Unfreeze C: and restart manually before updating.'
            return
        }
        Write-RoomTN20UpdateStatus $root $currentCode $latest 'available' 'A signed update is available.'
        $staging = Join-Path $env:ProgramData ('RoomTN20Update-' + [Guid]::NewGuid().ToString('N'))
        $security = [Security.AccessControl.DirectorySecurity]::new()
        $administrators = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
        $security.SetOwner($administrators)
        $security.SetAccessRuleProtection($true, $false)
        foreach ($sid in @($administrators, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
            [void]$security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
        }
        [void][IO.Directory]::CreateDirectory($staging, $security)
        $files = @(@('agent', 'StandaloneAgent.ps1'), @('core', 'Install-Core.ps1'), @('updater', 'Update-Agent.ps1'))
        foreach ($file in $files) {
            $metadata = $manifest.windows.($file[0])
            $target = Join-Path $staging $file[1]
            Save-RoomTN20Download -Url ([string]$metadata.url) -Path $target
            if ((Get-RoomTN20FileHash $target) -ine [string]$metadata.sha256) { throw "UPDATE_CHECKSUM_INVALID: $($file[0])" }
        }
        Copy-Item -LiteralPath (Join-Path $root 'Update-PublicKey.xml') -Destination (Join-Path $staging 'Update-PublicKey.xml')
        $installMutex = Enter-LanPowerInstallLock $root
        $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        $currentCode = [int]$config.versionCode
        if (-not (Test-RoomTN20NewerVersion $currentCode ([int]$manifest.windows.versionCode))) { return }
        Assert-LanPowerWritableSystem
        $backups = @{}
        foreach ($name in @('StandaloneAgent.ps1', 'Install-Core.ps1', 'Update-Agent.ps1', 'Update-PublicKey.xml', 'config.json')) {
            $backups[$name] = [IO.File]::ReadAllBytes((Join-Path $root $name))
        }
        Write-RoomTN20UpdateStatus $root $currentCode $latest 'updating' 'Installing the verified signed update.'
        try {
            . (Join-Path $staging 'Install-Core.ps1')
            $parameters = @{ SourceAgent = (Join-Path $staging 'StandaloneAgent.ps1'); SourceCore = (Join-Path $staging 'Install-Core.ps1');
                SourceUpdater = (Join-Path $staging 'Update-Agent.ps1'); SourcePublicKey = (Join-Path $staging 'Update-PublicKey.xml');
                ManagementKey = [string]$config.managementKey; InstallPath = $root; TaskName = [string]$config.agentTaskName; Port = [int]$config.port; SkipUwfPreparation = $true }
            if ($config.PSObject.Properties['vpnNetworks']) { $parameters.VpnNetworks = @($config.vpnNetworks) }
            Invoke-LanPowerInstall @parameters
            $installed = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
            if ([int]$installed.versionCode -ne [int]$manifest.windows.versionCode -or [string]$installed.version -cne $latest) { throw 'UPDATE_INSTALLED_VERSION_MISMATCH' }
            Write-RoomTN20UpdateStatus $root ([int]$installed.versionCode) $latest 'current' 'Signed update installed successfully.'
        }
        catch {
            $failure = $_
            Stop-ScheduledTask -TaskName ([string]$config.agentTaskName) -ErrorAction SilentlyContinue
            foreach ($name in $backups.Keys) { Set-LanPowerAtomicFile (Join-Path $root $name) $backups[$name] }
            . (Join-Path $root 'Install-Core.ps1')
            $restore = @{ SourceAgent = (Join-Path $root 'StandaloneAgent.ps1'); ManagementKey = [string]$config.managementKey; InstallPath = $root;
                TaskName = [string]$config.agentTaskName; Port = [int]$config.port; SkipUwfPreparation = $true }
            if ($config.PSObject.Properties['vpnNetworks']) { $restore.VpnNetworks = @($config.vpnNetworks) }
            Invoke-LanPowerInstall @restore
            throw $failure
        }
    }
    catch {
        $message = [string]$_.Exception.Message
        if ($config.PSObject.Properties['managementKey']) { $message = $message.Replace([string]$config.managementKey, '[hidden]') }
        Write-RoomTN20UpdateStatus $root $currentCode $latest 'error' $message
    }
    finally {
        if ($installMutex) { $installMutex.ReleaseMutex(); $installMutex.Dispose() }
        if ($staging -and (Test-Path -LiteralPath $staging)) {
            $resolved = [IO.Path]::GetFullPath($staging)
            if ((Split-Path -Parent $resolved).TrimEnd('\') -ine [IO.Path]::GetFullPath($env:ProgramData).TrimEnd('\') -or (Split-Path -Leaf $resolved) -notmatch '^RoomTN20Update-[0-9a-f]{32}$') { throw 'Unsafe update cleanup path.' }
            if ((Get-Item -LiteralPath $resolved -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unsafe update staging reparse point.' }
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
        if ($locked) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
    }
}

if ($PSCmdlet.ParameterSetName -eq 'Verify') {
    $verified = Read-RoomTN20Manifest -EnvelopeJson (Get-Content -LiteralPath $VerifyManifestPath -Raw) -PublicKeyXml (Get-Content -LiteralPath $PublicKeyPath -Raw)
    Write-Output "MANIFEST_VERSION=$($verified.windows.version)"
    Write-Output 'SIGNED_MANIFEST=PASS'
}
else { Invoke-RoomTN20Update }
