param([Parameter(Mandatory)][string]$AgentPath, [Parameter(Mandatory)][string]$TestRoot)
$ErrorActionPreference = 'Stop'
$source = [IO.File]::ReadAllText($AgentPath)
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw 'Cannot parse production agent.' }
$replacements = @{
    'Initialize-LanPowerDesktopFallback' = 'function Initialize-LanPowerDesktopFallback { }'
    'Get-PrimaryMacAddress' = "function Get-PrimaryMacAddress { return '00:11:22:33:44:55' }"
    'Get-UwfState' = "function Get-UwfState { if (Get-Variable TestUwfState -Scope Script -ErrorAction SilentlyContinue) { return `$script:TestUwfState }; return 'OFF' }"
    'Get-UwfCapabilities' = "function Get-UwfCapabilities { return 'power,uwf,wolrelay' }"
    'Invoke-UwfCommand' = "function Invoke-UwfCommand { throw 'Unsafe test: native UWF execution was attempted.' }"
    'Invoke-UwfAction' = @'
function Invoke-UwfAction {
    param([string]$Action)
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'fail-uwf')) { throw 'TEST_UWF_FAILURE' }
    if ($Action -eq 'freeze') { $script:TestUwfState = 'OFF (next ON)' }
    elseif ($Action -eq 'unfreeze') { $script:TestUwfState = 'ON (next OFF)' }
    else { throw 'Unsupported stub action.' }
    [Console]::WriteLine("TEST_UWF_ACTION=$Action")
    return "UWF $Action scheduled. Restart required. $script:TestUwfState"
}
'@
    'Invoke-PowerAction' = 'function Invoke-PowerAction { param([string]$Action) Write-Output "TEST_POWER_ACTION=$Action" }'
    'Invoke-WakeRelay' = 'function Invoke-WakeRelay { param([string]$Mac) [Console]::WriteLine("TEST_WAKE_RELAY=$Mac"); return "Wake-on-LAN relayed." }'
    'Initialize-LanPowerRuntime' = 'function Initialize-LanPowerRuntime { param([object]$Config) $script:DisplayRuntime = [pscustomobject]@{}; $script:DisplayRuntime | Add-Member ScriptMethod Dispose {}; $script:DisplayRuntime | Add-Member ScriptMethod RunDesktopAction { param($action, $value) [Console]::WriteLine("TEST_DESKTOP_ACTION=$action"); return "Desktop action fixture." }; $script:TestLocked = $false; $script:RuntimeLastTry = [DateTime]::UtcNow; $script:RuntimeStatus = "ready"; $script:RuntimeMessage = "Fixture media receiver is ready." }'
    'Get-LanPowerRuntimeSnapshot' = 'function Get-LanPowerRuntimeSnapshot { return @{ lockState = $(if ($script:TestLocked) { "locked" } else { "unlocked" }); mediaVersion = "none"; mediaPort = 17842; mediaCertSha256 = ("A" * 64); available = $true } }'
    'Invoke-LanPowerSessionLock' = @'
function Invoke-LanPowerSessionLock {
    param([string]$Action)
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'fail-lock')) { throw 'TEST_LOCK_UI_UNAVAILABLE' }
    $script:TestLocked = $Action -eq 'lock'
    [Console]::WriteLine("TEST_SESSION_LOCK=$Action")
    return "Interactive display confirmed $Action."
}
'@
}
$edits = @()
foreach ($name in $replacements.Keys) {
    $matches = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true))
    if ($matches.Count -ne 1) { throw "Unsafe test harness: expected exactly one function $name." }
    $edits += [pscustomobject]@{ Start = $matches[0].Extent.StartOffset; Length = $matches[0].Extent.EndOffset - $matches[0].Extent.StartOffset; Value = $replacements[$name] }
}
foreach ($edit in ($edits | Sort-Object Start -Descending)) {
    $source = $source.Remove($edit.Start, $edit.Length).Insert($edit.Start, $edit.Value)
}
$source = $source.Replace("'Global\LanPowerStandaloneAgent'", ("'Local\LanPowerAgentTest-" + [Guid]::NewGuid().ToString('N') + "'"))
if (Test-Path -LiteralPath (Join-Path $TestRoot 'hardware-uuid')) {
    $fixture = @'
function Get-CimInstance {
    param($ClassName, $ErrorAction)
    if ($ClassName -ne 'Win32_ComputerSystemProduct') { throw 'Unexpected test WMI request' }
    return [pscustomobject]@{ UUID = '12345678-1234-4234-9234-123456789012'; IdentifyingNumber = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'hardware-uuid')) }
}
'@
    $source = $source.Insert($ast.ParamBlock.Extent.EndOffset, "`n" + $fixture + "`n")
}
if ($source -match 'Start-Process|shutdown\.exe|uwfmgr\.exe|dism\.exe|\[RoomTN20\.Runtime\.Controller\]') { throw 'Unsafe test harness: real OS/UI action remains.' }
$testAgent = Join-Path $TestRoot 'StandaloneAgent.ps1'
[IO.File]::WriteAllText($testAgent, $source, [Text.Encoding]::ASCII)
& $testAgent -DebugMode
