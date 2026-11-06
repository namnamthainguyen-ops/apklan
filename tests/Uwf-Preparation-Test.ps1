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
