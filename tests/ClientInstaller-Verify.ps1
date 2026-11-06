[CmdletBinding()]
param([Parameter(Mandatory)][string]$InstallerExe)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent $PSScriptRoot
$exePath = (Resolve-Path -LiteralPath $InstallerExe).Path
$assembly = [Reflection.Assembly]::Load([IO.File]::ReadAllBytes($exePath))

foreach ($payload in @('StandaloneAgent.ps1', 'Install-Core.ps1', 'Update-Agent.ps1', 'Update-PublicKey.xml')) {
    $resources = @($assembly.GetManifestResourceNames() | Where-Object { $_.EndsWith($payload, [StringComparison]::OrdinalIgnoreCase) })
    if ($resources.Count -ne 1) { throw "Expected exactly one embedded payload resource for $payload." }
    $stream = $assembly.GetManifestResourceStream($resources[0])
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $embedded = [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-', '') }
    finally { $hash.Dispose(); $stream.Dispose() }
    $source = (Get-FileHash -LiteralPath (Join-Path $repoRoot "client\$payload") -Algorithm SHA256).Hash
    if ($embedded -cne $source) { throw "Embedded $payload differs from the checked-in source." }
    Write-Output "EMBEDDED_PAYLOAD=PASS $payload $embedded"
}

function Invoke-VerificationCommand {
    param([string]$Argument, [string[]]$ExpectedMarkers)
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $exePath
    $info.Arguments = $Argument
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw 'Cannot start installer self-verification.' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Installer self-verification timed out.' }
        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result
        if ($process.ExitCode -ne 0) { throw "Installer verification failed: $stdout $stderr" }
        foreach ($marker in $ExpectedMarkers) {
            if (-not $stdout.Contains($marker)) { throw "Installer self-verification did not report $marker." }
        }
        Write-Output ($stdout.Trim())
    }
    finally { $process.Dispose() }
}

Invoke-VerificationCommand '--verify-payload' @('PAYLOAD_VERIFY=PASS', 'ROOM_KEY_KDF=PASS', 'UPDATER_SHA256=', 'PUBLIC_KEY_SHA256=')
Invoke-VerificationCommand '--verify-ui' @('UI_CONTRACT=PASS', 'SUCCESS_AUTO_CLOSE=PASS')
Write-Output 'CLIENT_INSTALLER_VERIFICATION=PASS (embedded source hashes, KDF and in-process form contract; no installation)'
