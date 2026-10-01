# 本地发布：构建 release APK 并直接上传到 GitHub Release。
#
# 用法：
#   .\release.ps1 -Version 1.1
#   .\release.ps1 -Version 1.1 -NotesFile .\work\notes.md
#   .\release.ps1 -Version 1.1 -DryRun          # 只构建，不打 tag、不建 Release
#
# 版本号 -> versionCode 规则：major*10000 + minor*100 + patch（v1.0 -> 10000）。
# 版本号可写 1、1.0 或 1.0.0，缺的段按 0 计；tag 名与所写的版本号一致。

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    # Release 说明（Markdown）。不传时留空，GitHub 会显示为无正文。
    [string]$Notes,

    # 与 -Notes 二选一：从文件读取说明，便于写多行中文。
    [string]$NotesFile,

    # 跳过上传与打 tag，只产出 dist\HyperDuo-<version>.apk。
    [switch]$DryRun,

    # 已存在同名 Release 时覆盖它（默认会报错停下）。
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$Root    = 'C:\code\HyperDuo'
$Tools   = Join-Path $Root '.tools'
$Repo    = 'yixing233/HyperDuo'
$Package = 'com.hyperduo.trio'

# 原生程序把进度和警告写到 stderr（git 的 "Everything up-to-date"、gradle 的弃用提示）。
# 在 $ErrorActionPreference='Stop' 下，一旦把 stderr 并进管道，这些就变成终止错误，
# 会把一个成功的步骤当成失败中止整个发布。所以在这里临时放宽，只用 exit code 判成败。
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Exe @Arguments 2>&1
        return [pscustomobject]@{
            Output   = (@($output | ForEach-Object { "$_" }) -join "`n").Trim()
            ExitCode = $LASTEXITCODE
        }
    } finally {
        $ErrorActionPreference = $previous
    }
}

# ---- 版本号校验 -------------------------------------------------------------

if ($Version -notmatch '^\d+(\.\d+){0,2}$') {
    throw "版本号必须是 major / major.minor / major.minor.patch（收到 '$Version'）"
}

# 缺的段按 0 计：1.0 与 1.0.0 是同一个 versionCode，但 versionName 保留所写的形状。
$parts = @($Version.Split('.') | ForEach-Object { [int]$_ })
while ($parts.Count -lt 3) { $parts += 0 }
$code = $parts[0] * 10000 + $parts[1] * 100 + $parts[2]
$tag   = "v$Version"
$asset = "HyperDuo-$Version.apk"
$dist  = Join-Path $Root 'dist'

Write-Host "==> HyperDuo $Version (versionCode $code), tag $tag" -ForegroundColor Cyan

# ---- 构建 -------------------------------------------------------------------

# 发布构建不能用 --offline：R8 需要一个未 vendored 的 compose-group-mapping。
$env:JAVA_HOME        = Join-Path $Tools 'jdk\jdk-17.0.20.1+1'
$env:GRADLE_USER_HOME = Join-Path $Tools 'gradle-home'
$env:ANDROID_HOME     = Join-Path $Tools 'sdk'

$gradle = Join-Path $Tools 'gradle\gradle-9.8.0\bin\gradle.bat'
# 名字不能叫 $args：那是 PowerShell 的自动变量。
$gradleArgs = @(
    '--project-dir', $Root, ':app:assembleRelease',
    '--no-daemon', '--console=plain',
    "-PhyperduoVersionName=$Version",
    "-PhyperduoVersionCode=$code"
)

$build = Invoke-Native $gradle $gradleArgs
$build.Output -split "`n" | Where-Object { $_ -match '^BUILD |^FAILURE|error:' } | ForEach-Object { Write-Host "    $_" }
if ($build.ExitCode -ne 0) { throw "Gradle 构建失败（exit $($build.ExitCode)）" }

$built = Join-Path $Root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $built)) { throw "没有找到构建产物：$built" }

New-Item -ItemType Directory -Force -Path $dist | Out-Null
$target = Join-Path $dist $asset
Copy-Item $built $target -Force

# 构建出来的是什么版本，以 APK 自己为准 —— 版本注入写错时这里会立刻暴露。
$aapt = Join-Path $Tools 'sdk\build-tools\37.0.0\aapt2.exe'
$badgingRun = Invoke-Native $aapt @('dump', 'badging', $target)
$badging = [string]($badgingRun.Output -split "`n" | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
if (-not $badging) { throw "无法读取 $target 的包信息" }
Write-Host "    $badging"
if ($badging -notmatch "versionCode='$code'" -or $badging -notmatch "versionName='$Version'") {
    throw "APK 里的版本与预期不符，已中止发布"
}

Write-Host "==> 产物：$target" -ForegroundColor Green

if ($DryRun) {
    Write-Host '==> -DryRun：跳过 tag 与上传' -ForegroundColor Yellow
    return
}

# ---- 凭据 -------------------------------------------------------------------

function Get-GitHubToken {
    $tmp = Join-Path $env:TEMP ("hd_cred_" + [guid]::NewGuid().ToString('N') + '.txt')
    try {
        # git 只从 stdin 读协议行，PowerShell 管道塞不满它的期望，用 cmd 重定向。
        [System.IO.File]::WriteAllText($tmp, "protocol=https`nhost=github.com`n`n")
        $raw = (Invoke-Native 'cmd' @('/c', "git credential fill < `"$tmp`"")).Output
        $line = $raw -split "`n" | Where-Object { $_ -match '^password=' } | Select-Object -First 1
        if (-not $line) {
            throw 'GitHub 凭据不可用：git credential fill 没返回 password。先手动 git push 一次让凭据助手记住。'
        }
        return ($line -replace '^password=', '').Trim()
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
}

$token = Get-GitHubToken
$headers = @{
    Authorization = "token $token"
    'User-Agent'  = 'HyperDuo-Release'
    Accept        = 'application/vnd.github+json'
}

# ---- tag --------------------------------------------------------------------

Push-Location $Root
try {
    $existing = (Invoke-Native 'git' @('tag', '-l', $tag)).Output
    if ($existing) {
        Write-Host "==> tag $tag 已存在，跳过创建" -ForegroundColor Yellow
    } else {
        $created = Invoke-Native 'git' @('tag', '-a', $tag, '-m', "HyperDuo $Version")
        if ($created.ExitCode -ne 0) { throw "创建 tag $tag 失败：$($created.Output)" }
    }
    $pushed = Invoke-Native 'git' @('push', 'origin', $tag)
    if ($pushed.ExitCode -ne 0) { throw "推送 tag $tag 失败：$($pushed.Output)" }
    Write-Host "    $($pushed.Output)" -ForegroundColor DarkGray
} finally {
    Pop-Location
}

# ---- Release ----------------------------------------------------------------

$api = "https://api.github.com/repos/$Repo"

if (-not $Notes -and $NotesFile) {
    if (-not (Test-Path $NotesFile)) { throw "找不到说明文件：$NotesFile" }
    $Notes = [System.IO.File]::ReadAllText((Resolve-Path $NotesFile))
}

# 只有 404（这个 tag 还没有 Release）才当作「不存在」；网络错误要如实抛出去，
# 否则一次断网会被误判成「这是首发」，接着在创建 Release 时失败得更难懂。
$existingRelease = $null
try {
    $existingRelease = Invoke-RestMethod -Uri "$api/releases/tags/$tag" -Headers $headers
} catch {
    $status = $null
    try { $status = [int]$_.Exception.Response.StatusCode } catch { $status = $null }
    if ($status -ne 404) { throw }
}

$payload = @{
    tag_name   = $tag
    name       = "HyperDuo $Version"
    body       = [string]$Notes
    draft      = $false
    prerelease = $false
} | ConvertTo-Json -Depth 4

if ($existingRelease) {
    if (-not $Force) {
        throw "Release $tag 已存在。要覆盖请加 -Force。"
    }
    Write-Host "==> 覆盖已有 Release $tag" -ForegroundColor Yellow
    $release = Invoke-RestMethod -Method Patch -Uri "$api/releases/$($existingRelease.id)" `
        -Headers $headers -ContentType 'application/json; charset=utf-8' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($payload))
} else {
    $release = Invoke-RestMethod -Method Post -Uri "$api/releases" `
        -Headers $headers -ContentType 'application/json; charset=utf-8' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($payload))
}

# 同名 asset 会被 GitHub 拒绝，先删旧的再传。
foreach ($a in $release.assets) {
    if ($a.name -eq $asset) {
        Write-Host "==> 删除同名旧 asset $asset" -ForegroundColor Yellow
        Invoke-RestMethod -Method Delete -Uri "$api/releases/assets/$($a.id)" -Headers $headers | Out-Null
    }
}

$uploaded = Invoke-RestMethod -Method Post `
    -Uri "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=$asset" `
    -Headers $headers -ContentType 'application/vnd.android.package-archive' -InFile $target

Write-Host "==> 已上传 $($uploaded.name)  $($uploaded.size) B" -ForegroundColor Green
Write-Host "==> $($uploaded.browser_download_url)" -ForegroundColor Green
Write-Host "==> 设备上已装的旧版本现在可以在「关于 → 检查更新」里看到这个版本。"
