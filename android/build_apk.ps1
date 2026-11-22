# 一键构建：web 产物 -> assets -> gradle
# 用法：
#   powershell -File build_apk.ps1                 # 构建 web + 打包 debug
#   powershell -File build_apk.ps1 -SkipWebBuild    # 复用已有 web/dist
#   powershell -File build_apk.ps1 -Task assembleRelease
param(
    [switch]$SkipWebBuild,
    [string]$Task = 'assembleDebug'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $root
$web = Join-Path $repo 'web'
$dist = Join-Path $web 'dist'

function Invoke-Native([string]$file, [string[]]$arguments, [string]$workdir) {
    $p = Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $workdir `
        -NoNewWindow -Wait -PassThru
    if ($p.ExitCode -ne 0) { throw "$file exited with $($p.ExitCode)" }
}

if (-not $SkipWebBuild -or -not (Test-Path $dist)) {
    Write-Host '== build web =='
    # 注意：上游 package.json 里的 `rm -rf dist` 在 Windows 上跑不了，这里直接调 rspack
    if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
    Push-Location $web
    try {
        if (-not (Test-Path (Join-Path $web 'node_modules'))) {
            Invoke-Native 'pnpm' @('install') $web
        }
        $env:NODE_ENV = 'production'
        Invoke-Native 'pnpm' @('exec', 'rspack', 'build', '-c', 'rspack.config.ts') $web
    } finally {
        Pop-Location
    }
}

Write-Host '== sync assets =='
& (Join-Path $root 'sync_web.ps1')

Write-Host "== gradle $Task =="
# 预检：AGP 8.9 需要 JDK 17，系统默认 java 常常不是（会报 requires Java 17）
if (-not $env:JAVA_HOME -and -not (Select-String -Path (Join-Path $root 'gradle.properties') -Pattern '^\s*org\.gradle\.java\.home' -Quiet)) {
    Write-Warning 'JAVA_HOME 未设置，且 gradle.properties 里没有 org.gradle.java.home。构建需要 JDK 17。'
}
Push-Location $root
try {
    Invoke-Native (Join-Path $root 'gradlew.bat') @($Task, '--no-daemon') $root
} finally {
    Pop-Location
}

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    $info = Get-Item $apk
    Write-Host ("APK: {0} ({1:N1} MB)" -f $apk, ($info.Length / 1MB))
}
