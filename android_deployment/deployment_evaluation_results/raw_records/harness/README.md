# Run harness

The **executable form of the protocol** in `../live_session_protocol.md` and
appendix A.7.3. These are the scripts that actually drove the device; without them
the protocol document describes what should have happened rather than what did.

All four are PowerShell + `adb` and assume the phone is connected and the app is
installed. They write a timestamped log next to themselves and pull nothing —
the session JSON and the 12 sampled frames are pulled separately from
`/sdcard/Android/data/com.lychee.fsdh/files/`.

The `Produced` column names the output folder as the script creates it when run from
the deployment working directory. In this repository those folders live under
[`../sessions/`](../sessions).

| Script | Produced | Notes |
|---|---|---|
| `run_rounds.ps1` | `sessions/run1..3/` | The predefined protocol: 3 × 60 s, app force-stopped between runs, each run starting only after the HAL SKIN sensor falls to ≤ 46 °C (max 300 s wait), CPU/SKIN/battery logged before and after. |
| `slim_run1.ps1` | `sessions/slim_sanity/` | First run of the deployment package under the same protocol. |
| `slim_run2_mem.ps1` | `sessions/slim_sanity2/` + the `meminfo_slim_{stable,post}.txt` pair | Second slim run. Waits **25 s** after launch (not 10 s) to take the steady-state memory reading, which is why its pre-timing SKIN came out hotter than the others — see the diagnostics note in the protocol document. |
| `full_recheck.ps1` | `sessions/full_recheck/` | The control-package re-run that separated the build effect from the session drift. |

## Two things the scripts encode that the prose cannot

1. **The 10 s vs 25 s settle.** `run_rounds.ps1` waits 10 s between launching the
   activity and tapping *Rec 60 s*; `slim_run2_mem.ps1` waits 25 s because it also
   has to reach a steady state for `dumpsys meminfo`. That difference is the reason
   slim-run-2 started at a higher pre-timing SKIN (55.94 °C vs ~53.7 °C) and is a
   confound in that one comparison, not in the others.
2. **The tap coordinate is fixed** (`input tap 352 2314`, the *Rec 60 s* button).
   The run only begins if the app is in the foreground and the engine has finished
   loading; a missed tap produces no session JSON at all, which is why every run
   was verified by pulling and parsing the record afterwards rather than trusting
   that the tap landed.
