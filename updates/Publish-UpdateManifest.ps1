[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PrivateKeyPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$SourceCommit,
    [string]$Version = '1.2.3',
    [int]$VersionCode = 8,
    [string]$ReleaseNotes = 'Ho tro LAN/VPN WireGuard/OpenVPN va cap nhat co chu ky.'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$base = "https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/$SourceCommit/"
function Artifact([string]$Path) {
    $full = Join-Path $repoRoot $Path
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "Missing release artifact: $Path" }
    return [ordered]@{url = $base + $Path.Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash}
}
$android = Artifact "release/RoomTN20-$Version.apk"
$android.version = $Version
$android.versionCode = $VersionCode
$android.packageName = 'com.lanpower.standalone'
$android.signerSha256 = '253e62e567d191b4481aaae2cd087610e1ca730b76e5cf19ec4e97165fa22b5a'
$payload = [ordered]@{
    schemaVersion = 1
    channel = 'stable'
    publishedUtc = [DateTime]::UtcNow.ToString('o')
    android = $android
    windows = [ordered]@{
        version = $Version
        versionCode = $VersionCode
        agent = Artifact 'client/StandaloneAgent.ps1'
        core = Artifact 'client/Install-Core.ps1'
        updater = Artifact 'client/Update-Agent.ps1'
        setup = Artifact "release/RoomTN20-ClientSetup-$Version.exe"
    }
    releaseNotes = $ReleaseNotes
}
$bytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 8 -Compress))
$rsa = [Security.Cryptography.RSACryptoServiceProvider]::new()
$rsa.PersistKeyInCsp = $false
try {
    $rsa.FromXmlString([IO.File]::ReadAllText([IO.Path]::GetFullPath($PrivateKeyPath)))
    $expectedPublic = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'public-key.xml'))
    if ($rsa.ToXmlString($false) -cne $expectedPublic) { throw 'Private key does not match the deployed update trust key.' }
    $signature = $rsa.SignData($bytes, [Security.Cryptography.CryptoConfig]::MapNameToOID('SHA256'))
    $envelope = [ordered]@{algorithm = 'RS256'; payload = [Convert]::ToBase64String($bytes); signature = [Convert]::ToBase64String($signature)} | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'stable.json'), $envelope + "`n", [Text.UTF8Encoding]::new($false))
    Write-Output "SIGNED_STABLE_MANIFEST=PASS $Version ($SourceCommit)"
}
finally { $rsa.Dispose() }
