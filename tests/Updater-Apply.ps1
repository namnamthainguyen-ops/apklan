$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent $PSScriptRoot
$workspace = [IO.Path]::GetFullPath((Join-Path $repoRoot '..'))
$testRoot = Join-Path $workspace ('updater-policy-' + [Guid]::NewGuid().ToString('N'))
$productionPath = Join-Path $repoRoot 'client\Update-Agent.ps1'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($productionPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Production updater did not parse.' }
$body = @()
foreach ($name in @('Test-RoomTN20DownloadUrl', 'Test-RoomTN20NewerVersion', 'Get-RoomTN20FileHash', 'Read-RoomTN20Manifest', 'Write-RoomTN20UpdateStatus', 'Invoke-RoomTN20Update')) {
    $functions = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true))
    if ($functions.Count -ne 1) { throw "Expected one updater function: $name" }
    $functionSource = $functions[0].Extent.Text
    if ($name -eq 'Invoke-RoomTN20Update') {
        $secureDirectoryCall = '[void][IO.Directory]::CreateDirectory($staging, $security)'
        if ([regex]::Matches($functionSource, [regex]::Escape($secureDirectoryCall)).Count -ne 1) { throw 'Updater staging creation contract changed; review safe harness.' }
        # Production staging ACLs are verified by the real ephemeral-runner
        # installer test. This policy harness only creates its workspace folder.
        $functionSource = $functionSource.Replace($secureDirectoryCall, 'Assert-WithinHarness $staging; New-Item -ItemType Directory -Path $staging | Out-Null')
    }
    $body += $functionSource
}
$body += 'Invoke-RoomTN20Update'
$controlledCore = @'
function Get-LanPowerInstallPath { param([string]$Path) Assert-WithinHarness $Path; return $Path }
function Assert-LanPowerWritableSystem { if ($script:BlockUwf) { throw 'UWF_ACTIVE_DISABLE_AND_RESTART' } }
function Set-LanPowerDirectorySecurity { param([string]$Path) Assert-WithinHarness $Path }
function Set-LanPowerAtomicFile { param([string]$Path, [byte[]]$Bytes) Assert-WithinHarness $Path; [IO.File]::WriteAllBytes($Path, $Bytes) }
function Enter-LanPowerInstallLock {
    param([string]$Path)
    Assert-WithinHarness $Path
    $lock = [Threading.Mutex]::new($false, 'Local\RoomTN20UpdaterTest-' + [Guid]::NewGuid().ToString('N'))
    [void]$lock.WaitOne(0)
    return $lock
}
function Invoke-LanPowerInstall {
    param([string]$SourceAgent, [string]$SourceCore, [string]$SourceUpdater, [string]$SourcePublicKey,
        [string]$ManagementKey, [string]$InstallPath, [string]$TaskName, [int]$Port, [string[]]$VpnNetworks, [switch]$SkipUwfPreparation)
    Assert-WithinHarness $InstallPath
    $script:InstallCalls++
    if (-not $SkipUwfPreparation) { throw 'Automatic updates must not install Windows features or request reboots.' }
    if ($ManagementKey -cne $script:OriginalConfig.managementKey -or $TaskName -cne $script:OriginalConfig.agentTaskName -or
        $Port -ne $script:OriginalConfig.port -or ($VpnNetworks -join ',') -cne ($script:OriginalConfig.vpnNetworks -join ',')) {
        throw 'Updater did not preserve room identity or network configuration.'
    }
    if ($script:FailApply -and $SourceAgent -like '*RoomTN20Update-*') { throw 'TEST_APPLY_FAILURE' }
    foreach ($entry in @(@($SourceAgent, 'StandaloneAgent.ps1'), @($SourceCore, 'Install-Core.ps1'),
        @($SourceUpdater, 'Update-Agent.ps1'), @($SourcePublicKey, 'Update-PublicKey.xml'))) {
        if ($entry[0]) {
            Assert-WithinHarness $entry[0]
            $destination = Join-Path $InstallPath $entry[1]
            if ([IO.Path]::GetFullPath($entry[0]) -ine [IO.Path]::GetFullPath($destination)) {
                Copy-Item -LiteralPath $entry[0] -Destination $destination -Force
            }
        }
    }
    $config = Get-Content -LiteralPath (Join-Path $InstallPath 'config.json') -Raw | ConvertFrom-Json
    if ($SourceAgent -like '*RoomTN20Update-*') { $config.version = '1.2.1'; $config.versionCode = 6 }
    $config | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $InstallPath 'config.json') -Encoding ASCII
}
'@

function Assert-WithinHarness {
    param([string]$Path)
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($testRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Filesystem mutation escaped isolated updater test directory: $full"
    }
}
function Invoke-WebRequest {
    param([switch]$UseBasicParsing, [string]$Uri, [string]$OutFile, [int]$TimeoutSec, [int]$MaximumRedirection)
    if ($MaximumRedirection -ne 0 -or $TimeoutSec -lt 1) { throw 'Update requests must be bounded and reject redirects.' }
    $script:Downloads++
    if (-not $OutFile) { return [pscustomobject]@{ Content = $script:Envelope } }
    Assert-WithinHarness $OutFile
    $filename = [IO.Path]::GetFileName(([Uri]$Uri).AbsolutePath)
    if (-not $script:PayloadBytes.ContainsKey($filename)) { throw 'Unexpected mocked download.' }
    $bytes = if ($script:CorruptDownload) { [Text.Encoding]::ASCII.GetBytes('tampered') } else { $script:PayloadBytes[$filename] }
    [IO.File]::WriteAllBytes($OutFile, $bytes)
}
function Save-RoomTN20Download {
    param([string]$Url, [string]$Path, [int]$MaximumBytes)
    $script:Downloads++
    Assert-WithinHarness $Path
    $filename = [IO.Path]::GetFileName(([Uri]$Url).AbsolutePath)
    if (-not $script:PayloadBytes.ContainsKey($filename)) { throw 'Unexpected mocked download.' }
    $bytes = if ($script:CorruptDownload) { [Text.Encoding]::ASCII.GetBytes('tampered') } else { $script:PayloadBytes[$filename] }
    [IO.File]::WriteAllBytes($Path, $bytes)
}
function Stop-ScheduledTask {
    param([string]$TaskName, [string]$ErrorAction)
    if ($TaskName -cne $script:OriginalConfig.agentTaskName) { throw 'Updater tried to stop an unrelated task.' }
    $script:StoppedTasks++
}
function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Assertion failed: $Message" }
}
function New-ScenarioEnvelope {
    $prefix = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/client/'
    $metadata = @{}
    foreach ($entry in @(@('agent', 'StandaloneAgent.ps1'), @('core', 'Install-Core.ps1'), @('updater', 'Update-Agent.ps1'))) {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $hash = [BitConverter]::ToString($sha.ComputeHash($script:PayloadBytes[$entry[1]])).Replace('-', '') }
        finally { $sha.Dispose() }
        $metadata[$entry[0]] = @{ url = $prefix + $entry[1]; sha256 = $hash }
    }
    $payload = @{ schemaVersion = 1; channel = 'stable'; publishedUtc = [DateTime]::UtcNow.ToString('o');
        windows = @{ version = '1.2.1'; versionCode = $script:CandidateCode; agent = $metadata.agent; core = $metadata.core; updater = $metadata.updater } }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 8 -Compress))
    $signature = $rsa.SignData($bytes, [Security.Cryptography.CryptoConfig]::MapNameToOID('SHA256'))
    return @{ algorithm = 'RS256'; payload = [Convert]::ToBase64String($bytes); signature = [Convert]::ToBase64String($signature) } | ConvertTo-Json -Compress
}

$previousProgramData = $env:ProgramData
$rsa = [Security.Cryptography.RSACryptoServiceProvider]::new(3072)
$rsa.PersistKeyInCsp = $false
try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $env:ProgramData = Join-Path $testRoot 'programdata'
    New-Item -ItemType Directory -Path $env:ProgramData | Out-Null
    foreach ($scenario in @('success', 'protected-c', 'checksum', 'apply-failure', 'downgrade', 'signature')) {
        $scenarioRoot = Join-Path $testRoot $scenario
        New-Item -ItemType Directory -Path $scenarioRoot | Out-Null
        $script:OriginalConfig = [pscustomobject]@{ deviceId = [Guid]::NewGuid().ToString(); managementKey = [Convert]::ToBase64String((New-Object byte[] 32));
            port = 17940; vpnNetworks = @('100.64.10.0/24'); agentTaskName = 'RoomTN20IsolatedTest'; updateTaskName = 'RoomTN20IsolatedTestUpdater'; version = '1.2.0'; versionCode = 5 }
        $script:OriginalConfig | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $scenarioRoot 'config.json') -Encoding ASCII
        [IO.File]::WriteAllText((Join-Path $scenarioRoot 'Install-Core.ps1'), $controlledCore, [Text.Encoding]::ASCII)
        [IO.File]::WriteAllText((Join-Path $scenarioRoot 'Update-Agent.ps1'), ($body -join "`n`n"), [Text.Encoding]::ASCII)
        [IO.File]::WriteAllText((Join-Path $scenarioRoot 'StandaloneAgent.ps1'), 'old agent bytes', [Text.Encoding]::ASCII)
        [IO.File]::WriteAllText((Join-Path $scenarioRoot 'Update-PublicKey.xml'), $rsa.ToXmlString($false), [Text.Encoding]::ASCII)
        $script:BlockUwf = $scenario -eq 'protected-c'
        $script:CorruptDownload = $scenario -eq 'checksum'
        $script:FailApply = $scenario -eq 'apply-failure'
        $script:CandidateCode = if ($scenario -eq 'downgrade') { 4 } else { 6 }
        $script:InstallCalls = 0; $script:Downloads = 0; $script:StoppedTasks = 0
        $script:PayloadBytes = @{ 'StandaloneAgent.ps1' = [Text.Encoding]::ASCII.GetBytes('new signed agent bytes');
            'Install-Core.ps1' = [Text.Encoding]::ASCII.GetBytes($controlledCore); 'Update-Agent.ps1' = [Text.Encoding]::ASCII.GetBytes('new signed updater bytes') }
        $script:Envelope = New-ScenarioEnvelope
        if ($scenario -eq 'signature') {
            $bad = $script:Envelope | ConvertFrom-Json
            $bad.signature = [Convert]::ToBase64String((New-Object byte[] 384))
            $script:Envelope = $bad | ConvertTo-Json -Compress
        }
        . (Join-Path $scenarioRoot 'Update-Agent.ps1')
        $status = Get-Content -LiteralPath (Join-Path $scenarioRoot 'update-status.json') -Raw | ConvertFrom-Json
        $installed = Get-Content -LiteralPath (Join-Path $scenarioRoot 'config.json') -Raw | ConvertFrom-Json
        Assert-True ($installed.deviceId -ceq $script:OriginalConfig.deviceId -and $installed.managementKey -ceq $script:OriginalConfig.managementKey -and
            $installed.port -eq $script:OriginalConfig.port -and ($installed.vpnNetworks -join ',') -ceq ($script:OriginalConfig.vpnNetworks -join ',')) 'Update must preserve identity, key, port and VPN configuration.'
        switch ($scenario) {
            success {
                Assert-True ($status.status -eq 'current' -and $installed.versionCode -eq 6 -and $script:InstallCalls -eq 1) "Verified update should apply once. Status=$($status.status); Message=$($status.message); Calls=$script:InstallCalls"
                Assert-True ((Get-Content -LiteralPath (Join-Path $scenarioRoot 'StandaloneAgent.ps1') -Raw) -ceq 'new signed agent bytes') 'Only verified downloaded bytes should be installed.'
            }
            protected-c {
                Assert-True ($status.status -eq 'blocked-uwf' -and $script:InstallCalls -eq 0 -and $script:Downloads -eq 1) 'Protected C must skip downloads/install without unfreezing or rebooting.'
            }
            checksum { Assert-True ($status.status -eq 'error' -and $script:InstallCalls -eq 0) 'Payload checksum mismatch must never install.' }
            apply-failure {
                Assert-True ($status.status -eq 'error' -and $installed.versionCode -eq 5 -and $script:InstallCalls -eq 2 -and $script:StoppedTasks -eq 1) 'Failed update must restore prior files/config and reinstall the prior agent.'
                Assert-True ((Get-Content -LiteralPath (Join-Path $scenarioRoot 'StandaloneAgent.ps1') -Raw) -ceq 'old agent bytes') 'Rollback must restore original agent bytes.'
            }
            downgrade { Assert-True ($status.status -eq 'current' -and $script:InstallCalls -eq 0 -and $script:Downloads -eq 1) 'Older signed release must not install.' }
            signature { Assert-True ($status.status -eq 'error' -and $script:InstallCalls -eq 0 -and $script:Downloads -eq 1) 'Invalid signature must stop before payload downloads.' }
        }
        Assert-True (@(Get-ChildItem -LiteralPath $env:ProgramData -Force).Count -eq 0) 'Updater staging must be cleaned after every outcome.'
    }
    Write-Output 'SIGNED_UPDATER_APPLY=PASS (verified apply, preserved identity/key/VPN, protected C skip, checksum/signature rejection, downgrade refusal, rollback; isolated filesystem mocks only)'
}
finally {
    $env:ProgramData = $previousProgramData
    $rsa.Dispose()
    if (Test-Path -LiteralPath $testRoot) {
        $resolved = [IO.Path]::GetFullPath($testRoot)
        if ((Split-Path -Parent $resolved) -ine $workspace -or (Split-Path -Leaf $resolved) -notmatch '^updater-policy-[0-9a-f]{32}$') { throw 'Unsafe updater test cleanup.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
