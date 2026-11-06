$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent $PSScriptRoot
$workspace = [IO.Path]::GetFullPath((Join-Path $repoRoot '..'))
$testRoot = Join-Path $workspace ('atomic-write-' + [Guid]::NewGuid().ToString('N'))
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $repoRoot 'client\Install-Core.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Production installer core did not parse.' }
$functions = @($ast.FindAll({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Set-LanPowerAtomicFile'
}, $true))
if ($functions.Count -ne 1) { throw 'Expected one production atomic file writer.' }
. ([scriptblock]::Create($functions[0].Extent.Text))
try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $target = Join-Path $testRoot 'client config.json'
    foreach ($content in @('first installation', 'second installation', 'updated status')) {
        $bytes = [Text.Encoding]::UTF8.GetBytes($content)
        Set-LanPowerAtomicFile -Path $target -Bytes $bytes
        if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($target)) -cne [Convert]::ToBase64String($bytes)) {
            throw 'Atomic create/replace changed the file contents.'
        }
        if (@(Get-ChildItem -LiteralPath $testRoot -Force).Count -ne 1) { throw 'Atomic write left temporary or backup files.' }
    }
    Write-Output 'ATOMIC_FILE_WRITE=PASS (create, reinstall and update under Windows PowerShell 5.1)'
}
finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if ((Split-Path -Parent $resolved) -ine $workspace -or (Split-Path -Leaf $resolved) -notmatch '^atomic-write-[0-9a-f]{32}$') {
        throw 'Unsafe atomic-file test cleanup path.'
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
