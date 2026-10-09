[CmdletBinding()]
param(
    [string]$Server = 'http://192.168.95.55:19910',
    [string]$Username = 'admin',
    [string]$Password = 'admin',
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Name = 'SendToSMB',
    [string]$Package = 'com.zlight.sendtosmb',
    [string]$Description = 'Android 手机和平板的局域网 SMB 文件管理器',
    [string]$ReleaseNotes = '',
    [switch]$Mandatory,
    [switch]$Replace
)
$ErrorActionPreference = 'Stop'
$curl = (Get-Command curl.exe -ErrorAction Stop).Source
$apkPath = (Resolve-Path -LiteralPath $Apk -ErrorAction Stop).Path
$Server = $Server.TrimEnd('/')
$work = Join-Path $env:TEMP ('easyupdate-publish-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$cookies = Join-Path $work 'cookies.txt'

function Run-Curl([string[]]$CurlArgs) {
    & $curl @CurlArgs
    if ($LASTEXITCODE -ne 0) { throw "curl 调用失败（退出码 $LASTEXITCODE）：$($CurlArgs -join ' ')" }
}
function Read-Html([string]$File) { return [System.IO.File]::ReadAllText($File, [System.Text.Encoding]::UTF8) }
function Get-Field([string]$Html, [string]$FieldName) {
    if ($Html -match ('name="' + [regex]::Escape($FieldName) + '"[^>]*value="([^"]*)"')) { return $Matches[1] }
    throw "页面中未找到字段 $FieldName"
}
function Get-Csrf([string]$Html) {
    $value = Get-Field $Html 'csrf'
    if ($value -notmatch '^[0-9a-f]{64}$') { throw 'CSRF 字段格式异常' }
    return $value
}
function Get-Page([string]$Url, [string]$File) {
    Run-Curl @('-sS', '-L', '-b', $cookies, '-c', $cookies, $Url, '-o', $File)
    return Read-Html $File
}

try {
    # 登录，保持会话 cookie 与 CSRF。
    Run-Curl @('-sS', '-c', $cookies, "$Server/login", '-o', (Join-Path $work 'login.html'))
    $csrf = Get-Csrf (Read-Html (Join-Path $work 'login.html'))
    Run-Curl @('-sS', '-L', '-b', $cookies, '-c', $cookies,
        '--data-urlencode', "csrf=$csrf", '--data-urlencode', "username=$Username", '--data-urlencode', "password=$Password",
        "$Server/login", '-o', (Join-Path $work 'login-result.html'))
    $appsHtml = Get-Page "$Server/admin/apps" (Join-Path $work 'apps.html')
    if ($appsHtml -match 'login-window' -or $appsHtml -notmatch '/admin/apps/new') { throw '登录失败，请检查 EasyUpdate 账号和密码' }

    # 查找或新建应用。
    $appId = $null
    foreach ($row in ($appsHtml -split '<tr>')) {
        if ($row -match [regex]::Escape($Package) -and $row -match '/admin/apps/(\d+)') { $appId = [int]$Matches[1]; break }
    }
    if (-not $appId) {
        $form = Get-Page "$Server/admin/apps/new" (Join-Path $work 'app-new.html')
        $csrf = Get-Csrf $form
        $headers = Join-Path $work 'create-headers.txt'
        Run-Curl @('-sS', '-b', $cookies, '-c', $cookies, '-D', $headers, '-o', (Join-Path $work 'create.html'),
            '--data-urlencode', "csrf=$csrf", '--data-urlencode', "name=$Name", '--data-urlencode', "package_name=$Package",
            '--data-urlencode', "description=$Description", "$Server/admin/apps/new")
        $location = Get-Content -LiteralPath $headers -Raw
        if ($location -notmatch 'Location:\s*/admin/apps/(\d+)') { throw '创建应用失败，请检查应用名称或包名' }
        $appId = [int]$Matches[1]
        Write-Output "已创建应用 $Name（ID $appId）"
    } else {
        Write-Output "已存在应用 $Name（ID $appId）"
    }

    # 上传 APK 并读取确认页中的版本元数据。
    $uploadPage = Get-Page "$Server/admin/apps/$appId/upload" (Join-Path $work 'upload.html')
    $csrf = Get-Csrf $uploadPage
    Run-Curl @('-sS', '-b', $cookies, '-c', $cookies,
        '--form-string', "csrf=$csrf", '-F', "apk=@$apkPath;type=application/vnd.android.package-archive",
        "$Server/admin/apps/$appId/upload", '-o', (Join-Path $work 'confirm.html'))
    $confirm = Read-Html (Join-Path $work 'confirm.html')
    if ($confirm -match '未读取到完整元数据') { throw '服务端未能读取 APK 元数据，请检查 APK 或稍后重试' }
    $token = Get-Field $confirm 'upload_token'
    $versionName = Get-Field $confirm 'version_name'
    $versionCode = Get-Field $confirm 'version_code'
    if (-not $versionName -or -not $versionCode) { throw '未能从确认页读取版本名称或版本号' }
    Write-Output "待发布版本：$versionName ($versionCode)，包名 $Package"

    # 创建草稿；-Replace 时先删除同版本旧发布再重新创建。
    $csrf = Get-Csrf $confirm
    $createHeaders = Join-Path $work 'release-headers.txt'
    function New-Draft([string]$HeadersFile) {
        $callArgs = @('-sS', '-b', $cookies, '-c', $cookies, '-D', $HeadersFile, '-o', (Join-Path $work 'release-create.html'),
            '--form-string', "csrf=$csrf", '--form-string', "upload_token=$token",
            '--form-string', "package_name=$Package", '--form-string', "version_name=$versionName",
            '--form-string', "version_code=$versionCode", '--form-string', "release_notes=$ReleaseNotes")
        if ($Mandatory) { $callArgs += @('--form-string', 'mandatory=on') }
        $callArgs += @('--form-string', 'action=create', "$Server/admin/apps/$appId/releases")
        Run-Curl $callArgs
        return Get-Content -LiteralPath $HeadersFile -Raw
    }
    function Find-ReleaseId([string]$Html, [string]$Version) {
        foreach ($row in ($Html -split '<tr>')) {
            if ($row -match ('>' + [regex]::Escape($Version) + '<') -and $row -match '/admin/releases/(\d+)') { return [int]$Matches[1] }
        }
        return $null
    }

    $createResult = New-Draft $createHeaders
    $releaseId = $null
    if ($createResult -match 'Location:\s*/admin/releases/(\d+)') {
        $releaseId = [int]$Matches[1]
        Write-Output "已创建草稿（ID $releaseId）"
    } else {
        $appHtml = Get-Page "$Server/admin/apps/$appId" (Join-Path $work 'app-detail.html')
        $existingId = Find-ReleaseId $appHtml $versionName
        if (-not $existingId) { throw '创建版本失败，且未在应用页找到已有版本' }
        if ($Replace) {
            $deletePage = Get-Page "$Server/admin/releases/$existingId/delete" (Join-Path $work 'release-delete.html')
            $csrf = Get-Csrf $deletePage
            Run-Curl @('-sS', '-b', $cookies, '-c', $cookies, '-o', (Join-Path $work 'release-deleted.html'),
                '--data-urlencode', "csrf=$csrf", '--data-urlencode', "confirmation=$versionCode", "$Server/admin/releases/$existingId/delete")
            Write-Output "已删除旧版本 $versionName ($versionCode)（ID $existingId）"
            $csrf = Get-Csrf $confirm
            $createResult = New-Draft $createHeaders
            if ($createResult -notmatch 'Location:\s*/admin/releases/(\d+)') { throw '删除旧版本后重新创建失败' }
            $releaseId = [int]$Matches[1]
            Write-Output "已重新创建草稿（ID $releaseId）"
        } else {
            $releaseId = $existingId
            Write-Output "版本 $versionName 已存在（ID $releaseId）"
        }
    }

    # 未发布则发布；已发布则跳过。
    $releaseHtml = Get-Page "$Server/admin/releases/$releaseId" (Join-Path $work 'release.html')
    if ($releaseHtml -match 'badge published[^>]*>已发布') {
        Write-Output "版本 $versionName 已经发布，无需重复操作"
    } else {
        $csrf = Get-Csrf $releaseHtml
        Run-Curl @('-sS', '-b', $cookies, '-c', $cookies, '-o', (Join-Path $work 'publish.html'),
            '--data-urlencode', "csrf=$csrf", '--data-urlencode', 'action=publish', "$Server/admin/releases/$releaseId/publish")
        Write-Output "已发布版本 $versionName ($versionCode)"
    }

    # 公开接口验证。
    $previousCode = [int]$versionCode - 1
    Write-Output '---- 公开 API 验证 ----'
    & $curl -sS "$Server/api/v1/apps/$Package/latest?version_code=$previousCode"
    Write-Output ''
    & $curl -sS "$Server/api/v1/apps/$Package/latest?version_code=$versionCode"
} finally {
    $cleanupPath = [System.IO.Path]::GetFullPath($work)
    $tempRoot = [System.IO.Path]::GetFullPath($env:TEMP).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $cleanupPath.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        [System.IO.Path]::GetFileName($cleanupPath) -notmatch '^easyupdate-publish-[0-9a-f]{32}$') {
        throw '拒绝清理临时发布目录：路径不在预期的临时目录内'
    }
    Remove-Item -LiteralPath $cleanupPath -Recurse -Force -ErrorAction SilentlyContinue
}
