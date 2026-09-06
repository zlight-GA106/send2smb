[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest')
)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$javaExecutable = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) {
    throw "JDK not found: $JavaHome. Supply -JavaHome pointing to a JDK 17 or newer."
}
$socketTemp = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '.testenv\java-sockets'))
if ([System.Text.Encoding]::UTF8.GetByteCount($socketTemp) -gt 80) {
    $socketTemp = [System.IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'SendToSMB\java-sockets'))
}
New-Item -ItemType Directory -Force -Path $socketTemp | Out-Null
$socketTemp = (Get-Item -LiteralPath $socketTemp).FullName.Replace('\', '/')
$previousJavaHome = $env:JAVA_HOME
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_HOME = $JavaHome
    # Windows AF_UNIX cannot connect using this host's default 8.3 TEMP path.
    # This process-scoped property reaches both Gradle's launcher and daemon.
    $temporaryJvmOption = '"-Djdk.net.unixdomain.tmpdir=' + $socketTemp + '"'
    $env:JAVA_TOOL_OPTIONS = ($previousJavaToolOptions + ' ' + $temporaryJvmOption).Trim()
    & $javaExecutable (Join-Path $PSScriptRoot 'LoopbackProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Java loopback/selector preflight failed.' }
    Push-Location -LiteralPath $projectRoot
    try {
        & (Join-Path $projectRoot 'gradlew.bat') @Tasks --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
}
