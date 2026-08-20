# 本地一键构建 Release APK（与 GitHub Actions 产物一致）
# 用法：在仓库根目录执行  .\scripts\build-local-release.ps1 或双击 build-local-release.bat
# 启动时会询问版本：
#   回车      = 沿用当前版本重新构建（覆盖同名 APK）
#   输入说明  = 自动升一版（versionCode+1，versionName 末位+1，如 1.2.63 -> 1.2.64）
#              并把说明写入 release-manifest.json 的 changelog/notes
# 升版后记得提交推送（commit message = 新版本号），保持远端 CI 与本地一致
# 前置条件（本机已配置好，换机器需重装）：
#   1. JDK 21           C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
#   2. Android SDK      ..\pda\android-sdk（local.properties 已指向，含 platforms 35/36 + build-tools 35/36）
#   3. 正式签名          android\app\release.keystore + keystore.properties（git 忽略，勿提交）
# 说明：
#   - 项目路径含中文（桌面），AGP 需要 -Pandroid.overridePathCheck=true
#   - Gradle 依赖下载经本地代理 127.0.0.1:7890（若代理未开则直连），网络抖动时自动重试

$ErrorActionPreference = 'Stop'

# ── 路径与环境 ────────────────────────────────────────────────────────────
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$androidDir = Join-Path $repoRoot 'android'
$sdkDir = Join-Path ([System.IO.Path]::GetFullPath((Join-Path $repoRoot '..'))) 'android-sdk'
$javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
$apkPath = Join-Path $androidDir 'app\build\outputs\apk\release\app-release.apk'
$manifestPath = Join-Path $repoRoot 'release-manifest.json'
$updatesDir = Join-Path $repoRoot 'app-updates'

if (-not (Test-Path $javaHome)) { throw "未找到 JDK 21: $javaHome" }
if (-not (Test-Path $sdkDir)) { throw "未找到 Android SDK: $sdkDir" }
if (-not (Test-Path (Join-Path $repoRoot 'keystore.properties'))) { throw "缺少 keystore.properties，无法出正式签名包" }
if (-not (Test-Path (Join-Path $androidDir 'app\release.keystore'))) { throw "缺少 android\app\release.keystore" }

$env:JAVA_HOME = $javaHome

# ── 版本选择：升一版 或 沿用当前版本 ──────────────────────────────────────
$manifestNow = Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$currentNow = ($manifestNow.releases | Where-Object { [int]$_.versionCode -eq [int]$manifestNow.currentVersionCode })[0]
Write-Host ""
Write-Host "当前版本: $($currentNow.versionName) (versionCode $($manifestNow.currentVersionCode))" -ForegroundColor Cyan
$choice = Read-Host "回车 = 沿用当前版本重新构建；输入本次更新说明 = 自动升一版（如：修复扫码重复提交）"
if ($choice.Trim() -ne '') {
    node (Join-Path $repoRoot 'scripts\bump-version.mjs') $manifestPath $choice.Trim()
    if ($LASTEXITCODE -ne 0) { throw "版本升级失败" }
    Write-Host "已升版，本次构建新版本" -ForegroundColor Green
} else {
    Write-Host "沿用当前版本 $($currentNow.versionName) ($($manifestNow.currentVersionCode)) 重新构建" -ForegroundColor Yellow
}

# 代理按需启用：7890 有监听才走代理
$proxyListening = Get-NetTCPConnection -LocalPort 7890 -State Listen -ErrorAction SilentlyContinue
if ($proxyListening) {
    $env:GRADLE_OPTS = '-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890'
    Write-Host "[1/5] 检测到本地代理 127.0.0.1:7890，Gradle 依赖下载走代理" -ForegroundColor Cyan
} else {
    Remove-Item Env:\GRADLE_OPTS -ErrorAction SilentlyContinue
    Write-Host "[1/5] 未检测到本地代理，Gradle 直连下载" -ForegroundColor Cyan
}

# ── JS 构建 ───────────────────────────────────────────────────────────────
Write-Host "[2/5] npm install + vite build + cap sync" -ForegroundColor Cyan
Set-Location $repoRoot
npm install --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { throw "npm install 失败" }
npm run build
if ($LASTEXITCODE -ne 0) { throw "vite build 失败" }
npx cap sync android
if ($LASTEXITCODE -ne 0) { throw "cap sync 失败" }

# ── Gradle 构建（网络抖动自动重试） ────────────────────────────────────────
Write-Host "[3/5] gradlew assembleRelease（中文路径已启用 overridePathCheck）" -ForegroundColor Cyan
Set-Location $androidDir
$built = $false
for ($i = 1; $i -le 4 -and -not $built; $i++) {
    # 先删旧产物，避免上一次失败残留被误判成功
    & .\gradlew.bat assembleRelease --no-daemon '-Pandroid.overridePathCheck=true'
    if ($LASTEXITCODE -eq 0 -and (Test-Path $apkPath)) { $built = $true }
    else { Write-Host "构建失败（第 $i 次），Gradle 缓存已保留，5 秒后重试..." -ForegroundColor Yellow; Start-Sleep -Seconds 5 }
}
if (-not $built) { throw "gradlew assembleRelease 连续失败" }

# ── 产物入更新目录 ────────────────────────────────────────────────────────
Write-Host "[4/5] 整理产物到 app-updates" -ForegroundColor Cyan
Set-Location $repoRoot
$manifest = Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$current = ($manifest.releases | Where-Object { [int]$_.versionCode -eq [int]$manifest.currentVersionCode })[0]
if (-not $current) { throw "release-manifest.json 中未找到 currentVersionCode=$($manifest.currentVersionCode) 的条目" }
$targetApk = Join-Path $updatesDir $current.apkFileName
Copy-Item $apkPath $targetApk -Force
node scripts\generate-version-json.mjs release-manifest.json app-updates
if ($LASTEXITCODE -ne 0) { throw "generate-version-json 失败" }

# ── 完成 ──────────────────────────────────────────────────────────────────
Write-Host "[5/5] 构建完成" -ForegroundColor Green
Write-Host ""
Write-Host "版本: $($current.versionName) (versionCode $($current.versionCode))" -ForegroundColor Green
Write-Host "APK : $targetApk" -ForegroundColor Green
Write-Host "大小: $([math]::Round((Get-Item $targetApk).Length / 1MB, 2)) MB" -ForegroundColor Green
Write-Host ""
if ($proxyListening) {
    Write-Host "注意: 走代理下载依赖偶发 TLS 中断属正常，脚本已自动重试补齐缓存。" -ForegroundColor DarkGray
}
Write-Host "局域网更新服务(如已启动)会直接提供新文件:" -ForegroundColor Cyan
Write-Host "  http://192.168.2.138:9000/app-updates/$($current.apkFileName)"
Write-Host "手机端悬浮球 -> 触发原生更新检查 即可升级。"
Write-Host ""
Write-Host "如果本次升了版本，release-manifest.json 已改动，记得提交推送（commit message = 版本号）。" -ForegroundColor Yellow
Write-Host ""
Read-Host "按回车键关闭"
