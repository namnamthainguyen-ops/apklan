$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent $PSScriptRoot
$sourcePath = Join-Path $repoRoot 'client\Update-Agent.ps1'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($sourcePath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Production updater does not parse.' }
foreach ($name in @('Test-RoomTN20DownloadUrl', 'Test-RoomTN20NewerVersion', 'Read-RoomTN20Manifest', 'Read-RoomTN20BoundedUtf8')) {
    $functions = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true))
    if ($functions.Count -ne 1) { throw "Expected one updater function $name." }
    . ([scriptblock]::Create($functions[0].Extent.Text))
}
function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Assertion failed: $Message" }
}
function Assert-Rejected {
    param([scriptblock]$Action, [string]$Message)
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    Assert-True $failed $Message
}
function New-TestPayload {
    $prefix = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/'
    $hash = ('A' * 64)
    return [ordered]@{ schemaVersion = 1; channel = 'stable'; publishedUtc = [DateTime]::UtcNow.ToString('o');
        android = @{ version = '1.2.1'; versionCode = 6; url = ($prefix + 'release/RoomTN20-1.2.1.apk'); sha256 = $hash;
            packageName = 'com.lanpower.standalone'; signerSha256 = ('B' * 64) };
        windows = @{ version = '1.2.1'; versionCode = 6;
            agent = @{ url = ($prefix + 'client/StandaloneAgent.ps1'); sha256 = $hash };
            core = @{ url = ($prefix + 'client/Install-Core.ps1'); sha256 = $hash };
            updater = @{ url = ($prefix + 'client/Update-Agent.ps1'); sha256 = $hash };
            setup = @{ url = ($prefix + 'release/RoomTN20-ClientSetup-1.2.1.exe'); sha256 = $hash } };
        releaseNotes = 'Disposable test fixture; no downloads or installation.' }
}
function New-TestEnvelope {
    param([object]$Payload, [Security.Cryptography.RSACryptoServiceProvider]$Signer)
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 12 -Compress))
    $signature = $Signer.SignData($bytes, [Security.Cryptography.CryptoConfig]::MapNameToOID('SHA256'))
    return [ordered]@{ algorithm = 'RS256'; payload = [Convert]::ToBase64String($bytes); signature = [Convert]::ToBase64String($signature) } | ConvertTo-Json -Compress
}

$maximumManifestBytes = [IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes(('A' * 262144)))
$oversizedManifestBytes = [IO.MemoryStream]::new((New-Object byte[] 1048576))
$invalidUtf8Bytes = [IO.MemoryStream]::new([byte[]]@(255, 255))
try {
    $decoded = Read-RoomTN20BoundedUtf8 -Stream $maximumManifestBytes
    Assert-True ($decoded.Length -eq 262144) 'An exactly 256 KiB manifest must be accepted.'
    Assert-Rejected { Read-RoomTN20BoundedUtf8 -Stream $oversizedManifestBytes } 'An unknown-length oversized manifest must be rejected during stream reading.'
    Assert-True ($oversizedManifestBytes.Position -eq 262145) 'Oversized response must stop after at most one overflow-detection byte; never consume the rest.'
    Assert-Rejected { Read-RoomTN20BoundedUtf8 -Stream $invalidUtf8Bytes } 'Malformed UTF-8 must be rejected before parsing.'
}
finally { $maximumManifestBytes.Dispose(); $oversizedManifestBytes.Dispose(); $invalidUtf8Bytes.Dispose() }

$key = [Security.Cryptography.RSACryptoServiceProvider]::new(3072)
$wrongKey = [Security.Cryptography.RSACryptoServiceProvider]::new(3072)
$smallKey = [Security.Cryptography.RSACryptoServiceProvider]::new(2048)
$key.PersistKeyInCsp = $false
$wrongKey.PersistKeyInCsp = $false
$smallKey.PersistKeyInCsp = $false
try {
    $public = $key.ToXmlString($false)
    $payload = New-TestPayload
    $envelope = New-TestEnvelope $payload $key
    $verified = Read-RoomTN20Manifest $envelope $public
    Assert-True ($verified.windows.versionCode -eq 6) 'Valid RS256 manifest must retain its signed version.'
    $mediaPayload = New-TestPayload
    $mediaPayload.windows.version = '1.3.0'
    $mediaPayload.windows.versionCode = 10
    Assert-Rejected { Read-RoomTN20Manifest (New-TestEnvelope $mediaPayload $key) $public } 'Version 10 requires signed runtime and player metadata.'
    $mediaPayload.windows.runtime = @{ url = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20.Runtime-1.3.0.dll'; sha256 = ('C' * 64) }
    $mediaPayload.windows.player = @{ url = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20.Player-1.3.0.exe'; sha256 = ('D' * 64) }
    Assert-True ((Read-RoomTN20Manifest (New-TestEnvelope $mediaPayload $key) $public).windows.versionCode -eq 10) 'Version 10 accepts valid signed runtime and player metadata.'
    foreach ($field in @('runtime', 'player')) {
        $previousUrl = $mediaPayload.windows[$field].url
        $mediaPayload.windows[$field].url = 'https://example.com/untrusted.exe'
        Assert-Rejected { Read-RoomTN20Manifest (New-TestEnvelope $mediaPayload $key) $public } "Untrusted $field URL must fail even when signed."
        $mediaPayload.windows[$field].url = $previousUrl
        $previousHash = $mediaPayload.windows[$field].sha256
        $mediaPayload.windows[$field].sha256 = 'not-a-hash'
        Assert-Rejected { Read-RoomTN20Manifest (New-TestEnvelope $mediaPayload $key) $public } "Invalid $field checksum must fail."
        $mediaPayload.windows[$field].sha256 = $previousHash
    }
    $tampered = $envelope | ConvertFrom-Json
    $tampered.payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"schemaVersion":1}'))
    Assert-Rejected { Read-RoomTN20Manifest ($tampered | ConvertTo-Json -Compress) $public } 'Tampered payload must fail before use.'
    Assert-Rejected { Read-RoomTN20Manifest $envelope ($wrongKey.ToXmlString($false)) } 'Manifest signed by an untrusted key must fail.'
    Assert-Rejected { Read-RoomTN20Manifest (New-TestEnvelope $payload $smallKey) ($smallKey.ToXmlString($false)) } 'RSA keys below 3072 bits must fail.'
    $badAlgorithm = $envelope | ConvertFrom-Json
    $badAlgorithm.algorithm = 'none'
    Assert-Rejected { Read-RoomTN20Manifest ($badAlgorithm | ConvertTo-Json -Compress) $public } 'Algorithm substitution must fail.'
    $badSignature = $envelope | ConvertFrom-Json
    $badSignature.signature = 'not base64'
    Assert-Rejected { Read-RoomTN20Manifest ($badSignature | ConvertTo-Json -Compress) $public } 'Malformed signature must fail.'
    foreach ($badField in @('schema', 'channel', 'future', 'hash', 'url', 'version')) {
        $badPayload = New-TestPayload
        switch ($badField) {
            schema { $badPayload.schemaVersion = 2 }
            channel { $badPayload.channel = 'testing' }
            future { $badPayload.publishedUtc = [DateTime]::UtcNow.AddDays(2).ToString('o') }
            hash { $badPayload.windows.agent.sha256 = '123' }
            url { $badPayload.windows.core.url = 'https://example.com/core.ps1' }
            version { $badPayload.windows.versionCode = 0 }
        }
        Assert-Rejected { Read-RoomTN20Manifest (New-TestEnvelope $badPayload $key) $public } "Signed but invalid $badField must fail."
    }
    Assert-Rejected { Read-RoomTN20Manifest ('X' * 262145) $public } 'Oversized envelope must fail.'
    $validUrl = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/Update-Agent.ps1'
    Assert-True (Test-RoomTN20DownloadUrl $validUrl) 'Expected HTTPS release source must be allowed.'
    Assert-True (Test-RoomTN20DownloadUrl ('https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/' + ('a' * 40) + '/release/RoomTN20-1.2.0.apk')) 'Commit-pinned HTTPS release source must be allowed.'
    foreach ($url in @('http://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/Update-Agent.ps1',
        'https://raw.githubusercontent.com/other/repo/main/client/Update-Agent.ps1',
        'https://raw.githubusercontent.com.evil.example/namnamthainguyen-ops/apklan/main/client/Update-Agent.ps1',
        'https://user@raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/Update-Agent.ps1',
        ($validUrl + '?download=1'), ($validUrl + '#fragment'),
        'https://raw.githubusercontent.com:444/namnamthainguyen-ops/apklan/main/client/Update-Agent.ps1',
        'file:///C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe',
        'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/updates/not-payload.ps1')) {
        Assert-True (-not (Test-RoomTN20DownloadUrl $url)) "Untrusted source must be rejected: $url"
    }
    Assert-True (Test-RoomTN20NewerVersion 5 6) 'A higher version code is an update.'
    Assert-True (-not (Test-RoomTN20NewerVersion 5 5)) 'Equal version is not an update.'
    Assert-True (-not (Test-RoomTN20NewerVersion 5 4)) 'Downgrade must be rejected.'
    Assert-True (-not (Test-RoomTN20NewerVersion 0 0)) 'Zero candidate version must be rejected.'
    Write-Output 'SIGNED_UPDATER_UNIT=PASS (RSA3072, tamper/wrong-key/algorithm/schema/source rejection, rollback guard; no network or installation)'
}
finally { $key.Dispose(); $wrongKey.Dispose(); $smallKey.Dispose() }
