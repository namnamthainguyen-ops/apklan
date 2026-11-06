function Resolve-TestCleanupPath {
    param([Parameter(Mandatory)][string]$ActualPath, [Parameter(Mandatory)][string]$ExpectedPath)
    $separators = [char[]]@('\', '/')
    $actual = [IO.Path]::GetFullPath($ActualPath).TrimEnd($separators)
    $expected = [IO.Path]::GetFullPath($ExpectedPath).TrimEnd($separators)
    $root = [IO.Path]::GetPathRoot([IO.Path]::GetFullPath($ExpectedPath)).TrimEnd($separators)
    if ($expected -eq $root -or -not [string]::Equals($actual, $expected, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unexpected cleanup path. Actual='$actual'; Expected='$expected'."
    }
    return $actual
}

function Assert-TestCleanupNotReparse {
    param([Parameter(Mandatory)][string]$Path)
    $ancestor = [IO.Path]::GetFullPath($Path)
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -LiteralPath $ancestor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Cleanup path or ancestor is a reparse point: '$ancestor'."
            }
        }
        $parent = [IO.Path]::GetDirectoryName($ancestor)
        if ($parent -eq $ancestor) { break }
        $ancestor = $parent
    }
}
