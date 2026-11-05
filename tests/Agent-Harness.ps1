param([Parameter(Mandatory)][string]$AgentPath, [Parameter(Mandatory)][string]$TestRoot)
$ErrorActionPreference = 'Stop'
$source = [IO.File]::ReadAllText($AgentPath)
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw 'Cannot parse production agent.' }
$replacements = @{
    'Get-PrimaryMacAddress' = "function Get-PrimaryMacAddress { return '00:11:22:33:44:55' }"
    'Get-UwfState' = "function Get-UwfState { return 'test' }"
    'Invoke-PowerAction' = 'function Invoke-PowerAction { param([string]$Action) Write-Output "TEST_POWER_ACTION=$Action" }'
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
if ($source -match 'Start-Process|shutdown\.exe|uwfmgr\.exe') { throw 'Unsafe test harness: real power executable remains.' }
$testAgent = Join-Path $TestRoot 'StandaloneAgent.ps1'
[IO.File]::WriteAllText($testAgent, $source, [Text.Encoding]::ASCII)
& $testAgent -DebugMode
