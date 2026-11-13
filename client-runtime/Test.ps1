[CmdletBinding()]
param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repoRoot 'release' }
$runtime = Join-Path $OutputDirectory 'RoomTN20.Runtime-1.4.2.dll'
$compiler = Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
$testDirectory = Join-Path (Join-Path $repoRoot '..') ('runtime-unit-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDirectory | Out-Null
try {
    Copy-Item -LiteralPath $runtime -Destination (Join-Path $testDirectory 'RoomTN20.Runtime-1.4.2.dll')
    $testExe = Join-Path $testDirectory 'Runtime-Unit.exe'
    & $compiler /nologo /target:exe /platform:anycpu /langversion:5 ("/out:$testExe") /reference:System.dll /reference:System.Core.dll /reference:System.Web.Extensions.dll ("/reference:$runtime") (Join-Path $PSScriptRoot 'Runtime-Unit.cs')
    if ($LASTEXITCODE -ne 0) { throw 'Runtime unit test compilation failed.' }
    & $testExe
    if ($LASTEXITCODE -ne 0) { throw 'Runtime unit tests failed.' }
    $pipeTest = Join-Path $testDirectory 'Pipe-Access-Test.exe'
    & $compiler /nologo /target:exe /langversion:5 ("/out:$pipeTest") /reference:System.dll /reference:System.Core.dll /reference:System.Web.Extensions.dll (Join-Path $PSScriptRoot 'Shared.cs') (Join-Path $PSScriptRoot 'Pipe-Access-Test.cs')
    if ($LASTEXITCODE -ne 0) { throw 'Pipe access test compilation failed.' }
    & $pipeTest
    if ($LASTEXITCODE -ne 0) { throw 'Standard-user pipe access test failed.' }
    $player = Join-Path $OutputDirectory 'RoomTN20.Player-1.4.2.exe'
    $process = Start-Process -FilePath $player -ArgumentList '--self-test' -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw 'Player pure self-test failed.' }
    Write-Output 'PLAYER_SELF_TEST=PASS (no window, lock, media listener or OS changes)'
}
finally {
    $resolved = [IO.Path]::GetFullPath($testDirectory)
    if ((Split-Path -Parent $resolved) -ine [IO.Path]::GetFullPath((Join-Path $repoRoot '..')) -or (Split-Path -Leaf $resolved) -notmatch '^runtime-unit-[0-9a-f]{32}$') { throw 'Unsafe runtime test cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
