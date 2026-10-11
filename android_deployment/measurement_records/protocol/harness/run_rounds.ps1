# Three cooled 60 s sessions on the full build.
#
# Protocol change vs runs 1-3 (which were back to back): the app is stopped
# between runs and each run starts only after the SKIN sensor has come back
# down to <= 46 C, with CPU/SKIN/battery temperature and level recorded before
# and after every run. Runs 1-3 started at SKIN 54.5 C.
#
# The 10 s between launching the activity and tapping Rec is engine/camera
# bring-up; the session excludes its own first 20 frames as warm-up.

$ErrorActionPreference = "Continue"

$adb = "D:\android-toolchain\android-sdk\platform-tools\adb.exe"
$dir = "D:\fsdh_shots\rounds_v2"
$log = "$dir\runlog.txt"
$skinTarget = 46.0
$maxCoolSeconds = 300

New-Item -ItemType Directory -Force $dir | Out-Null

function Get-T {
    param($Adb)
    $t = (& $Adb shell dumpsys thermalservice) -join "`n"
    $cpu = ([regex]::Matches($t, "mValue=([0-9.]+), mType=0, mName=CPU")) | Select-Object -Last 1
    $skin = ([regex]::Matches($t, "mValue=([0-9.]+), mType=3, mName=SKIN")) | Select-Object -Last 1
    $bat = ([regex]::Matches($t, "mValue=([0-9.]+), mType=2, mName=BATTERY")) | Select-Object -Last 1
    $st = ([regex]::Match($t, "Thermal Status: ([0-9]+)")).Groups[1].Value
    $bl = (& $Adb shell dumpsys battery) -join "`n"
    $lv = ([regex]::Match($bl, "level: ([0-9]+)")).Groups[1].Value
    $chg = ([regex]::Match($bl, "USB powered: (true|false)")).Groups[1].Value
    [pscustomobject]@{
        Cpu    = [double]$cpu.Groups[1].Value
        Skin   = [double]$skin.Groups[1].Value
        Bat    = [double]$bat.Groups[1].Value
        Status = $st
        Level  = $lv
        Usb    = $chg
    }
}

function Say {
    param($Text)
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $Text
    $line | Tee-Object -FilePath $log -Append
}

Say "=== cooled 3x60s, target SKIN <= $skinTarget C, FULL build ==="

for ($r = 1; $r -le 3; $r++) {
    Say "== run $r =="

    & $adb shell am force-stop com.lychee.fsdh | Out-Null

    $waited = 0
    $t = Get-T $adb
    while ($t.Skin -gt $skinTarget -and $waited -lt $maxCoolSeconds) {
        Start-Sleep -Seconds 15
        $waited += 15
        $t = Get-T $adb
    }
    $reached = if ($t.Skin -le $skinTarget) { "reached" } else { "TIMEOUT" }
    Say ("cooldown {0}: waited {1}s -> CPU {2} SKIN {3} BAT {4} level {5}% status {6}" -f `
        $reached, $waited, $t.Cpu, $t.Skin, $t.Bat, $t.Level, $t.Status)

    & $adb shell am start -n com.lychee.fsdh/.MainActivity | Out-Null
    Start-Sleep -Seconds 10

    $b = Get-T $adb
    Say ("before: CPU {0} SKIN {1} BAT {2} level {3}% status {4} usb {5}" -f `
        $b.Cpu, $b.Skin, $b.Bat, $b.Level, $b.Status, $b.Usb)

    & $adb shell input tap 352 2314
    Start-Sleep -Seconds 66

    $a = Get-T $adb
    Say ("after:  CPU {0} SKIN {1} BAT {2} level {3}% status {4}" -f `
        $a.Cpu, $a.Skin, $a.Bat, $a.Level, $a.Status)

    & $adb shell screencap -p /sdcard/e.png | Out-Null
    & $adb pull /sdcard/e.png "$dir\run${r}_end.png" | Out-Null
}

Say "=== all runs done ==="
