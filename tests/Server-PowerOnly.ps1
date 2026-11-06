$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'client\Install-Core.ps1')
$edition = Get-LanPowerWindowsEdition
if ($edition -notmatch '^Server') {
    Write-Output 'SERVER_POWER_ONLY_PREPARATION=SKIP (host is not Windows Server)'
    return
}
# Verify the actual runner's Server edition without ever servicing Windows.
$script:NativeFeatureCalls = 0
function Invoke-LanPowerDism {
    param([string[]]$Arguments)
    $script:NativeFeatureCalls++
    throw 'Server power-only verification must never call DISM.'
}
$result = Initialize-LanPowerUwfFeature
if ($result.Status -ne 'not-supported' -or $result.FeatureState -ne 'NotSupported' -or $result.RestartRequired -or $script:NativeFeatureCalls) {
    throw 'Actual Windows Server must successfully skip UWF without changing features or rebooting.'
}
$caption = (Get-CimInstance -ClassName Win32_OperatingSystem).Caption
Write-Output "SERVER_POWER_ONLY_PREPARATION=PASS ($caption; $edition; no DISM or reboot)"
