$ErrorActionPreference = 'Stop'
$tokens = $null
$parseErrors = $null
$source = Join-Path (Split-Path -Parent $PSScriptRoot) 'client\Install-Core.ps1'
$ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Installer core did not parse.' }
$functions = @($ast.FindAll({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-LanPowerUwfPreparation'
}, $true))
if ($functions.Count -ne 1) { throw 'Expected one automatic UWF preparation helper.' }
. ([scriptblock]::Create($functions[0].Extent.Text))
$script:PreparationCalls = 0
$script:PreparationFails = $false
function Initialize-LanPowerUwfFeature {
    $script:PreparationCalls++
    if ($script:PreparationFails) { throw 'TEST_UNSUPPORTED_WINDOWS_FEATURE' }
}
Invoke-LanPowerUwfPreparation
if ($script:PreparationCalls -ne 1) { throw 'A normal install must prepare UWF automatically.' }
Invoke-LanPowerUwfPreparation -SkipUwfPreparation
if ($script:PreparationCalls -ne 1) { throw 'CI skip must not enable real Windows features.' }
$script:PreparationFails = $true
$preparationWarnings = @(Invoke-LanPowerUwfPreparation 3>&1)
if ($script:PreparationCalls -ne 2 -or -not ($preparationWarnings -join '').Contains('TEST_UNSUPPORTED_WINDOWS_FEATURE')) {
    throw 'Unsupported UWF must produce a visible warning while preserving the installed power agent.'
}
Write-Output 'AUTOMATIC_UWF_PREPARATION=PASS (normal install prepares, CI skips, unsupported feature warns; no DISM or reboot executed)'

# Load the production preparation policy and parser while replacing every OS
# query/servicing boundary. Nothing below invokes DISM or enables any feature.
foreach ($name in @('Get-LanPowerUwfFeatureInfo', 'Get-LanPowerDismFailure', 'Initialize-LanPowerUwfFeature')) {
    $found = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true))
    if ($found.Count -ne 1) { throw "Expected one production UWF helper: $name" }
    . ([scriptblock]::Create($found[0].Extent.Text))
}
function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Assertion failed: $Message" }
}
function Assert-Failure {
    param([scriptblock]$Action, [string]$Expected)
    $message = ''
    try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
    Assert-True ($message.Contains($Expected)) "Expected failure $Expected; got '$message'."
}
$script:NativeCalls = [Collections.Generic.List[object]]::new()
$script:NativeResults = [Collections.Generic.Queue[object]]::new()
$script:Edition = 'IoTEnterpriseS'
$script:GuardBlocked = $false
$script:HasInstalledConfig = $false
function Test-LanPowerAdministrator { return $true }
function Get-LanPowerWindowsEdition { return $script:Edition }
function Test-LanPowerUwfRuntime { return $script:RuntimeReady }
function Assert-LanPowerWritableSystem { if ($script:GuardBlocked) { throw 'UWF_ACTIVE_DISABLE_AND_RESTART' } }
function Test-Path { param([string]$LiteralPath, [string]$PathType) return $script:HasInstalledConfig }
function Get-LanPowerInstallPath { param([string]$Path) return $Path }
function Get-WindowsOptionalFeature { throw 'Unsafe test: DISM PowerShell module must not be required by the hosted installer.' }
function Start-Process { throw 'Unsafe test: preparation must use the full native DISM process wrapper.' }
function Restart-Computer { throw 'Unsafe test: preparation must never restart Windows.' }
function Invoke-LanPowerDism {
    param([string[]]$Arguments)
    $script:NativeCalls.Add(@($Arguments))
    Assert-True ('/English' -in $Arguments) 'Native feature output must use a stable English parser.'
    Assert-True ('/Online' -in $Arguments) 'Native feature queries must target the running Windows image.'
    $allowed = @('/English', '/Online', '/Get-FeatureInfo', '/FeatureName:Client-UnifiedWriteFilter', '/Enable-Feature', '/All', '/NoRestart')
    Assert-True (@($Arguments | Where-Object { $_ -notin $allowed -and $_ -notlike '/LogPath:*' }).Count -eq 0) 'Feature preparation must only query/install UWF, never protect a volume or reboot.'
    if ('/Enable-Feature' -in $Arguments) {
        Assert-True ('/NoRestart' -in $Arguments -and '/All' -in $Arguments) 'Enable operation must include dependency preparation and suppress reboot.'
        Assert-True (@($Arguments | Where-Object { $_ -like '/FeatureName:*' }).Count -eq 1) 'Prepare only the UWF child feature; /All handles its parent.'
    }
    if ($script:NativeResults.Count -eq 0) { throw 'Unexpected native DISM call.' }
    return $script:NativeResults.Dequeue()
}
function Reset-Fixture {
    $script:NativeCalls.Clear()
    $script:NativeResults.Clear()
    $script:Edition = 'IoTEnterpriseS'
    $script:GuardBlocked = $false
    $script:HasInstalledConfig = $false
    $script:RuntimeReady = $true
}
function Add-NativeResult {
    param([string]$State, [int]$ExitCode = 0)
    $script:NativeResults.Enqueue([pscustomobject]@{ ExitCode = $ExitCode; Output = "Deployment Image Servicing and Management tool`r`nFeature Name : Client-UnifiedWriteFilter`r`nState : $State`r`n" })
}

Reset-Fixture
Add-NativeResult 'Enabled'
$result = Initialize-LanPowerUwfFeature
Assert-True ($result.Status -eq 'ready' -and -not $result.RestartRequired -and $result.Edition -eq 'IoTEnterpriseS') 'Already enabled UWF must succeed idempotently.'
Assert-True ($script:NativeCalls.Count -eq 1) 'Already enabled feature must not be installed again.'

Reset-Fixture
$script:RuntimeReady = $false
Add-NativeResult 'Enabled'
$result = Initialize-LanPowerUwfFeature
Assert-True ($result.RestartRequired -and $result.Status -eq 'restart-required') 'Installed feature without its runtime must not claim Freeze is ready.'

foreach ($pending in @('Enable Pending', 'Disable Pending')) {
    Reset-Fixture
    Add-NativeResult $pending
    $result = Initialize-LanPowerUwfFeature
    Assert-True ($result.Status -eq 'restart-required' -and $result.RestartRequired) 'Pending Windows servicing must request a manual restart.'
    Assert-True ($script:NativeCalls.Count -eq 1) 'Do not re-enable while a previous operation is pending.'
}

Reset-Fixture
$script:HasInstalledConfig = $true
Add-NativeResult 'Disabled'
Add-NativeResult '' 3010
Add-NativeResult 'Enable Pending'
$result = Initialize-LanPowerUwfFeature -InstallPath 'C:\ProgramData\LanPowerStandalone'
Assert-True ($result.Status -eq 'restart-required' -and $result.FeatureState -eq 'EnablePending' -and $result.RestartRequired) 'Exit3010 and verified pending state must be successful preparation, not a generic error.'
Assert-True ($result.LogPath -eq 'C:\ProgramData\LanPowerStandalone\uwf-preparation.log') 'Installed client diagnostics must be kept in the protected agent directory.'
Assert-True ($script:NativeCalls.Count -eq 3) 'Feature preparation must query, enable and verify state.'

Reset-Fixture
Add-NativeResult 'Disabled with Payload Removed'
Add-NativeResult ''
Add-NativeResult 'Enabled'
$result = Initialize-LanPowerUwfFeature
Assert-True ($result.Status -eq 'ready' -and -not $result.RestartRequired) 'Successful restored feature is ready after verification.'

Reset-Fixture
$script:Edition = 'Professional'
Assert-Failure { Initialize-LanPowerUwfFeature } 'UWF_UNSUPPORTED_EDITION'
Assert-True ($script:NativeCalls.Count -eq 0) 'Unsupported Windows edition must not attempt feature installation.'

Reset-Fixture
$script:GuardBlocked = $true
Assert-Failure { Initialize-LanPowerUwfFeature } 'UWF_ACTIVE_DISABLE_AND_RESTART'
Assert-True ($script:NativeCalls.Count -eq 0) 'Frozen C: must never be changed by feature preparation.'

Reset-Fixture
Add-NativeResult 'Disabled'
$missingSource = [BitConverter]::ToInt32([byte[]]@(0x1f, 0x08, 0x0f, 0x80), 0)
Add-NativeResult '' $missingSource
Assert-Failure { Initialize-LanPowerUwfFeature } '0x800F081F'
Assert-True ($script:NativeCalls.Count -eq 2) 'Missing component source must stop after failure and provide actionable diagnostics.'

Reset-Fixture
Add-NativeResult 'Disabled'
Add-NativeResult ''
Add-NativeResult 'Disabled'
Assert-Failure { Initialize-LanPowerUwfFeature } 'UWF_VERIFY_FAILED'

Reset-Fixture
Add-NativeResult 'Unexpected localized or unrecognized value'
Assert-Failure { Initialize-LanPowerUwfFeature } 'UWF_FEATURE_STATE_UNKNOWN'

Reset-Fixture
Add-NativeResult '' 5
Assert-Failure { Initialize-LanPowerUwfFeature } 'UWF_FEATURE_QUERY_FAILED'
Write-Output 'UWF_NATIVE_PREPARATION=PASS (hosted-module independence, enabled/pending idempotence, dependency install, native exit codes, post-verification, protected diagnostics, unsupported/source failures; OS calls stubbed)'
