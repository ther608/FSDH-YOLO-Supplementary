# Android deployment measurements

Everything the on-device deployment section is built from: the raw per-frame
records, the sampled frames, the memory readings and the exact APKs. Consolidated
here from the two scratch folders (`D:\fsdh_apk`, `D:\fsdh_shots`) so the evidence
does not live outside the repository.

**Scope of this evidence**: *preliminary on-device deployment validation*. It
answers "can the trained FSDH-YOLO be deployed to a consumer Android device and
run continuous camera-based detection?" It is not a real-time claim, not a
power claim, and not a long-run stability claim.

---

## Device and deployment

| | |
|---|---|
| Device | Redmi K40 Gaming Enhanced Edition, `M2012K10C` / `ares` / MT6893, Android 13 (SDK 33) |
| Model | FSDH-YOLO-n **seed0**, ONNX fp32, static 640×640, batch 1, NMS omitted |
| Backend | ONNX Runtime **Android 1.19.2, CPU EP**; intraOp=4, interOp=1, optLevel=ALL_OPT |
| Camera stream | 480×640 (4:3, pinned identically for Preview and ImageAnalysis) |
| App | `com.lychee.fsdh`, Kotlin + CameraX, light theme, English UI |

---

## Layout

```
measurements/
├── protocol/
│   ├── live_session_protocol.md      what the 60 s run does and why
│   └── results_summary.json          the run table + across-run statistics
├── protocol/
│   ├── harness/                      the scripts that actually drove the device
│   └── results_summary.json
├── sessions/
│   ├── run1|run2|run3/               control package — the primary result
│   ├── slim_sanity/ slim_sanity2/    deployment package, post-hoc diagnostics
│   ├── full_recheck/                 control package again — this one shows the drift
│   ├── _superseded_backtoback/       no-reset runs; methodological contrast ONLY
│   ├── _superseded_pre_protocol/     the 348.10 ms run that triggered the investigation
│   └── runlog_cooled.txt             reset waits and HAL temperatures per session
├── bench/                            fixed-frame replay records (the backend table)
├── memory/                           dumpsys meminfo, full vs slim
├── apk/                              the exact binaries (see provenance below)
├── ui/                               UI screenshots, main + bench
└── _scratch/                         intermediate debug screenshots, not results
```

---

## `record.json`

One file per session, written by the app itself when the 60 s window closes.
Shape:

| Key | Meaning |
|---|---|
| `deployment` | model, engine, backend, thread settings, network input, camera stream |
| `protocol` | duration, warm-up, shot schedule, and the **definitions** of end-to-end and of the statistics |
| `counts` | processed / measured / warm-up-excluded frames, shots saved |
| `summary` | per-metric `n, mean, sd, median, p95, min, max` for e2e / infer / pre / post / overhead |
| `shots` | the 12 sampled frame filenames |
| `frames` | **every** frame: `i, tMs, wallMs, preMs, inferMs, postMs, overheadMs, dets, excluded` |

`frames` is the point of the file. Any statistic in `summary` can be recomputed
from it, and the warm-up exclusion can be re-derived rather than taken on trust.

---

## Three measurement decisions worth knowing

**1. End-to-end is measured, not summed.** `wallMs` is the wall time around the
whole per-frame pipeline: YUV→RGB conversion, letterbox, inference, decode/NMS.
The engine's own `totalMs` is only `pre + infer + post`, which **excludes the
YUV→RGB conversion** — on this device that is 33–47 ms, 12–14 % of end-to-end.
Keeping the sum as the headline would have dropped it silently. Instead:

```
T_E2E = T_pre + T_infer + T_post + T_overhead        (holds exactly, per frame)
```

`overhead` is what the code actually measures as the residual. It is dominated
by the YUV→RGB conversion but has **not** been timed separately, so it should be
described as *pipeline overhead, mainly the camera-format conversion* — not as a
strictly attributed conversion cost.

**2. All four on-screen metrics share one 20-frame window.** An earlier build
averaged `E2E` over 20 frames while showing the *instantaneous* pre/infer/post,
which put `E2E 227 ms` next to stages summing to `257 ms` on screen. The display
window is now a single object holding all four together, and
`FPS = 1000 / mean(E2E)` uses the same mean.

**3. The screen is not the result.** The windowed average exists so the display
does not flicker. Every figure in `results_summary.json` is computed after the
run from the raw `frames` list. When a session ends the display freezes on that
session's summary, so a screenshot cannot disagree with the record.

**4. Runs are separated by a device reset.** The app is force-stopped and the
device is left until the HAL SKIN sensor falls to ≤ 46 °C before the next run.
This is not a cold start — the app's own bring-up re-heats the device by ~10 °C in
about 10 s.

**5. Two packages, two jobs — do not mix them.** `full` = **all** performance
measurement. `slim` = APK size and memory only. **No latency or FPS comparison is made
between them** — see the diagnostics note below for why that comparison is unavailable.

**6. The primary result is the three runs in `sessions/run1..3/`, and nothing else.**

| Run | E2E mean±SD / ms | E2E P95 / ms | FPS |
|---|---:|---:|---:|
| 1 | 242.34 ± 17.86 | 284.63 | 4.13 |
| 2 | 255.11 ± 20.66 | 305.54 | 3.92 |
| 3 | 257.09 ± 18.09 | 292.96 | 3.89 |
| **Range** | **242.34 – 257.09** | **284.63 – 305.54** | **3.89 – 4.13** |

`sessions/slim_sanity*/` and `sessions/full_recheck/` are **post-hoc diagnostics, not
results**. They exist to answer why the slim sessions came out slower, and are kept
because that investigation is worth reporting — not because their numbers are
performance figures.

**The reset buys consistency between adjacent runs, not repeatability.** It cuts the
spread of three adjacent runs to 6 % (vs 42 % back to back), but the **same build**
re-run **about 16 min later**, at a pre-timing SKIN only 0.20 °C higher, measured
**21.5 % slower than its own first session** (242.34 → 294.41 ms). Across all six
sessions the pre-timing SKIN range is 2.3 °C while the latency range is 1.29× — so the
drift is **not** explained by the temperature readings available here. No causal
attribution is made anywhere in the paper material.

So: quote latency as a **range**, never as a controlled benchmark, never averaged
across sessions, and never compared between the two packages.

---

## APK provenance

| File | What it is |
|---|---|
| `fsdh_yolo_n_seed0_2026-10-08_cooled-runs_full.apk` | **The exact binary behind the three reported runs.** Archived because rebuilding from a later commit would not reproduce it. |
| `fsdh_yolo_n_seed0_2026-10-08_runs1-3.apk` | The binary behind the earlier **back-to-back** runs, kept because it is what the contrast dataset was produced with. |
| `app-slim-debug.apk` | ONNX Runtime + the single final model. **The deployment build** — the one whose size and memory describe shipping. |
| `app-full-debug.apk` | Both runtimes, both models. This is the A/B build the backend table comes from. Byte-identical to `..._cooled-runs_full.apk`; that one is kept under its provenance name. |
| `fsdh_yolo_n_seed0_2026-10-06_dark_zh.apk` | The build behind the fixed-frame backend table (dark UI, Chinese strings). Superseded as UI, kept for provenance. |

The two current ones are rebuilt by
`scripts\build_apk.ps1 -Flavor slim` / `-Flavor full`. Both are **debug** builds;
a release build with R8 and an arm64-only ABI filter would be substantially
smaller (see `protocol/live_session_protocol.md`).

APKs are excluded from git (`../../.gitignore`) — they are large and regenerable
from the build script. Everything else under `measurements/` is committed.

---

## Reproducing a session

1. `powershell -File scripts\build_apk.ps1 -Flavor full`
2. `adb install -r android\app\build\outputs\apk\full\debug\app-full-debug.apk`
3. Point the phone at a single litchi cluster, fixed position, phone still.
4. Tap **Rec 60 s**. Do not touch the phone for the next 60 s.
5. `adb pull /sdcard/Android/data/com.lychee.fsdh/files/` — the record and the 12
   shots land there.

Keep the screen awake for the whole window (charging + `adb shell svc power
stayon true`). Record the thermal status before and after — see the protocol
notes for why the aggregate status is not sufficient on this device.
