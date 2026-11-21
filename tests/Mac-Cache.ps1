param([string]$AgentPath)
$ErrorActionPreference = 'Stop'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($AgentPath, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Agent syntax error.' }
$found = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-PrimaryMacAddress' }, $true))
if ($found.Count -ne 1) { throw 'Expected the production MAC lookup function.' }
. ([scriptblock]::Create($found[0].Extent.Text))
$script:CachedMac = ''; $script:CachedMacTime = [DateTime]::MinValue; $script:Calls = 0
function Get-NetAdapter {
    param([switch]$Physical)
    $script:Calls++
    return [pscustomobject]@{ Status = 'Up'; ifIndex = 1; MacAddress = $(if ($script:Calls -eq 1) { '02-00-00-00-00-01' } else { '02-00-00-00-00-02' }) }
}
$first = Get-PrimaryMacAddress
for ($i = 0; $i -lt 20; $i++) {
    if ((Get-PrimaryMacAddress) -cne $first) { throw 'Cached MAC changed unexpectedly.' }
}
if ($script:Calls -ne 1 -or $first -cne '02:00:00:00:00:01') { throw 'Repeated probes queried the adapter again.' }
$script:CachedMacTime = [DateTime]::UtcNow.AddSeconds(-31)
if ((Get-PrimaryMacAddress) -cne '02:00:00:00:00:02' -or $script:Calls -ne 2) { throw 'Expired MAC did not refresh.' }
Write-Output 'MAC_CACHE=PASS (20 repeated lookups use cache; refresh after adapter change/expiry; no OS/network changes)'
