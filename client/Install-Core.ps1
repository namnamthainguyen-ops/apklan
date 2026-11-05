Set-StrictMode -Version Latest

function Get-LanPowerInstallPath {
    param([Parameter(Mandatory)][string]$Path)
    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $blocked = @([IO.Path]::GetPathRoot($fullPath), $env:ProgramData, $env:WINDIR,
        $env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:USERPROFILE, $env:TEMP)
    foreach ($entry in $blocked) {
        if ($entry -and $fullPath -ieq $entry.TrimEnd('\')) {
            throw 'InstallPath must be a dedicated agent directory, not a system or profile root.'
        }
    }
    $ancestor = $fullPath
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -LiteralPath $ancestor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'InstallPath and its ancestors must not be reparse points.'
            }
        }
        $parent = [IO.Path]::GetDirectoryName($ancestor)
        if ($parent -eq $ancestor) { break }
        $ancestor = $parent
    }
    if (Test-Path -LiteralPath $fullPath) {
        if (-not (Get-Item -LiteralPath $fullPath -Force).PSIsContainer) {
            throw 'InstallPath must be a directory.'
        }
        foreach ($item in @(Get-ChildItem -LiteralPath $fullPath -Force)) {
            if ($item.PSIsContainer -or $item.Name -notin @('StandaloneAgent.ps1', 'config.json') -or
                ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw 'InstallPath contains unrelated files or reparse points; choose a dedicated directory.'
            }
        }
    }
    return $fullPath
}

function Set-LanPowerDirectorySecurity {
    param([Parameter(Mandatory)][string]$Path)
    $administrators = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($administrators)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @($administrators, $system)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl',
            'ContainerInherit,ObjectInherit', 'None', 'Allow')
        [void]$acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    foreach ($item in @(Get-ChildItem -LiteralPath $Path -Force)) {
        $fileAcl = [Security.AccessControl.FileSecurity]::new()
        $fileAcl.SetOwner($administrators)
        $fileAcl.SetAccessRuleProtection($true, $false)
        foreach ($sid in @($administrators, $system)) {
            [void]$fileAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow'))
        }
        Set-Acl -LiteralPath $item.FullName -AclObject $fileAcl
    }
}

function Get-LanPowerFirewallRuleName {
    param([string]$TaskName, [int]$Port)
    if ($TaskName -eq 'LanPowerStandaloneAgent' -and $Port -eq 17840) {
        return 'LanPower Standalone Agent UDP 17840'
    }
    return "LanPower Standalone Agent $TaskName UDP $Port"
}

function Invoke-LanPowerInstall {
    param(
        [Parameter(Mandatory)][string]$SourceAgent,
        [Parameter(Mandatory)][string]$ManagementKey,
        [Parameter(Mandatory)][string]$InstallPath,
        [ValidatePattern('^[A-Za-z0-9_.-]{1,100}$')][string]$TaskName = 'LanPowerStandaloneAgent',
        [ValidateRange(1024, 65535)][int]$Port = 17840
    )
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run PowerShell as Administrator.'
    }
    try { $keyBytes = [Convert]::FromBase64String($ManagementKey) }
    catch { throw 'Management Key is not valid Base64.' }
    if ($keyBytes.Length -ne 32) { throw 'Management Key must contain exactly 32 bytes.' }
    if (-not (Test-Path -LiteralPath $SourceAgent -PathType Leaf)) { throw 'StandaloneAgent.ps1 was not found.' }
    $InstallPath = Get-LanPowerInstallPath $InstallPath
    New-Item -ItemType Directory -Path $InstallPath -Force | Out-Null
    Set-LanPowerDirectorySecurity $InstallPath
    $configPath = Join-Path $InstallPath 'config.json'
    $deviceId = [Guid]::NewGuid().ToString()
    if (Test-Path -LiteralPath $configPath) {
        $previous = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        $parsedId = [Guid]::Empty
        if (-not [Guid]::TryParse([string]$previous.deviceId, [ref]$parsedId)) {
            throw 'Existing config.json has an invalid deviceId; preserve it and investigate before reinstalling.'
        }
        $deviceId = $parsedId.ToString()
    }
    $existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($existingTask) {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction Stop
        for ($attempt = 0; $attempt -lt 50; $attempt++) {
            if ((Get-ScheduledTask -TaskName $TaskName).State -ne 'Running') { break }
            Start-Sleep -Milliseconds 100
        }
        if ((Get-ScheduledTask -TaskName $TaskName).State -eq 'Running') { throw 'Existing agent did not stop.' }
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    }
    Copy-Item -LiteralPath $SourceAgent -Destination (Join-Path $InstallPath 'StandaloneAgent.ps1') -Force
    [ordered]@{
        deviceId = $deviceId
        managementKey = $ManagementKey
        port = $Port
        installedAt = [DateTime]::UtcNow.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding ASCII
    Set-LanPowerDirectorySecurity $InstallPath
    $agentPath = Join-Path $InstallPath 'StandaloneAgent.ps1'
    $arguments = ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}"' -f $agentPath)
    $powershellPath = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $action = New-ScheduledTaskAction -Execute $powershellPath -Argument $arguments
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 1)
    $taskPrincipal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $taskPrincipal -Force | Out-Null
    $ruleName = Get-LanPowerFirewallRuleName $TaskName $Port
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol UDP -LocalPort $Port -RemoteAddress LocalSubnet -Action Allow -Program $powershellPath | Out-Null
    Start-ScheduledTask -TaskName $TaskName
    Write-Host "Standalone agent installed. UDP $Port is open to LocalSubnet." -ForegroundColor Green
}
