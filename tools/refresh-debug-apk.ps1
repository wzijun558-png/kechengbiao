# refresh-debug-apk.ps1 —— 重新构建课程表 debug 包，并同步刷新本地本体文件
# 作用（每次发版必跑）：
#   1) 自动修正 local.properties 的 sdk.dir（项目目录变化时自动适配）
#   2) 构建 debug APK（与线上更新包同一签名线：debug.keystore）
#   3) 覆盖刷新两个本地本体文件：
#        - <root>\课程表App-debug.apk        （本地本体蓝本）
#        - <root>\release\course-table-1.0.apk（本地发布副本，与线上资产一致）
#   4) 打印新包的 size / md5（供 update.json 使用）
# 用法：pwsh -File tools\refresh-debug-apk.ps1   （或双击运行）

$ErrorActionPreference = 'Stop'
$root = 'C:\Program Files\Google\dsh'

$jdk    = Join-Path $root 'buildenv\jdk\jdk-17.0.20.1+1'
$gradle = Join-Path $root 'buildenv\gradle\gradle-8.7\bin\gradle.bat'
$sdk    = Join-Path $root 'android-sdk'

if (-not (Test-Path $jdk))    { throw "找不到 JDK: $jdk" }
if (-not (Test-Path $gradle)) { throw "找不到 Gradle: $gradle" }
if (-not (Test-Path $sdk))    { throw "找不到 Android SDK: $sdk" }

# 1) 修正 sdk.dir
$lp = Join-Path $root 'CourseTableApp\local.properties'
$sdkEsc = $sdk.Replace('\', '\\')
if (Test-Path $lp) {
    $content = Get-Content $lp -Raw
    if ($content -notmatch [regex]::Escape($sdkEsc)) {
        Set-Content -Path $lp -Value "sdk.dir=$sdkEsc" -Encoding Ascii
        "local.properties 已更新：sdk.dir=$sdkEsc"
    }
} else {
    Set-Content -Path $lp -Value "sdk.dir=$sdkEsc" -Encoding Ascii
}

# 2) 构建
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_USER_HOME = Join-Path $root 'android-user-home'
$env:PATH = "$jdk\bin;$env:PATH"

& $gradle -p (Join-Path $root 'CourseTableApp') :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败 (exit $LASTEXITCODE)" }

# 3) 覆盖刷新本地本体
$apk = Join-Path $root 'CourseTableApp\app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "找不到构建产物: $apk" }

$body = Join-Path $root '课程表App-debug.apk'
Copy-Item $apk $body -Force
$relDir = Join-Path $root 'release'
if (Test-Path $relDir) { Copy-Item $apk (Join-Path $relDir 'course-table-1.0.apk') -Force }

# 4) 输出校验值
$size = (Get-Item $body).Length
$md5  = (Get-FileHash $body -Algorithm MD5).Hash.ToLower()
''
'构建完成，本地本体已同步刷新：'
"  $body"
'  release\course-table-1.0.apk'
"size = $size"
"md5  = $md5"
''
'后续发版步骤见 docs\更新发布流程.md：'
'  改版本号 -> 本脚本 -> 打 tag -> 发 Release 上传本体包 -> 更新 update.json 的 size/md5 并推送'
