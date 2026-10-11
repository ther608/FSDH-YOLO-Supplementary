# Decisive check for the drift hypothesis.
#
# Evening timeline so far (all with the same per-run SKIN reset):
#   17:14 full  SKIN 53.87 -> 242.34
#   17:16 full  SKIN 53.75 -> 255.11
#   17:19 full  SKIN 53.72 -> 257.09
#   17:24 slim  SKIN 53.60 -> 272.99
#   17:27 slim  SKIN 55.94 -> 313.59
#
# Two readings of that: the slim build is slower, or a slower drift the SKIN
# reset does not arrest. Re-running the FULL build now, with the identical
# 10 s settle, separates them: ~310 ms exonerates the slim build, ~250 ms
# implicates it.

$ErrorActionPreference = "Continue"

$adb = "D:\android-toolchain\android-sdk\platform-tools\adb.exe"
$dir = "D:\fsdh_shots\rounds_v2"
$log = "$dir\full_recheck_log.txt"
$full = "D:\yolo\paper\general journal\ultralytics-main\deploy_fsdh_yolo_n_android_native\android\app\build\outputs\apk\full\debug\app-full-debug.apk"

function Get-T {
    param($Adb)
    $t = (& $Adb shell dumpsys thermalservice) -join "`n"
    $cpu = ([regex]::Matches($t, "mValue=([0-9.]+), mType=0, mName=CPU")) | Select-Object -Last 1
    $skin = ([regex]::Matches($t, "mValue=([0-9.]+), mType=3, mName=SKIN")) | Select-Object -Last 1
    $st = ([regex]::Match($t, "Thermal Status: ([0-9]+)")).Groups[1].Value
    [pscustomobject]@{ Cpu = [double]$cpu.Groups[1].Value; Skin = [double]$skin.Groups[1].Value; Status = $st }
}

function Say {
    param($Text)
    ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $Text) | Tee-Object -FilePath $log -Append
}

Say "=== full-build recheck (drift hypothesis) ==="
Say ("installing " + (Split-Path $full -Leaf) + " mtime " + (Get-Item $full).LastWriteTime)
& $adb install -r $full | Select-Object -Last 1 | ForEach-Object { Say $_ }

& $adb shell am force-stop com.lychee.fsdh | Out-Null
$waited = 0
$t = Get-T $adb
while ($t.Skin -gt 46 -and $waited -lt 300) {
    Start-Sleep -Seconds 15
    $waited += 15
    $t = Get-T $adb
}
Say ("cooldown: waited {0}s -> CPU {1} SKIN {2} status {3}" -f $waited, $t.Cpu, $t.Skin, $t.Status)

& $adb shell am start -n com.lychee.fsdh/.MainActivity | Out-Null
Start-Sleep -Seconds 10          # same settle as the three primary runs

$b = Get-T $adb
Say ("before: CPU {0} SKIN {1} status {2}" -f $b.Cpu, $b.Skin, $b.Status)

& $adb shell input tap 352 2314
Start-Sleep -Seconds 66

$a = Get-T $adb
Say ("after:  CPU {0} SKIN {1} status {2}" -f $a.Cpu, $a.Skin, $a.Status)

Say "=== done ==="
