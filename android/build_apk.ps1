# One-shot build: web output -> assets -> gradle
# Usage:
#   powershell -File build_apk.ps1                  # build web + debug apk
#   powershell -File build_apk.ps1 -SkipWebBuild     # reuse existing web/dist
#   powershell -File build_apk.ps1 -Task assembleRelease
#
# Note: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less UTF-8 as
# ANSI; non-ASCII text right before a quote can swallow the quote and break parsing.
param(
    [switch]$SkipWebBuild,
    [string]$Task = 'assembleDebug'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $root
$web = Join-Path $repo 'web'
$dist = Join-Path $web 'dist'

# Run a command line through cmd.exe so .cmd / .ps1 shims (pnpm, gradlew.bat) work.
function Invoke-Cmd([string]$commandLine, [string]$workdir) {
    $p = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', $commandLine) `
        -WorkingDirectory $workdir -NoNewWindow -Wait -PassThru
    if ($p.ExitCode -ne 0) { throw "command failed ($($p.ExitCode)): $commandLine" }
}

if (-not $SkipWebBuild -or -not (Test-Path $dist)) {
    Write-Host '== build web =='
    # upstream package.json uses "rm -rf dist", which does not work on Windows;
    # call rspack directly instead.
    if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
    if (-not (Test-Path (Join-Path $web 'node_modules'))) {
        Invoke-Cmd 'pnpm install' $web
    }
    $env:NODE_ENV = 'production'
    Invoke-Cmd 'pnpm exec rspack build -c rspack.config.ts' $web
}

Write-Host '== sync assets =='
& (Join-Path $root 'sync_web.ps1')

Write-Host "== gradle $Task =="
# AGP 8.9 needs JDK 17. Warn early if neither JAVA_HOME nor org.gradle.java.home is set.
$hasJavaHomeProp = $false
$gradleProps = Join-Path $root 'gradle.properties'
if (Test-Path $gradleProps) {
    $hasJavaHomeProp = [bool](Select-String -Path $gradleProps -Pattern 'org\.gradle\.java\.home' -Quiet)
}
if (-not $env:JAVA_HOME -and -not $hasJavaHomeProp) {
    Write-Warning 'JAVA_HOME is not set and gradle.properties has no org.gradle.java.home. This build needs JDK 17.'
}
Invoke-Cmd "gradlew.bat $Task --no-daemon" $root

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    $info = Get-Item $apk
    Write-Host ('APK: {0} ({1:N1} MB)' -f $apk, ($info.Length / 1MB))
}
