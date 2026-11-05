$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$sourcePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'client\StandaloneAgent.ps1'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($sourcePath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Production agent did not parse.' }
foreach ($functionName in @('Get-UwfState', 'Get-UwfCapabilities', 'Invoke-UwfAction')) {
    $definitions = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
    }, $true))
    if ($definitions.Count -ne 1) { throw "Expected one production function: $functionName" }
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}

# The production native-command helper is deliberately NOT imported. All OS
# reads and writes below are in-memory mocks, local to this test process.
function Reset-Scenario {
    param([bool]$CurrentFilter = $false, [bool]$NextFilter = $false,
        [bool]$CurrentC = $false, [bool]$NextC = $false, [int]$OverlayMB = 1024)
    $script:FilterConfig = [pscustomobject]@{ CurrentEnabled = $CurrentFilter; NextEnabled = $NextFilter }
    $script:CurrentC = $CurrentC
    $script:NextC = $NextC
    $script:NextD = $true
    $script:OverlayMB = $OverlayMB
    $script:ToolPresent = $true
    $script:QueryFails = $false
    $script:CommandFails = $false
    $script:IgnoreUpdates = $false
    $script:Commands = [Collections.Generic.List[string]]::new()
    $script:LastUwfCheck = [DateTime]::MinValue
    $script:UwfState = 'unknown'
    $script:UwfAvailable = $false
}

function Test-Path {
    param([string]$LiteralPath)
    if ($LiteralPath -notlike '*\uwfmgr.exe') { throw 'Unexpected filesystem access in UWF unit test.' }
    return $script:ToolPresent
}

function Get-CimInstance {
    param([string]$Namespace, [string]$ClassName, [string]$Filter, [string]$ErrorAction)
    if ($Namespace -ne 'root\standardcimv2\embedded') { throw 'Unexpected CIM namespace.' }
    if ($script:QueryFails) { throw 'Mock provider failure.' }
    switch ($ClassName) {
        'UWF_Filter' { return $script:FilterConfig }
        'UWF_Volume' {
            return @(
                [pscustomobject]@{ DriveLetter = 'C:'; CurrentSession = $true; Protected = $script:CurrentC },
                [pscustomobject]@{ DriveLetter = 'C:'; CurrentSession = $false; Protected = $script:NextC },
                [pscustomobject]@{ DriveLetter = 'D:'; CurrentSession = $true; Protected = $true },
                [pscustomobject]@{ DriveLetter = 'D:'; CurrentSession = $false; Protected = $script:NextD }
            )
        }
        'UWF_OverlayConfig' { return [pscustomobject]@{ MaximumSize = $script:OverlayMB } }
        default { throw "Unexpected CIM class: $ClassName" }
    }
}

function Invoke-UwfCommand {
    param([string]$Tool, [string[]]$Arguments)
    if ($Tool -notlike '*\uwfmgr.exe') { throw 'Unexpected tool in UWF test.' }
    $command = $Arguments -join ' '
    $script:Commands.Add($command)
    if ($script:CommandFails) { throw 'UWF_COMMAND_FAILED: mock nonzero native exit.' }
    if ($script:IgnoreUpdates) { return }
    switch ($command) {
        'volume protect C:' { $script:NextC = $true }
        'volume unprotect C:' { $script:NextC = $false }
        'filter enable' { $script:FilterConfig.NextEnabled = $true }
        'overlay set-size 1024' { $script:OverlayMB = 1024 }
        default { throw "Unexpected or out-of-scope UWF command: $command" }
    }
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -cne $Expected) { throw "$Message (expected: $Expected; actual: $Actual)" }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern)
    $observed = $null
    try { & $Action | Out-Null } catch { $observed = $_.Exception.Message }
    if (-not $observed -or $observed -notlike "*$Pattern*") { throw "Expected error containing $Pattern; received $observed" }
}

Reset-Scenario -CurrentFilter $true -NextFilter $true -NextC $true
Assert-Equal (Get-UwfState) 'OFF (next ON)' 'D: protection must not imply that C: is frozen.'
Assert-Equal (Get-UwfCapabilities) 'power,uwf' 'Available UWF provider advertises support.'

Reset-Scenario
Assert-Equal (Get-UwfState) 'OFF (next OFF)' 'Read initial C: status.'
$script:FilterConfig.CurrentEnabled = $true
$script:FilterConfig.NextEnabled = $true
$script:CurrentC = $true
$script:NextC = $true
$script:LastUwfCheck = [DateTime]::UtcNow.AddSeconds(-21)
Assert-Equal (Get-UwfState) 'ON (next ON)' 'External C: policy changes must become visible within the room refresh window.'

Reset-Scenario -CurrentC $true -NextC $true
Assert-Equal (Get-UwfState) 'OFF (next OFF)' 'Volume protection alone is ineffective when the filter is off.'

Reset-Scenario
$result = Invoke-UwfAction -Action freeze
Assert-Equal ($script:Commands -join '|') 'volume protect C:|filter enable' 'Freeze only protects C and enables the filter.'
Assert-Equal $script:CurrentC $false 'Freeze must not claim to change the current session.'
Assert-Equal $script:NextD $true 'Freeze preserves other-volume policy.'
Assert-Equal (Get-UwfState) 'OFF (next ON)' 'Freeze is pending until restart.'
if ($result -notlike '*Restart required*') { throw 'Freeze result must describe pending restart.' }

Reset-Scenario -CurrentFilter $true -NextFilter $true -CurrentC $true -NextC $true
[void](Invoke-UwfAction -Action unfreeze)
Assert-Equal ($script:Commands -join '|') 'volume unprotect C:' 'Unfreeze must not disable the global filter.'
Assert-Equal $script:FilterConfig.NextEnabled $true 'Other protected volumes retain their filter.'
Assert-Equal $script:NextD $true 'Unfreeze preserves D: protection.'
Assert-Equal (Get-UwfState) 'ON (next OFF)' 'Unfreeze is pending until restart.'

Reset-Scenario -CurrentFilter $true -NextFilter $true -CurrentC $true -NextC $true
[void](Invoke-UwfAction -Action freeze)
Assert-Equal $script:Commands.Count 0 'Already frozen configuration must be idempotent.'

Reset-Scenario -OverlayMB 128
[void](Invoke-UwfAction -Action freeze)
Assert-Equal $script:Commands[0] 'overlay set-size 1024' 'Initialize an unusably small overlay before enabling.'

Reset-Scenario -OverlayMB 2048
[void](Invoke-UwfAction -Action freeze)
Assert-Equal $script:OverlayMB 2048 'Preserve an existing administrator overlay policy.'

Reset-Scenario
$script:CommandFails = $true
Assert-Throws { Invoke-UwfAction -Action freeze } 'UWF_COMMAND_FAILED'
Assert-Equal $script:FilterConfig.NextEnabled $false 'A failed protect command must not enable the filter.'

Reset-Scenario
$script:IgnoreUpdates = $true
Assert-Throws { Invoke-UwfAction -Action freeze } 'UWF_VERIFY_FAILED'

Reset-Scenario
$script:ToolPresent = $false
Assert-Equal (Get-UwfCapabilities) 'power' 'Missing UWF keeps power management available.'
Assert-Throws { Invoke-UwfAction -Action freeze } 'UWF_NOT_INSTALLED'
Assert-Equal $script:Commands.Count 0 'Missing feature must not run any native command.'

Reset-Scenario
$script:QueryFails = $true
Assert-Equal (Get-UwfCapabilities) 'power' 'Unavailable provider must not advertise trusted UWF support.'
Assert-Throws { Invoke-UwfAction -Action unfreeze } 'UWF_NOT_INSTALLED'

Write-Output 'UWF_POLICY_UNIT_TEST=PASS (C-only, current/next, idempotency, overlay, failures; no real OS changes)'
