<#
.SYNOPSIS
  Утренний сбор ночных данных с OPW2: выгрузить файлы, проверить качество ночи, показать историю будильника.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\morning.ps1
  powershell -ExecutionPolicy Bypass -File tools\morning.ps1 -Serial 192.168.31.137:38665
#>
param(
    [string]$Adb = "C:\Android\platform-tools\adb.exe",
    [string]$Serial = "",
    [string]$OutDir = "probe-data",
    [string]$Python = "python",
    [switch]$NoReport
)

$ErrorActionPreference = "Continue"
$dir = "/sdcard/Android/data/io.github.smartalarm/files"

if (-not (Test-Path $Adb)) { Write-Error "adb не найден: $Adb"; exit 2 }

if (-not $Serial) {
    $lines = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
    if (-not $lines) { Write-Error "нет подключённых устройств (adb connect <ip:port>)"; exit 2 }
    $Serial = ($lines[0] -split '\s+')[0]
}
Write-Host "устройство: $Serial"

New-Item -ItemType Directory -Force $OutDir | Out-Null

Write-Host "`n=== выгрузка ==="
& $Adb -s $Serial pull "$dir/." "$OutDir/" 2>&1 | Select-Object -Last 2

Write-Host "`n=== качество ночи ==="
if (-not $NoReport) {
    & $Python "tools/night_report.py" --latest $OutDir
    $rc = $LASTEXITCODE
    Write-Host "night_report exit=$rc"
}

Write-Host "`n=== будильник: срабатывания ==="
$fired = Join-Path $OutDir "alarm_fired.txt"
if (Test-Path $fired) {
    Get-Content $fired | Select-Object -Last 6
} else {
    Write-Host "alarm_fired.txt нет — будильник не срабатывал"
}

Write-Host "`n=== будильник: последние решения логики ==="
$dbg = Join-Path $OutDir "alarm_debug.txt"
if (Test-Path $dbg) {
    Get-Content $dbg | Select-Object -Last 12
} else {
    Write-Host "alarm_debug.txt нет"
}

Write-Host "`n=== сенсоры: что реально доступно ==="
foreach ($f in "report_Ppg.txt", "report_HealthServices.txt") {
    $p = Join-Path $OutDir $f
    if (Test-Path $p) { Write-Host "--- $f ---"; Get-Content $p | Select-Object -First 14 }
}

if (-not $NoReport -and $rc -ne 0) {
    Write-Host "`nВНИМАНИЕ: ночь не прошла пороги — смотри провалы и hr_source выше."
}
