param([string]$TestRoot, [int]$Port, [string]$RoomKey, [int]$TimeoutSeconds = 2)
$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$source = Join-Path (Split-Path -Parent $PSScriptRoot) 'client\Install-Core.ps1'
$ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Installer core did not parse.' }
foreach ($name in @('ConvertTo-LanPowerManagementKey', 'Get-LanPowerHandshakeHmac', 'Test-LanPowerHandshakeSignature', 'Wait-LanPowerInstalledAgent')) {
    $found = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true))
    if ($found.Count -ne 1) { throw "Expected one production function $name." }
    . ([scriptblock]::Create($found[0].Extent.Text))
}
$result = Wait-LanPowerInstalledAgent -InstallPath $TestRoot -RoomKey $RoomKey -Port $Port -TimeoutSeconds $TimeoutSeconds
if (-not $result.Ready) { throw 'Readiness was not verified.' }
Write-Output 'INSTALLED_AGENT_READY=PASS'
