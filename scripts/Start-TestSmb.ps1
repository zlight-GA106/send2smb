param(
    [string]$Python = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Serial = "",
    [int]$Port = 1445,
    [switch]$HostOnly,
    [string]$Packages = ""
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot '.testenv'
$packages = if ($Packages) { [System.IO.Path]::GetFullPath($Packages) } else { Join-Path $testRoot 'python-packages' }
$serverScript = Join-Path $PSScriptRoot 'smb_test_server.py'
$statePath = Join-Path $testRoot 'smb-server.json'
if (-not (Test-Path -LiteralPath $Python)) { throw "Python not found: $Python. Pass -Python with a Python 3 executable." }
if (-not $HostOnly -and -not (Test-Path -LiteralPath $Adb)) { throw "adb not found: $Adb. Use -HostOnly for local JVM tests without a device." }
if (Test-Path -LiteralPath $statePath) { throw 'A test server state exists. Run Stop-TestSmb.ps1 first.' }
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $packages 'impacket'))) {
    & $Python -m pip install --disable-pip-version-check --target $packages 'impacket==0.13.0'
    if ($LASTEXITCODE -ne 0) { throw 'Could not install isolated SMB test dependencies.' }
}
$oldPythonPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = $packages
    $serverProcess = Start-Process -FilePath $Python -ArgumentList @('-u', ('"' + $serverScript + '"'), '--port', $Port) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $testRoot 'smb-stdout.log') -RedirectStandardError (Join-Path $testRoot 'smb-stderr.log') -PassThru
    $savedStartTime = $serverProcess.StartTime.ToUniversalTime()
    $pythonPath = $serverProcess.Path
    @{ processId = $serverProcess.Id; startTimeUtc = $savedStartTime.ToString('o'); port = $Port; serial = $Serial; python = $pythonPath; hostOnly = [bool]$HostOnly; reverseConfigured = $false } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 250
        $serverProcess.Refresh()
        if ($serverProcess.HasExited) { throw ('SMB fixture exited: ' + (Get-Content -LiteralPath (Join-Path $testRoot 'smb-stderr.log') -Raw)) }
        if ((Get-Content -LiteralPath (Join-Path $testRoot 'smb-stdout.log') -Raw) -match 'READY ') { $ready = $true; break }
    }
    if (-not $ready) { throw 'SMB fixture did not become ready within 10 seconds.' }
    & $Python $serverScript --port $Port --smoke-test
    if ($LASTEXITCODE -ne 0) { throw 'Host SMB smoke test failed.' }
    if (-not $HostOnly) {
        $adbArgs = @()
        if ($Serial) { $adbArgs += @('-s', $Serial) }
        & $Adb @adbArgs reverse "tcp:$Port" "tcp:$Port"
        if ($LASTEXITCODE -ne 0) { throw 'Could not create adb reverse forwarding.' }
        @{ processId = $serverProcess.Id; startTimeUtc = $savedStartTime.ToString('o'); port = $Port; serial = $Serial; python = $pythonPath; hostOnly = $false; reverseConfigured = $true } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    }
    Write-Output "Test SMB ready: smb://127.0.0.1:$Port/TESTSHARE"
    Write-Output 'Test username: android-test'
    Write-Output 'Test password: SendToSMB-test-only!'
    Write-Output "Fixture files: $(Join-Path $testRoot 'share')"
} catch {
    if ($serverProcess) {
        $currentProcess = Get-Process -Id $serverProcess.Id -ErrorAction SilentlyContinue
        if ($currentProcess -and $currentProcess.Path -eq $pythonPath -and
            $currentProcess.StartTime.ToUniversalTime().Ticks -eq $savedStartTime.Ticks) {
            Stop-Process -Id $currentProcess.Id
        }
    }
    if (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
    throw
} finally {
    $env:PYTHONPATH = $oldPythonPath
}
