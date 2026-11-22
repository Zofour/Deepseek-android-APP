# Copy web build output into app/src/main/assets/www
param(
    [string]$Dist = '',
    [string]$Dest = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Dist) { $Dist = Join-Path (Split-Path -Parent $root) 'web\dist' }
if (-not $Dest) { $Dest = Join-Path $root 'app\src\main\assets\www' }

if (-not (Test-Path $Dist)) {
    throw "missing web build output: $Dist  (run the web build first, e.g. android\build_apk.ps1)"
}
if (Test-Path $Dest) { Remove-Item $Dest -Recurse -Force }
New-Item -ItemType Directory -Path $Dest -Force | Out-Null
Copy-Item -Path (Join-Path $Dist '*') -Destination $Dest -Recurse -Force
$count = (Get-ChildItem $Dest -Recurse -File | Measure-Object).Count
$size = '{0:N1} MB' -f ((Get-ChildItem $Dest -Recurse -File | Measure-Object Length -Sum).Sum / 1MB)
Write-Host "synced: $count files, $size -> $Dest"
