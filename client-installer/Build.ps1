[CmdletBinding()]
param([string]$OutputPath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputPath) { $OutputPath = Join-Path $repoRoot 'release\RoomTN20-ClientSetup-1.2.4.exe' }
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
$agentPath = Join-Path $repoRoot 'client\StandaloneAgent.ps1'
$corePath = Join-Path $repoRoot 'client\Install-Core.ps1'
$updaterPath = Join-Path $repoRoot 'client\Update-Agent.ps1'
$publicKeyPath = Join-Path $repoRoot 'client\Update-PublicKey.xml'
$agentHash = (Get-FileHash -LiteralPath $agentPath -Algorithm SHA256).Hash
$coreHash = (Get-FileHash -LiteralPath $corePath -Algorithm SHA256).Hash
$updaterHash = (Get-FileHash -LiteralPath $updaterPath -Algorithm SHA256).Hash
$publicKeyHash = (Get-FileHash -LiteralPath $publicKeyPath -Algorithm SHA256).Hash
$framework = [Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()
$compiler = Join-Path $framework 'csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'The .NET Framework C# compiler was not found. Run this build using Windows PowerShell 5.1.' }
$sma = [Management.Automation.PowerShell].Assembly.Location
if ([Management.Automation.PowerShell].Assembly.GetName().Version.Major -ne 3) { throw 'Build using Windows PowerShell 5.1 (.NET Framework), not PowerShell 7.' }
$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$buildRoot = Join-Path $env:TEMP ('LanPowerBuild-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $buildRoot | Out-Null
try {
    $metadataPath = Join-Path $buildRoot 'PayloadInfo.g.cs'
    $metadata = @"
namespace LanPower.ClientSetup {
    internal static class PayloadInfo {
        internal const string AgentSha256 = "$agentHash";
        internal const string CoreSha256 = "$coreHash";
        internal const string UpdaterSha256 = "$updaterHash";
        internal const string PublicKeySha256 = "$publicKeyHash";
        internal const string Version = "1.2.4";
        internal const int VersionCode = 9;
    }
}
"@
    [IO.File]::WriteAllText($metadataPath, $metadata, [Text.Encoding]::UTF8)
    $arguments = @('/nologo', '/target:winexe', '/platform:anycpu', '/optimize+', '/codepage:65001', '/langversion:5',
        ('/out:' + $OutputPath), ('/win32manifest:' + (Join-Path $PSScriptRoot 'app.manifest')),
        '/reference:System.dll', '/reference:System.Core.dll', '/reference:System.Drawing.dll', '/reference:System.Windows.Forms.dll',
        ('/reference:' + $sma),
        ('/resource:' + $agentPath + ',LanPower.Payload.StandaloneAgent.ps1'),
        ('/resource:' + $corePath + ',LanPower.Payload.Install-Core.ps1'),
        ('/resource:' + $updaterPath + ',LanPower.Payload.Update-Agent.ps1'),
        ('/resource:' + $publicKeyPath + ',LanPower.Payload.Update-PublicKey.xml'),
        (Join-Path $PSScriptRoot 'Program.cs'), $metadataPath)
    & $compiler @arguments
    if ($LASTEXITCODE -ne 0) { throw "C# compiler failed with exit code $LASTEXITCODE." }
    # Runtime metadata is embedded through the manifest and assembly attributes;
    # no sidecar config, DLL, script, or payload is required by the downloadable EXE.
    Write-Output "BUILT_EXE=$OutputPath"
    Write-Output "EXE_SHA256=$((Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash)"
    Write-Output "AGENT_SHA256=$agentHash"
    Write-Output "CORE_SHA256=$coreHash"
    Write-Output "UPDATER_SHA256=$updaterHash"
    Write-Output "PUBLIC_KEY_SHA256=$publicKeyHash"
}
finally {
    if (Test-Path -LiteralPath $buildRoot) {
        $resolved = [IO.Path]::GetFullPath($buildRoot)
        $expectedParent = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')
        if ((Split-Path -Parent $resolved).TrimEnd('\') -ne $expectedParent -or (Split-Path -Leaf $resolved) -notmatch '^LanPowerBuild-[0-9a-f]{32}$') { throw 'Unsafe build cleanup path.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
