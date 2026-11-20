$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent $PSScriptRoot
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $repoRoot 'client\StandaloneAgent.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Agent syntax failed.' }
$definition = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-LanPowerClientUpdate' }, $true))
if ($definition.Count -ne 1) { throw 'Update command helper missing.' }
. ([scriptblock]::Create($definition[0].Extent.Text))
$AgentRoot = $PSScriptRoot
$script:started = 0
$script:taskState = 'Ready'
function Test-Path { param($LiteralPath, $PathType) return $LiteralPath -eq (Join-Path $AgentRoot 'Update-Agent.ps1') }
function Get-ScheduledTask {
    param($TaskName, $ErrorAction)
    if ($TaskName -ne 'FixtureUpdater') { throw 'Unexpected task name.' }
    return [pscustomobject]@{ State = $script:taskState }
}
function Start-ScheduledTask {
    param($TaskName, $ErrorAction)
    if ($TaskName -ne 'FixtureUpdater') { throw 'Unexpected task name.' }
    $script:started++
}
$config = [pscustomobject]@{ updateTaskName = 'FixtureUpdater' }
Invoke-LanPowerClientUpdate $config | Out-Null
if ($script:started -ne 1) { throw 'Ready updater did not start exactly once.' }
$script:taskState = 'Running'
Invoke-LanPowerClientUpdate $config | Out-Null
if ($script:started -ne 1) { throw 'Running updater restarted.' }
$rejected = $false
try { Invoke-LanPowerClientUpdate ([pscustomobject]@{}) } catch { $rejected = $true }
if (-not $rejected -or $script:started -ne 1) { throw 'Missing configuration started a task.' }
Write-Output 'UPDATE_COMMAND=PASS (production helper, ready/running/missing configuration, isolated task mocks)'
