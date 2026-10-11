# Re-run the slim-build sanity check under the SAME reset protocol as the
# three primary runs, so it is comparable to them.
#
# The earlier sanity run (348.10 ms) was taken before the protocol change, on a
# hot device, back to back with other work. Against the new primary range
# (242-257 ms) it would read as a regression when it is really a different
# starting state.

$ErrorActionPreference = "Continue"

$adb = "D:\android-toolchain\android-sdk\platform-tools\adb.exe"
$dir = "D:\fsdh_shots\rounds_v2"
$log = "$dir\slim_sanity_log.txt"
$slim = "D:\yolo\paper\general journal\ultralytics-main\deploy_fsdh_yolo_n_android_native\android\app\build\outputs\apk\slim\debug\app-slim-debug.apk"

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

Say "=== slim sanity under reset protocol ==="
Say ("installing " + (Split-Path $slim -Leaf) + " mtime " + (Get-Item $slim).LastWriteTime)
& $adb install -r $slim | Select-Object -Last 1 | ForEach-Object { Say $_ }

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
Start-Sleep -Seconds 10
$b = Get-T $adb
Say ("before: CPU {0} SKIN {1} status {2}" -f $b.Cpu, $b.Skin, $b.Status)

& $adb shell input tap 352 2314
Start-Sleep -Seconds 66

$a = Get-T $adb
Say ("after:  CPU {0} SKIN {1} status {2}" -f $a.Cpu, $a.Skin, $a.Status)

& $adb shell screencap -p /sdcard/sl.png | Out-Null
& $adb pull /sdcard/sl.png "$dir\slim_sanity_end.png" | Out-Null

Say "=== done ==="
