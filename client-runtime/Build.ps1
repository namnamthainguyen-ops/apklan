[CmdletBinding()]
param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repoRoot 'release' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$compiler = Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Build with Windows PowerShell 5.1 / .NET Framework compiler.' }
$shared = Join-Path $PSScriptRoot 'Shared.cs'
$runtime = Join-Path $OutputDirectory 'RoomTN20.Runtime-1.4.4.dll'
$player = Join-Path $OutputDirectory 'RoomTN20.Player-1.4.4.exe'
$references = @('/reference:System.dll', '/reference:System.Core.dll', '/reference:System.Web.Extensions.dll', '/reference:System.Security.dll')
& $compiler /nologo /target:library /platform:anycpu /optimize+ /langversion:5 /codepage:65001 ("/out:$runtime") @references $shared (Join-Path $PSScriptRoot 'Controller.cs')
if ($LASTEXITCODE -ne 0) { throw 'Runtime compilation failed.' }
$framework = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\WPF'
if (-not (Test-Path -LiteralPath $framework)) { $framework = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\WPF' }
$wpfReferences = @('/reference:System.Xaml.dll', '/reference:System.Windows.Forms.dll', '/reference:System.Drawing.dll')
foreach ($name in @('PresentationCore.dll', 'PresentationFramework.dll', 'WindowsBase.dll')) { $wpfReferences += '/reference:' + (Join-Path $framework $name) }
& $compiler /nologo /target:winexe /platform:anycpu /optimize+ /langversion:5 /codepage:65001 ("/out:$player") @references @wpfReferences ("/win32manifest:" + (Join-Path $PSScriptRoot 'player.manifest')) $shared (Join-Path $PSScriptRoot 'Player.cs')
if ($LASTEXITCODE -ne 0) { throw 'Player compilation failed.' }
Write-Output "RUNTIME_DLL=$runtime"
Write-Output "PLAYER_EXE=$player"
Write-Output "RUNTIME_SHA256=$((Get-FileHash -LiteralPath $runtime -Algorithm SHA256).Hash)"
Write-Output "PLAYER_SHA256=$((Get-FileHash -LiteralPath $player -Algorithm SHA256).Hash)"
