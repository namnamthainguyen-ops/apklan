$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'Media transfer smoke test is restricted to the disposable GitHub runner.' }
$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $repoRoot 'client\Install-Core.ps1')
$testRoot = Join-Path $env:TEMP ('RoomTN20MediaSmoke-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $cert = Join-Path $testRoot 'fixture.pfx'
    Initialize-LanPowerMediaCertificate -Path $cert -DeviceId ([Guid]::NewGuid().ToString())
    $runtime = Join-Path $repoRoot 'release\RoomTN20.Runtime-1.3.0.dll'
    Copy-Item -LiteralPath $runtime -Destination $testRoot
    $compiler = Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
    $exe = Join-Path $testRoot 'Media-Transfer-Smoke.exe'
    & $compiler /nologo /target:exe /langversion:5 ("/out:$exe") /reference:System.dll /reference:System.Core.dll /reference:System.Web.Extensions.dll ("/reference:$runtime") (Join-Path $PSScriptRoot 'Media-Transfer-Smoke.cs')
    if ($LASTEXITCODE -ne 0) { throw 'Media smoke test compilation failed.' }
    & $exe $testRoot $cert
    if ($LASTEXITCODE -ne 0) { throw 'Actual media transfer test failed.' }
}
finally {
    $full = [IO.Path]::GetFullPath($testRoot)
    if ((Split-Path -Parent $full) -ine [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') -or (Split-Path -Leaf $full) -notmatch '^RoomTN20MediaSmoke-[a-f0-9]{32}$') { throw 'Unsafe smoke cleanup.' }
    if (Test-Path -LiteralPath $full) { Remove-Item -LiteralPath $full -Force -Recurse }
}
