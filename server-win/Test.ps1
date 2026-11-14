$ErrorActionPreference='Stop'
$compiler=Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
$exe=Join-Path ([IO.Path]::GetTempPath()) ('RoomTN20-Smoke-'+[Guid]::NewGuid().ToString('N')+'.exe')
try {
    $sources=@('Program.cs','ConsoleWindow.cs','Smoke.cs') | ForEach-Object { Join-Path $PSScriptRoot $_ }
    & $compiler /nologo /target:exe /main:ServerSmoke /codepage:65001 "/out:$exe" /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Web.Extensions.dll /r:System.Security.dll $sources
    if($LASTEXITCODE){throw 'Smoke build failed.'}
    & $exe
    if($LASTEXITCODE){throw 'Smoke test failed.'}
}
finally { if(Test-Path -LiteralPath $exe){Remove-Item -LiteralPath $exe} }
