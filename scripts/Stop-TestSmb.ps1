param([string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$statePath = Join-Path $projectRoot '.testenv\smb-server.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Output 'No test server state found.'; return }
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$serverProcess = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
if ($serverProcess) {
    $savedStartTime = ([datetime]$state.startTimeUtc).ToUniversalTime()
    if ($serverProcess.StartTime.ToUniversalTime().Ticks -ne $savedStartTime.Ticks -or $serverProcess.Path -ne $state.python) {
        throw 'Saved process identity does not match; refusing to stop another process.'
    }
    Stop-Process -Id $serverProcess.Id
}
$adbArgs = @()
if ($state.serial) { $adbArgs += @('-s', $state.serial) }
& $Adb @adbArgs reverse --remove "tcp:$($state.port)"
Remove-Item -LiteralPath $statePath
Write-Output 'Test SMB server stopped. Test fixture files were retained in .testenv/share.'
