param([string]$OutputPath = (Join-Path $PSScriptRoot '..\release\RoomTN20-Server-1.0.0.exe'))
$ErrorActionPreference = 'Stop'
$compiler = Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
if (!(Test-Path -LiteralPath $compiler)) { throw 'Run this build with Windows PowerShell 5.1.' }
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
& $compiler /nologo /target:winexe /optimize+ /codepage:65001 "/out:$OutputPath" /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Web.Extensions.dll /r:System.Security.dll (Join-Path $PSScriptRoot 'Program.cs') (Join-Path $PSScriptRoot 'ConsoleWindow.cs')
if ($LASTEXITCODE) { throw 'Server build failed.' }
Get-FileHash -LiteralPath $OutputPath
