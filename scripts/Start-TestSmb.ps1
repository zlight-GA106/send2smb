param(
    [string]$Python = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Serial = "",
    [int]$Port = 1445
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot '.testenv'
$packages = Join-Path $testRoot 'python-packages'
$serverScript = Join-Path $PSScriptRoot 'smb_test_server.py'
$statePath = Join-Path $testRoot 'smb-server.json'
if (-not (Test-Path -LiteralPath $Python)) { throw "Python not found: $Python. Pass -Python with a Python 3 executable." }
if (-not (Test-Path -LiteralPath $Adb)) { throw "adb not found: $Adb" }
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
    @{ processId = $serverProcess.Id; startTimeUtc = $serverProcess.StartTime.ToUniversalTime().ToString('o'); port = $Port; serial = $Serial; python = $Python } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
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
    $adbArgs = @()
    if ($Serial) { $adbArgs += @('-s', $Serial) }
    & $Adb @adbArgs reverse "tcp:$Port" "tcp:$Port"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create adb reverse forwarding.' }
    Write-Output "Test SMB ready: smb://127.0.0.1:$Port/TESTSHARE"
    Write-Output 'Test username: android-test'
    Write-Output 'Test password: SendToSMB-test-only!'
    Write-Output "Fixture files: $(Join-Path $testRoot 'share')"
} catch {
    if ($serverProcess -and -not $serverProcess.HasExited) { Stop-Process -Id $serverProcess.Id }
    if (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
    throw
} finally {
    $env:PYTHONPATH = $oldPythonPath
}
