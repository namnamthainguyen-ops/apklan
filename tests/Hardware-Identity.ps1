$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent $PSScriptRoot
$functions = @()
foreach ($relative in @('client/StandaloneAgent.ps1', 'client/Install-Core.ps1')) {
    $ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $repo $relative), [ref]$null, [ref]$null)
    $functions += $ast.Find({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-LanPowerHardwareDeviceId' }, $true).Extent.Text
}
if ($functions[0] -cne $functions[1]) { throw 'Installer/runtime identity algorithms differ.' }
Invoke-Expression $functions[0]
# Only the production pure identity function runs; all WMI is replaced with fixtures.
$script:Serial = 'HP-TEST-001'
$script:Uuid = '12345678-1234-4234-9234-123456789012'
$script:Mac = '02:11:22:33:44:55'
function Get-CimInstance {
    param($ClassName, $ErrorAction)
    if ($ClassName -eq 'Win32_ComputerSystemProduct') { return [pscustomobject]@{ UUID = $script:Uuid; IdentifyingNumber = $script:Serial } }
    if ($ClassName -eq 'Win32_NetworkAdapter') { return [pscustomobject]@{ PhysicalAdapter = $true; MACAddress = $script:Mac } }
    throw 'Unexpected WMI request'
}
$first = Get-LanPowerHardwareDeviceId
if ($first -cne (Get-LanPowerHardwareDeviceId)) { throw 'Identity changed across boots.' }
$script:Mac = '02:11:22:33:44:66'
if ($first -cne (Get-LanPowerHardwareDeviceId)) { throw 'A new NIC changed a valid SMBIOS identity.' }
$script:Serial = 'HP-TEST-002'
if ($first -ceq (Get-LanPowerHardwareDeviceId)) { throw 'Ghost clones on different hardware collided.' }
$script:Serial = 'DEFAULT STRING'
$fallback = Get-LanPowerHardwareDeviceId
$script:Mac = '02:11:22:33:44:77'
if ($fallback -ceq (Get-LanPowerHardwareDeviceId)) { throw 'Physical MAC fallback collided.' }
$script:Mac = ''
$rejected = $false
try { Get-LanPowerHardwareDeviceId | Out-Null } catch { $rejected = $_.Exception.Message -like 'DEVICE_IDENTITY_UNAVAILABLE*' }
if (-not $rejected) { throw 'Missing identity silently reused a cloned ID.' }
Write-Output 'HARDWARE_IDENTITY=PASS (stable boot, distinct ghost hardware, NIC change, MAC fallback, fail closed; no OS changes)'
