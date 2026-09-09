param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Serial = 'b5b85793'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$out = Join-Path $projectRoot '.testenv'
$size = (& $Adb -s $Serial shell wm size) -join "`n"
$density = (& $Adb -s $Serial shell wm density) -join "`n"
$rotation = (& $Adb -s $Serial shell settings get system user_rotation).Trim()
$autoRotate = (& $Adb -s $Serial shell settings get system accelerometer_rotation).Trim()
@{ size = $size; density = $density; rotation = $rotation; autoRotate = $autoRotate } | ConvertTo-Json | Set-Content (Join-Path $out 'display-before.json')
try {
    & $Adb -s $Serial shell settings put system accelerometer_rotation 0
    $layouts = @(
        @{ name = 'phone-21x9'; size = '1080x2520'; density = '420'; rotation = '0' },
        @{ name = 'tablet-16x9'; size = '1080x1920'; density = '240'; rotation = '1' }
    )
    foreach ($layout in $layouts) {
        & $Adb -s $Serial shell wm size $layout.size
        & $Adb -s $Serial shell wm density $layout.density
        & $Adb -s $Serial shell settings put system user_rotation $layout.rotation
        $result = & $Adb -s $Serial shell am instrument -w -e class com.zlight.sendtosmb.ExplorerUiTest com.zlight.sendtosmb.test/androidx.test.runner.AndroidJUnitRunner
        $result | Set-Content (Join-Path $out ($layout.name + '-test.log'))
        if (($result -join "`n") -notmatch 'OK \(3 tests\)') { throw "Layout UI test failed for $($layout.name)" }
        & $Adb -s $Serial shell am start -n com.zlight.sendtosmb/.MainActivity
        Start-Sleep -Seconds 2
        & $Adb -s $Serial shell screencap -p /data/local/tmp/sendtosmb-layout.png
        & $Adb -s $Serial pull /data/local/tmp/sendtosmb-layout.png (Join-Path $out ($layout.name + '.png'))
        Write-Output "PASS $($layout.name): UI tests and screenshot saved"
    }
} finally {
    if ($size -match 'Override size: (\d+x\d+)') { & $Adb -s $Serial shell wm size $Matches[1] }
    else { & $Adb -s $Serial shell wm size reset }
    if ($density -match 'Override density: (\d+)') { & $Adb -s $Serial shell wm density $Matches[1] }
    else { & $Adb -s $Serial shell wm density reset }
    if ($rotation -eq 'null') { & $Adb -s $Serial shell settings delete system user_rotation }
    else { & $Adb -s $Serial shell settings put system user_rotation $rotation }
    if ($autoRotate -eq 'null') { & $Adb -s $Serial shell settings delete system accelerometer_rotation }
    else { & $Adb -s $Serial shell settings put system accelerometer_rotation $autoRotate }
    Write-Output 'Original display size, density and rotation restored.'
}
