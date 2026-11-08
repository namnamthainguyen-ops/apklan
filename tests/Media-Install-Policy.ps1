$ErrorActionPreference = 'Stop'
$env:PSModulePath = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\Modules'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent $PSScriptRoot
$workspace = [IO.Path]::GetFullPath((Join-Path $repoRoot '..'))
$testRoot = Join-Path $workspace ('media-policy-' + [Guid]::NewGuid().ToString('N'))
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $repoRoot 'client\Install-Core.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Core installer did not parse.' }
foreach ($name in @('Resolve-LanPowerRuntimePayload', 'Initialize-LanPowerMediaCertificate', 'Initialize-LanPowerMediaData')) {
    $functions = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true))
    if ($functions.Count -ne 1) { throw "Expected one media installation helper: $name" }
    . ([scriptblock]::Create($functions[0].Extent.Text))
}
function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Assertion failed: $Message" }
}
function Assert-Rejected {
    param([scriptblock]$Action, [string]$Message)
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-True $rejected $Message
}
function Assert-Isolated {
    param([string]$Path)
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($testRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Test write outside isolated workspace refused.' }
}
function Set-LanPowerAtomicFile {
    param([string]$Path, [byte[]]$Bytes)
    Assert-Isolated $Path
    [IO.File]::WriteAllBytes($Path, $Bytes)
}
function Get-PfxFingerprint {
    param([string]$Path)
    $cert = [Security.Cryptography.X509Certificates.X509Certificate2]::new([IO.File]::ReadAllBytes($Path), '', [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
    try {
        Assert-True $cert.HasPrivateKey 'Media TLS PFX must contain its private key.'
        $sha = [Security.Cryptography.SHA256]::Create()
        try { return [BitConverter]::ToString($sha.ComputeHash($cert.RawData)).Replace('-', '') }
        finally { $sha.Dispose() }
    }
    finally { $cert.Dispose() }
}
try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    foreach ($kind in @('runtime', 'player')) {
        $filename = if ($kind -eq 'runtime') { 'RoomTN20.Runtime-1.3.2.dll' } else { 'RoomTN20.Player-1.3.2.exe' }
        $published = Join-Path $repoRoot ('release\' + $filename)
        $resolved = Resolve-LanPowerRuntimePayload -Source $published -PayloadRoot (Join-Path $repoRoot 'client') -Kind $kind
        Assert-True ([IO.Path]::GetFullPath($resolved) -ceq [IO.Path]::GetFullPath($published)) 'Valid published runtime payload must resolve locally without downloading or executing it.'
        $tampered = Join-Path $testRoot $filename
        [IO.File]::WriteAllText($tampered, 'tampered fixture; never executable', [Text.Encoding]::ASCII)
        Assert-Rejected { Resolve-LanPowerRuntimePayload -Source $tampered -PayloadRoot $testRoot -Kind $kind } 'Tampered runtime/player payload must fail hash verification.'
    }
    $certificatePath = Join-Path $testRoot 'media-certificate.pfx'
    $deviceId = [Guid]::NewGuid().ToString()
    Initialize-LanPowerMediaCertificate -Path $certificatePath -DeviceId $deviceId
    $first = Get-PfxFingerprint $certificatePath
    $firstFileHash = (Get-FileHash -LiteralPath $certificatePath -Algorithm SHA256).Hash
    Initialize-LanPowerMediaCertificate -Path $certificatePath -DeviceId $deviceId
    Assert-True ((Get-PfxFingerprint $certificatePath) -ceq $first -and (Get-FileHash -LiteralPath $certificatePath -Algorithm SHA256).Hash -ceq $firstFileHash) 'Reinstall must reuse the existing TLS media identity without changing its pin.'
    Assert-Rejected { Initialize-LanPowerMediaData -DataRoot ([IO.Path]::GetPathRoot($env:WINDIR)) } 'Media directory cannot be a drive root.'
    Assert-Rejected { Initialize-LanPowerMediaData -DataRoot (Join-Path $env:SystemDrive 'RoomTN20Data') } 'Media data cannot be written to the Windows drive.'
    # Model a machine without D: using read-only Test-Path/Get-Item mocks. Any
    # attempt to create directories or query UWF would escape this early return.
    function Test-Path { param([string]$LiteralPath, [string]$PathType) return $false }
    function New-Item { throw 'Missing data drive must not create directories.' }
    function Get-CimInstance { throw 'Missing data drive must not query or change UWF.' }
    Assert-True ((Initialize-LanPowerMediaData) -ceq '') 'Absent D: must disable media honestly without mutating another drive.'
    Remove-Item function:Test-Path
    Remove-Item function:New-Item
    Remove-Item function:Get-CimInstance
    Write-Output 'MEDIA_INSTALL_POLICY=PASS (pinned runtime/player bytes, tamper refusal, stable ephemeral PFX identity, Windows-drive/root rejection, absent-D no-op)'
}
finally {
    foreach ($function in @('Test-Path','New-Item','Get-CimInstance')) {
        if (Test-Path ('function:' + $function)) { Remove-Item ('function:' + $function) -ErrorAction SilentlyContinue }
    }
    if (Test-Path -LiteralPath $testRoot) {
        $resolved = [IO.Path]::GetFullPath($testRoot)
        if ((Split-Path -Parent $resolved) -ine $workspace -or (Split-Path -Leaf $resolved) -notmatch '^media-policy-[0-9a-f]{32}$') { throw 'Unsafe media test cleanup.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
