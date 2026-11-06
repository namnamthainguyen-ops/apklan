[CmdletBinding()]
param([Parameter(Mandatory)][string]$PrivateKeyPath, [string]$PublicDirectory)
$ErrorActionPreference = 'Stop'
if (-not $PublicDirectory) { $PublicDirectory = $PSScriptRoot }
$privateFull = [IO.Path]::GetFullPath($PrivateKeyPath)
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
if ($privateFull.StartsWith($repoRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The private update signing key must be stored outside the repository.'
}
if (Test-Path -LiteralPath $privateFull) { throw 'A signing key already exists; never overwrite a deployed update trust key.' }
$privateDirectory = Split-Path -Parent $privateFull
New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
$owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetOwner($owner)
$acl.SetAccessRuleProtection($true, $false)
foreach ($sid in @($owner, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
    [void]$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
}
Set-Acl -LiteralPath $privateDirectory -AclObject $acl
New-Item -ItemType Directory -Path $PublicDirectory -Force | Out-Null
$rsa = [Security.Cryptography.RSACryptoServiceProvider]::new(3072)
$rsa.PersistKeyInCsp = $false
try {
    [IO.File]::WriteAllText($privateFull, $rsa.ToXmlString($true), [Text.Encoding]::ASCII)
    $parameters = $rsa.ExportParameters($false)
    $publicJson = [ordered]@{
        algorithm = 'RS256'
        modulus = [Convert]::ToBase64String($parameters.Modulus)
        exponent = [Convert]::ToBase64String($parameters.Exponent)
    } | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $PublicDirectory 'public-key.xml'), $rsa.ToXmlString($false), [Text.Encoding]::ASCII)
    [IO.File]::WriteAllText((Join-Path $PublicDirectory 'public-key.json'), $publicJson + "`n", [Text.UTF8Encoding]::new($false))
    Write-Output "PUBLIC_UPDATE_KEY_CREATED=$PublicDirectory"
    Write-Output "PRIVATE_KEY_STORED_OUTSIDE_REPOSITORY=$privateFull"
}
finally { $rsa.Dispose() }
