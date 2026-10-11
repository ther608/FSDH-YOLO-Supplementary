# Second slim sanity run under the reset protocol, plus a clean memory pair
# taken on the same session:
#   stable  = 25 s after launch, engine loaded and inferring
#   post    = immediately after the 60 s window closes
#
# The first slim run landed at 272.99 ms, above the three full-build runs
# (242-257 ms) and with a much larger SD, so one run cannot tell noise from a
# real difference.

$ErrorActionPreference = "Continue"

$adb = "D:\android-toolchain\android-sdk\platform-tools\adb.exe"
$dir = "D:\fsdh_shots\rounds_v2"
$log = "$dir\slim_run2_log.txt"

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

Say "=== slim run 2 + memory pair ==="

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
Start-Sleep -Seconds 25

$b = Get-T $adb
Say ("before: CPU {0} SKIN {1} status {2}" -f $b.Cpu, $b.Skin, $b.Status)
& $adb shell dumpsys meminfo com.lychee.fsdh > "$dir\meminfo_slim_stable.txt"

& $adb shell input tap 352 2314
Start-Sleep -Seconds 62

& $adb shell dumpsys meminfo com.lychee.fsdh > "$dir\meminfo_slim_post.txt"
$a = Get-T $adb
Say ("after:  CPU {0} SKIN {1} status {2}" -f $a.Cpu, $a.Skin, $a.Status)

& $adb shell screencap -p /sdcard/sl2.png | Out-Null
& $adb pull /sdcard/sl2.png "$dir\slim_run2_end.png" | Out-Null

Say "=== done ==="
