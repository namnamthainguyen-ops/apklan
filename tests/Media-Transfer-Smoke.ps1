$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'Media transfer smoke test is restricted to the disposable GitHub runner.' }
$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $repoRoot 'client\Install-Core.ps1')
$testRoot = Join-Path $env:TEMP ('RoomTN20MediaSmoke-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $cert = Join-Path $testRoot 'fixture.pfx'
    Initialize-LanPowerMediaCertificate -Path $cert -DeviceId ([Guid]::NewGuid().ToString())
    $runtime = Join-Path $repoRoot 'release\RoomTN20.Runtime-1.3.2.dll'
    Copy-Item -LiteralPath $runtime -Destination $testRoot
    $compiler = Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
    $exe = Join-Path $testRoot 'Media-Transfer-Smoke.exe'
    & $compiler /nologo /target:exe /langversion:5 ("/out:$exe") /reference:System.dll /reference:System.Core.dll /reference:System.Web.Extensions.dll ("/reference:$runtime") (Join-Path $PSScriptRoot 'Media-Transfer-Smoke.cs')
    if ($LASTEXITCODE -ne 0) { throw 'Media smoke test compilation failed.' }
    & $exe $testRoot $cert
    if ($LASTEXITCODE -ne 0) { throw 'Actual media transfer test failed.' }
    # Exercise the real PowerShell agent as SYSTEM as well as the DLL client/server.
    $agentRoot = Join-Path $testRoot 'Agent'
    $displayRoot = Join-Path $agentRoot 'Display'
    $dataRoot = Join-Path $testRoot 'AgentData'
    New-Item -ItemType Directory -Path $displayRoot, $dataRoot -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot 'client\StandaloneAgent.ps1') -Destination $agentRoot
    Copy-Item -LiteralPath $runtime -Destination (Join-Path $agentRoot 'RoomTN20.Runtime.dll')
    Copy-Item -LiteralPath $cert -Destination (Join-Path $agentRoot 'media-certificate.pfx')
    [IO.File]::WriteAllText((Join-Path $displayRoot 'RoomTN20.Player.exe'), 'Not an executable program; cannot show UI.')
    $probe = [Net.Sockets.UdpClient]::new(0)
    $udpPort = ([Net.IPEndPoint]$probe.Client.LocalEndPoint).Port
    $probe.Dispose()
    $fixtureKey = [Convert]::ToBase64String((New-Object byte[] 32))
    $config = @{ deviceId = [Guid]::NewGuid().ToString(); managementKey = $fixtureKey; port = $udpPort;
        runtimeDataRoot = $dataRoot; runtimePort = 17842; version = '1.3.2'; versionCode = 12 } | ConvertTo-Json
    Set-LanPowerAtomicFile (Join-Path $agentRoot 'config.json') ([Text.Encoding]::ASCII.GetBytes($config))
    Set-LanPowerDirectorySecurity $agentRoot
    $taskName = 'RoomTN20MediaSmoke-' + [Guid]::NewGuid().ToString('N')
    try {
        $powershell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $action = New-ScheduledTaskAction -Execute $powershell -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}"' -f (Join-Path $agentRoot 'StandaloneAgent.ps1'))
        $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
        Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Force | Out-Null
        Start-ScheduledTask -TaskName $taskName
        [void](Wait-LanPowerInstalledAgent -InstallPath $agentRoot -ManagementKey $fixtureKey -Port $udpPort -RequireMedia -ExpectedVersion '1.3.2' -ExpectedVersionCode 12)
        $agentProcess = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -like ('*' + (Join-Path $agentRoot 'StandaloneAgent.ps1') + '*') } | Select-Object -First 1
        if (-not $agentProcess) { throw 'SYSTEM agent process was not found.' }
        $probeExe = Join-Path $testRoot 'Pipe-Access-Test.exe'
        & $compiler /nologo /target:exe /langversion:5 ("/out:$probeExe") /reference:System.dll /reference:System.Core.dll /reference:System.Web.Extensions.dll (Join-Path $repoRoot 'client-runtime\Shared.cs') (Join-Path $repoRoot 'client-runtime\Pipe-Access-Test.cs')
        if ($LASTEXITCODE -ne 0) { throw 'Standard-user identity probe compilation failed.' }
        & $probeExe --system-process $agentProcess.ProcessId
        if ($LASTEXITCODE -ne 0) { throw 'Standard-user SYSTEM identity validation failed.' }
        Write-Output 'SYSTEM_MEDIA_RECEIVER_READY=PASS (real agent/SYSTEM, signed receiver readiness; inert player fixture, no UI or power/UWF commands)'
    }
    finally {
        Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    }
}
finally {
    $full = [IO.Path]::GetFullPath($testRoot)
    if ((Split-Path -Parent $full) -ine [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') -or (Split-Path -Leaf $full) -notmatch '^RoomTN20MediaSmoke-[a-f0-9]{32}$') { throw 'Unsafe smoke cleanup.' }
    if (Test-Path -LiteralPath $full) { Remove-Item -LiteralPath $full -Force -Recurse }
}
