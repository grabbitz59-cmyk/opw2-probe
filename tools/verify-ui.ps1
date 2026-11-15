<#
.SYNOPSIS
    Проверка геометрии UI на OnePlus Watch 2 (круглый экран 466x466, центр 233,233,
    безопасный радиус контента 209 px).

.DESCRIPTION
    Сценарий для каждого запуска:
      1) будит экран (KEYCODE_WAKEUP),
      2) снимает uiautomator dump и забирает его на хост,
      3) снимает PNG через `adb exec-out screencap -p`,
      4) парсит bounds всех узлов с непустым text/content-desc, считает расстояние
         каждого угла прямоугольника до центра экрана и печатает таблицу PASS/FAIL,
      5) печатает сводку: количество FAIL и 5 самых далёких элементов,
      6) печатает wm size / wm density.

    Полноэкранные контейнеры (ширина >= 460 И высота >= 460) заведомо выходят за круг,
    они помечаются вердиктом "container" и не считаются провалом.

    Скриншот ровно 1866 байт — это чёрный PNG, признак "экран спит". Скрипт повторит
    wakeup + screencap до 3 раз и предупредит, если экран так и не проснулся.

.PARAMETER Serial
    Серийник устройства, обычно "<ip>:<port>". Если не указан — берётся первое
    устройство в состоянии device (приоритет у записи вида ip:port).

.PARAMETER OutDir
    Каталог, куда складываются PNG и XML.

.PARAMETER Tag
    Префикс имён файлов. По умолчанию — метка времени.

.PARAMETER AdbPath
    Путь к adb.exe. По умолчанию берётся современный C:\Android\platform-tools\adb.exe,
    затем C:\Android\adb.exe, затем adb из PATH.

.PARAMETER Center
    Координата центра экрана (по умолчанию 233).

.PARAMETER Threshold
    Максимально допустимое расстояние угла от центра (по умолчанию 209).

.PARAMETER ExpectedSize
    Ожидаемая сторона экрана (по умолчанию 466).

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File verify-ui.ps1 -Serial 192.168.31.137:44591

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-ui.ps1 -Tag main-screen -OutDir E:\Watch_App\analysis\ui-before

.NOTES
    Коды возврата: 0 — провалов нет; 1 — есть FAIL; 2 — нет устройства/adb;
    3 — не удалось получить uiautomator dump (геометрию проверить нельзя).
    Совместимо с Windows PowerShell 5.1 и PowerShell 7.
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$OutDir = 'E:\Watch_App\analysis\ui-check',
    [string]$Tag = '',
    [string]$AdbPath = '',
    [int]$Center = 233,
    [int]$Threshold = 209,
    [int]$ExpectedSize = 466,
    [int]$ContainerMin = 460,
    [switch]$NoDismissCharging
)

# Вывод в UTF-8, чтобы кириллица не ломалась при перенаправлении в файл/канал.
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

$ErrorActionPreference = 'Continue'

function Write-Head([string]$s) {
    Write-Host ''
    Write-Host ('=== ' + $s + ' ===')
}

function Stop-WithMessage {
    param([string]$Message, [int]$Code = 2)
    Write-Host ''
    Write-Host $Message
    exit $Code
}

function Resolve-AdbPath {
    param([string]$Explicit)
    if ($Explicit) {
        if (Test-Path $Explicit) { return (Resolve-Path -LiteralPath $Explicit).Path }
        return $null
    }
    foreach ($c in @('C:\Android\platform-tools\adb.exe', 'C:\Android\adb.exe', 'C:\adb\adb.exe')) {
        if (Test-Path $c) { return $c }
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Get-AdbDeviceList {
    param([string]$Adb)
    $list = @()
    $out = & $Adb devices 2>&1
    foreach ($line in $out) {
        $s = [string]$line
        if ($s -match '^(\S+)\s+(device|offline|unauthorized|no permissions|recovery|sideload|connecting|authorizing)\s*$') {
            $list += [pscustomobject]@{ Serial = $matches[1]; State = $matches[2] }
        }
    }
    return $list
}

function Pad-Text {
    param([string]$Text, [int]$Width, [switch]$Left)
    if ($null -eq $Text) { $Text = '' }
    $t = ($Text -replace '\s+', ' ').Trim()
    if ($t.Length -gt $Width) {
        if ($Width -le 1) { $t = $t.Substring(0, $Width) }
        else { $t = $t.Substring(0, $Width - 1) + '~' }
    }
    if ($Left) { return $t.PadRight($Width) }
    return $t.PadLeft($Width)
}

function Save-ScreenPng {
    param([string]$Adb, [string]$DevSerial, [string]$Path)
    # exec-out отдаёт PNG в stdout — читаем строго как байты, без текстового конвейера PowerShell.
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $Adb
        $psi.Arguments = '-s ' + $DevSerial + ' exec-out screencap -p'
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $proc = [System.Diagnostics.Process]::Start($psi)
        $errTask = $proc.StandardError.ReadToEndAsync()
        $ms = New-Object System.IO.MemoryStream
        $proc.StandardOutput.BaseStream.CopyTo($ms)
        $proc.WaitForExit()
        $null = $errTask.Result
        $bytes = $ms.ToArray()
        $ms.Dispose()
        if ($bytes.Length -gt 8) {
            [System.IO.File]::WriteAllBytes($Path, $bytes)
            return $bytes.Length
        }
    } catch { }

    # Резерв: скриншот на устройстве + pull.
    $remote = '/sdcard/ui_verify_shot.png'
    & $Adb -s $DevSerial shell screencap -p $remote 2>&1 | Out-Null
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue }
    & $Adb -s $DevSerial pull $remote $Path 2>&1 | Out-Null
    if (Test-Path -LiteralPath $Path) {
        return (Get-Item -LiteralPath $Path).Length
    }
    return 0
}

function Get-PngSize {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try {
        $b = [System.IO.File]::ReadAllBytes($Path)
        $isPng = ($b.Length -gt 24 -and $b[0] -eq 0x89 -and $b[1] -eq 0x50 -and $b[2] -eq 0x4E -and $b[3] -eq 0x47)
        if (-not $isPng) { return $null }
        $w = ([int]$b[16] * 16777216) + ([int]$b[17] * 65536) + ([int]$b[18] * 256) + [int]$b[19]
        $h = ([int]$b[20] * 16777216) + ([int]$b[21] * 65536) + ([int]$b[22] * 256) + [int]$b[23]
        return [pscustomobject]@{ Width = $w; Height = $h; Bytes = $b.Length }
    } catch { return $null }
}

# ---------------------------------------------------------------- экран / зарядный оверлей
#
# На OnePlus Watch 2 дисплеем управляет MCU: в MCU-сне инъекция KEYCODE_WAKEUP
# не поднимает экран (в logcat видно "PowerGroup: BM mcu state or screenControlledByMcu
# forbid wakeup"), поэтому screencap отдаёт чёрный PNG ровно 1866 байт.
# Лечится имитацией подключения зарядки: `dumpsys battery set ac 1` будит экран.
# Побочный эффект: поверх приложения выезжает зарядная анимация
# (com.heytap.wearable.offload, uid system — убить нельзя), она снимается
# `dumpsys battery unplug`. Оба состояния обратимы: `dumpsys battery reset`.

function Get-Wakefulness {
    param([string]$Adb, [string]$DevSerial)
    $out = (& $Adb -s $DevSerial shell 'dumpsys power' 2>&1) -join "`n"
    if ($out -match 'mWakefulness=(\w+)') { return $matches[1] }
    return 'unknown'
}

function Test-ChargingOverlay {
    param([string]$Adb, [string]$DevSerial)
    $out = (& $Adb -s $DevSerial shell 'dumpsys window' 2>&1) -join "`n"
    if ($out -match 'mCurrentFocus=\S+ \S+ (com\.heytap\.wearable\.offload)') { return $matches[1] }
    return ''
}

function Enable-ScreenAwake {
    param([string]$Adb, [string]$DevSerial)
    # 1) штатный путь из брифа
    & $Adb -s $DevSerial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    Start-Sleep -Milliseconds 600
    $w = Get-Wakefulness -Adb $Adb -DevSerial $DevSerial
    if ($w -eq 'Awake') { return 'wakeup' }

    # 2) MCU-сон: инъекция бессильна, имитируем подключение зарядки
    & $Adb -s $DevSerial shell dumpsys battery set ac 1 2>&1 | Out-Null
    Start-Sleep -Milliseconds 2500
    $w = Get-Wakefulness -Adb $Adb -DevSerial $DevSerial
    if ($w -eq 'Awake') { return 'battery-ac' }
    return 'failed'
}

function Invoke-UiDump {
    param([string]$Adb, [string]$DevSerial, [string]$XmlPath)
    $remote = '/sdcard/ui_verify.xml'
    $ok = $false
    for ($i = 1; $i -le 3; $i++) {
        $dumpOut = (& $Adb -s $DevSerial shell uiautomator dump $remote 2>&1) -join ' '
        if ($dumpOut -match 'dumped to|UI hierchary|hierarchy') { $ok = $true; break }
        Write-Host ("  uiautomator dump, попытка {0}/3: {1}" -f $i, $dumpOut.Trim())
        & $Adb -s $DevSerial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
        Start-Sleep -Milliseconds 900
    }
    if (-not $ok) { return [pscustomobject]@{ Ok = $false; RootPackage = '' ; Xml = '' } }

    if (Test-Path -LiteralPath $XmlPath) { Remove-Item -LiteralPath $XmlPath -Force -ErrorAction SilentlyContinue }
    & $Adb -s $DevSerial pull $remote $XmlPath 2>&1 | Out-Null
    if (-not (Test-Path -LiteralPath $XmlPath)) { return [pscustomobject]@{ Ok = $false; RootPackage = ''; Xml = '' } }
    if ((Get-Item -LiteralPath $XmlPath).Length -le 0) { return [pscustomobject]@{ Ok = $false; RootPackage = ''; Xml = '' } }

    $raw = Get-Content -LiteralPath $XmlPath -Raw -Encoding UTF8
    $pkg = ''
    if ($raw -match 'package="([^"]+)"') { $pkg = $matches[1] }
    return [pscustomobject]@{ Ok = $true; RootPackage = $pkg; Xml = $raw }
}

# ---------------------------------------------------------------- adb + устройство

$Adb = Resolve-AdbPath -Explicit $AdbPath
if (-not $Adb) {
    Stop-WithMessage ("[verify-ui] ADB НЕ НАЙДЕН.`n" +
        "  Искал: -AdbPath, C:\Android\platform-tools\adb.exe, C:\Android\adb.exe, C:\adb\adb.exe, PATH.`n" +
        "  Укажи путь вручную: -AdbPath C:\Android\platform-tools\adb.exe") 2
}

Write-Host "[verify-ui] adb: $Adb"
$verLine = (& $Adb version 2>&1 | Select-Object -First 1)
Write-Host "[verify-ui] $verLine"

$devices = @(Get-AdbDeviceList -Adb $Adb)
$online = @($devices | Where-Object { $_.State -eq 'device' })

if ($Serial) {
    $match = @($online | Where-Object { $_.Serial -eq $Serial })
    if ($match.Count -eq 0 -and $Serial -match '^\d{1,3}(\.\d{1,3}){3}:\d+$') {
        Write-Host "[verify-ui] устройство $Serial не подключено, пробую adb connect..."
        & $Adb connect $Serial 2>&1 | ForEach-Object { Write-Host "  $_" }
        Start-Sleep -Seconds 2
        $devices = @(Get-AdbDeviceList -Adb $Adb)
        $online = @($devices | Where-Object { $_.State -eq 'device' })
        $match = @($online | Where-Object { $_.Serial -eq $Serial })
    }
    if ($match.Count -eq 0) {
        $seen = ''
        if ($devices.Count -gt 0) {
            $seen = ($devices | ForEach-Object { '    ' + $_.Serial + ' -> ' + $_.State }) -join "`n"
        } else {
            $seen = '    (ничего не найдено)'
        }
        Stop-WithMessage ("[verify-ui] НЕТ УСТРОЙСТВА: $Serial не в состоянии 'device'.`n" +
            "  adb devices:`n$seen`n" +
            "  Подсказка: часы в Wi-Fi, adb-порт берётся из диапазона 30000-48000:`n" +
            "    $Adb connect 192.168.31.137:<порт>`n" +
            "  Внимание: старый C:\Android\adb.exe (1.0.32) оставляет устройство в 'offline',`n" +
            "  нужен C:\Android\platform-tools\adb.exe.") 2
    }
    $useSerial = $Serial
} else {
    if ($online.Count -eq 0) {
        $seen = ''
        if ($devices.Count -gt 0) {
            $seen = ($devices | ForEach-Object { '    ' + $_.Serial + ' -> ' + $_.State }) -join "`n"
        } else {
            $seen = '    (ничего не найдено)'
        }
        Stop-WithMessage ("[verify-ui] НЕТ УСТРОЙСТВА: adb не видит ни одного девайса в состоянии 'device'.`n" +
            "  adb devices:`n$seen`n" +
            "  Подсказка: $Adb connect 192.168.31.137:<порт>, порт ищется в 30000-48000.") 2
    }
    $pref = @($online | Where-Object { $_.Serial -match '^\d{1,3}(\.\d{1,3}){3}:\d+$' } | Select-Object -First 1)
    if ($pref.Count -gt 0) { $useSerial = $pref[0].Serial } else { $useSerial = $online[0].Serial }
}

Write-Host "[verify-ui] устройство: $useSerial"

if (-not $Tag) { $Tag = (Get-Date -Format 'yyyyMMdd-HHmmss') }
if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}
$OutDir = (Resolve-Path -LiteralPath $OutDir).Path
$pngPath = Join-Path $OutDir ($Tag + '.png')
$xmlPath = Join-Path $OutDir ($Tag + '_ui.xml')
Write-Host "[verify-ui] OutDir: $OutDir"

# ---------------------------------------------------------------- размеры экрана

Write-Head 'wm size / wm density'
$wmSize = (& $Adb -s $useSerial shell wm size 2>&1) -join ' '
$wmDensity = (& $Adb -s $useSerial shell wm density 2>&1) -join ' '
Write-Host ('  ' + $wmSize.Trim())
Write-Host ('  ' + $wmDensity.Trim())
if ($wmSize -match '(\d+)x(\d+)') {
    $rw = [int]$matches[1]; $rh = [int]$matches[2]
    if ($rw -ne $ExpectedSize -or $rh -ne $ExpectedSize) {
        Write-Host ("  ВНИМАНИЕ: ожидалось {0}x{0}, получено {1}x{2} — расчёт центра по ({3},{3}) может быть неверен." -f $ExpectedSize, $rw, $rh, $Center)
    } else {
        Write-Host ("  OK: {0}x{1} — совпадает с ожидаемым." -f $rw, $rh)
    }
} else {
    Write-Host '  ВНИМАНИЕ: не удалось разобрать вывод wm size.'
}

# ---------------------------------------------------------------- wakeup + dump

Write-Head 'снимок'
$wakeMode = Enable-ScreenAwake -Adb $Adb -DevSerial $useSerial
switch ($wakeMode) {
    'wakeup' {
        Write-Host '  экран: разбужен KEYCODE_WAKEUP.'
    }
    'battery-ac' {
        Write-Host '  экран: KEYCODE_WAKEUP не поднял дисплей (MCU-сон) — разбудил имитацией зарядки (dumpsys battery set ac 1).'
        Write-Host '         откат состояния батареи: adb shell dumpsys battery reset'
    }
    default {
        Write-Host '  ВНИМАНИЕ: экран разбудить не удалось (похоже, часы в MCU-сне) — нужен физический тап по экрану или кнопка на часах.'
    }
}

$dumpRes = Invoke-UiDump -Adb $Adb -DevSerial $useSerial -XmlPath $xmlPath

# Зарядная анимация com.heytap.wearable.offload (uid system, убить нельзя) перекрывает
# приложение целиком и портит и дамп, и скриншот — снимаем её имитацией отключения зарядки.
$overlay = Test-ChargingOverlay -Adb $Adb -DevSerial $useSerial
if (-not $overlay -and $dumpRes.Ok -and $dumpRes.RootPackage -eq 'com.heytap.wearable.offload') {
    $overlay = $dumpRes.RootPackage
}
if ($overlay) {
    if ($NoDismissCharging) {
        Write-Host "  ВНИМАНИЕ: на экране зарядная анимация $overlay, а не приложение (-NoDismissCharging: не снимаю)."
    } else {
        Write-Host "  на экране зарядная анимация $overlay — снимаю: adb shell dumpsys battery unplug"
        & $Adb -s $useSerial shell dumpsys battery unplug 2>&1 | Out-Null
        Start-Sleep -Seconds 2
        $dumpRes = Invoke-UiDump -Adb $Adb -DevSerial $useSerial -XmlPath $xmlPath
        if ($dumpRes.Ok -and $dumpRes.RootPackage -eq 'com.heytap.wearable.offload') {
            Write-Host '  ВНИМАНИЕ: зарядная анимация всё ещё на экране — проверка ниже может быть про неё, а не про приложение.'
        }
    }
}

$xmlOk = $dumpRes.Ok
if ($xmlOk) {
    Write-Host "  XML: $xmlPath"
    if ($dumpRes.RootPackage) { Write-Host ("  активное окно пакета: " + $dumpRes.RootPackage) }
} else {
    Write-Host '  ВНИМАНИЕ: uiautomator dump не получен — геометрию проверить нечем.'
}

# ---------------------------------------------------------------- скриншот (+ 1866 байт = спит)

$shotBytes = 0
$shotSleeping = $false
for ($i = 1; $i -le 3; $i++) {
    if ($i -gt 1) {
        $null = Enable-ScreenAwake -Adb $Adb -DevSerial $useSerial
        Start-Sleep -Milliseconds 800
    }
    $shotBytes = Save-ScreenPng -Adb $Adb -DevSerial $useSerial -Path $pngPath
    if ($shotBytes -ne 1866 -and $shotBytes -gt 1000) { break }
    if ($shotBytes -eq 1866) {
        $shotSleeping = $true
        Write-Host ("  screencap вернул 1866 байт (чёрный PNG, экран спит), попытка {0}/3." -f $i)
    }
}

$pngInfo = Get-PngSize -Path $pngPath
if ($pngInfo) {
    Write-Host ("  PNG: {0} ({1} байт, {2}x{3})" -f $pngPath, $pngInfo.Bytes, $pngInfo.Width, $pngInfo.Height)
    if ($pngInfo.Width -ne $ExpectedSize -or $pngInfo.Height -ne $ExpectedSize) {
        Write-Host ("  ВНИМАНИЕ: PNG {0}x{1}, ожидалось {2}x{2}." -f $pngInfo.Width, $pngInfo.Height, $ExpectedSize)
    }
} else {
    Write-Host ("  ВНИМАНИЕ: PNG не получен или не распознан как PNG: {0} ({1} байт)" -f $pngPath, $shotBytes)
}
if ($shotBytes -eq 1866) {
    Write-Host '  ВНИМАНИЕ: скриншот остался чёрным (1866 байт) после 3 попыток — экран не просыпается, попробуй нажать кнопку на часах.'
}

# ---------------------------------------------------------------- разбор геометрии

if (-not $xmlOk) {
    Write-Head 'итог'
    Write-Host '  Геометрия не проверена: нет XML. Код возврата 3.'
    exit 3
}

Write-Head 'геометрия'
$rawXml = Get-Content -LiteralPath $xmlPath -Raw -Encoding UTF8
$doc = New-Object System.Xml.XmlDocument
try { $doc.LoadXml($rawXml) } catch {
    Write-Head 'итог'
    Write-Host ('  ОШИБКА разбора XML: ' + $_.Exception.Message)
    Write-Host '  Код возврата 3.'
    exit 3
}

$rows = New-Object System.Collections.Generic.List[object]
$nodes = $doc.SelectNodes('//node')
$idx = 0
foreach ($n in $nodes) {
    $txt = [string]$n.GetAttribute('text')
    $desc = [string]$n.GetAttribute('content-desc')
    $label = $txt
    if ([string]::IsNullOrWhiteSpace($label)) { $label = $desc }
    if ([string]::IsNullOrWhiteSpace($label)) { continue }

    $b = [string]$n.GetAttribute('bounds')
    if ($b -notmatch '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$') { continue }
    $x1 = [int]$matches[1]; $y1 = [int]$matches[2]
    $x2 = [int]$matches[3]; $y2 = [int]$matches[4]
    $w = [Math]::Abs($x2 - $x1)
    $h = [Math]::Abs($y2 - $y1)

    $d = @(
        [Math]::Sqrt([Math]::Pow($x1 - $Center, 2) + [Math]::Pow($y1 - $Center, 2)),
        [Math]::Sqrt([Math]::Pow($x2 - $Center, 2) + [Math]::Pow($y1 - $Center, 2)),
        [Math]::Sqrt([Math]::Pow($x1 - $Center, 2) + [Math]::Pow($y2 - $Center, 2)),
        [Math]::Sqrt([Math]::Pow($x2 - $Center, 2) + [Math]::Pow($y2 - $Center, 2))
    )
    $maxD = ($d | Measure-Object -Maximum).Maximum

    $verdict = 'FAIL'
    if ($w -ge $ContainerMin -and $h -ge $ContainerMin) { $verdict = 'container' }
    elseif ($maxD -le $Threshold) { $verdict = 'PASS' }

    $idx++
    $rows.Add([pscustomobject]@{
        N           = $idx
        Label       = $label
        Klass       = [string]$n.GetAttribute('class')
        Bounds      = $b
        Size        = ('{0}x{1}' -f $w, $h)
        MaxCorner   = [Math]::Round($maxD, 1)
        Verdict     = $verdict
        IsContainer = ($verdict -eq 'container')
    })
}

$cN = 4; $cLabel = 34; $cKlass = 26; $cBounds = 23; $cSize = 9; $cDist = 9; $cVerd = 9
Write-Host ('  ' + (Pad-Text '#' $cN -Left) + '  ' + (Pad-Text 'текст / content-desc' $cLabel -Left) + '  ' +
    (Pad-Text 'класс' $cKlass -Left) + '  ' + (Pad-Text 'bounds' $cBounds -Left) + '  ' +
    (Pad-Text 'размер' $cSize -Left) + '  ' + (Pad-Text 'угол' $cDist -Left) + '  ' + (Pad-Text 'вердикт' $cVerd -Left))
Write-Host ('  ' + ('-' * ($cN + $cLabel + $cKlass + $cBounds + $cSize + $cDist + $cVerd + 12)))

foreach ($r in $rows) {
    Write-Host ('  ' + (Pad-Text ([string]$r.N) $cN -Left) + '  ' + (Pad-Text $r.Label $cLabel -Left) + '  ' +
        (Pad-Text $r.Klass $cKlass -Left) + '  ' + (Pad-Text $r.Bounds $cBounds -Left) + '  ' +
        (Pad-Text $r.Size $cSize -Left) + '  ' + (Pad-Text ([string]$r.MaxCorner) $cDist -Left) + '  ' +
        (Pad-Text $r.Verdict $cVerd -Left))
}

# ---------------------------------------------------------------- сводка

if ($rows.Count -eq 0 -or (@($rows | Where-Object { -not $_.IsContainer }).Count -eq 0)) {
    Write-Head 'итог'
    Write-Host '  ВНИМАНИЕ: uiautomator не дал ни одного измеримого текстового узла'
    Write-Host '  (либо вообще ни одного, либо только полноэкранные контейнеры).'
    Write-Host ('  Активное окно пакета: ' + $dumpRes.RootPackage + '; PNG: ' + $pngPath)
    Write-Host '  Так бывает, когда экран нарисован на canvas (дизайн v2): uiautomator видит только'
    Write-Host '  контейнер с content-desc. Геометрия по узлам НЕ проверена — смотри PNG или мерь пиксели.'
    Write-Host '  Код возврата 4.'
    exit 4
}

$fails = @($rows | Where-Object { $_.Verdict -eq 'FAIL' })
$passes = @($rows | Where-Object { $_.Verdict -eq 'PASS' })
$conts = @($rows | Where-Object { $_.IsContainer })

Write-Head 'итог'
Write-Host ("  экран: разбужен через {0}; скриншот {1} байт" -f $wakeMode, $shotBytes)
Write-Host ("  проверено элементов: {0}  (PASS {1}, FAIL {2}, container {3})" -f $rows.Count, $passes.Count, $fails.Count, $conts.Count)
Write-Host ("  порог: угол <= {0} px от центра ({1},{1}); контейнер = сторона >= {2}x{2}" -f $Threshold, $Center, $ContainerMin)

$far = @($rows | Where-Object { -not $_.IsContainer } | Sort-Object -Property MaxCorner -Descending | Select-Object -First 5)
if ($far.Count -gt 0) {
    Write-Host ''
    Write-Host '  5 самых далёких элементов (без контейнеров):'
    foreach ($r in $far) {
        Write-Host ("    {0,8} px  {1}  {2}" -f $r.MaxCorner, (Pad-Text $r.Verdict 9 -Left), (Pad-Text $r.Label 40 -Left) + ' ' + $r.Bounds)
    }
}

if ($fails.Count -gt 0) {
    Write-Host ''
    Write-Host '  FAIL — вылезли за круг 209 px:'
    foreach ($r in $fails) {
        Write-Host ("    {0,8} px  {1}  {2}" -f $r.MaxCorner, (Pad-Text $r.Label 40 -Left), $r.Bounds)
    }
}

Write-Host ''
Write-Host "  файлы: $pngPath"
Write-Host "         $xmlPath"

if ($fails.Count -gt 0) {
    Write-Host ''
    Write-Host ("  РЕЗУЛЬТАТ: ПРОВАЛ — {0} элемент(ов) вне безопасного круга. Код возврата 1." -f $fails.Count)
    exit 1
}

Write-Host ''
Write-Host '  РЕЗУЛЬТАТ: OK — все текстовые элементы внутри безопасного круга. Код возврата 0.'
exit 0
