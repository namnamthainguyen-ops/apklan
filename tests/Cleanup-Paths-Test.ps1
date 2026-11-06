$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Cleanup-Paths.ps1')
$leaf = 'LanPowerPackaged-0123456789abcdef0123456789abcdef'
$expected = "C:\Temp\$leaf"
foreach ($actual in @("C:/Temp/$leaf", "c:\TEMP\$leaf\", "C:\Temp\unused\..\$leaf")) {
    $resolved = Resolve-TestCleanupPath -ActualPath $actual -ExpectedPath $expected
    if (-not [string]::Equals($resolved, $expected, [StringComparison]::OrdinalIgnoreCase)) { throw 'Equivalent cleanup path did not normalize.' }
}
foreach ($actual in @('C:\', 'C:\Temp', "$expected-other")) {
    $failed = $false
    try { Resolve-TestCleanupPath -ActualPath $actual -ExpectedPath $expected | Out-Null }
    catch {
        $failed = $true
        if ($_.Exception.Message -notmatch 'Actual=.*Expected=') { throw 'Mismatch diagnostic must include both paths.' }
    }
    if (-not $failed) { throw 'Unsafe or unrelated cleanup target was accepted.' }
}
$failed = $false
try { Resolve-TestCleanupPath -ActualPath 'C:\' -ExpectedPath 'c:/' | Out-Null } catch { $failed = $true }
if (-not $failed) { throw 'A filesystem root cannot be a cleanup target.' }
Write-Output 'CLEANUP_PATH_COMPARISON=PASS (mixed separators, casing, dot segments; roots and unrelated targets rejected)'
