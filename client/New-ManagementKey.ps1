[CmdletBinding()]
param()

$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$key = [Convert]::ToBase64String($bytes)
Write-Host 'Management Key:' -ForegroundColor Cyan
Write-Host $key
Write-Host 'Use this same key for the agents and Android app. Store it securely.' -ForegroundColor Yellow
