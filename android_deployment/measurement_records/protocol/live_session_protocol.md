# Live session protocol (60 s continuous camera)

The measurement behind `results_summary.json`. Implemented in
`bench/LiveSession.kt` and driven from the **Rec 60 s** button on the live screen.

---

## What one session does

| Step | Setting |
|---|---|
| **Inter-run reset** | app force-stopped; poll `dumpsys thermalservice` until HAL **SKIN ≤ 46 °C**; relaunch (max 300 s wait) |
| Warm-up | first **20 processed frames** recorded but excluded from every statistic |
| Window | **60 s** of continuous camera input, wall clock |
| Frames | every frame the analyzer actually processed is recorded individually |
| Sampled frames | **12** screenshots at 5, 10, … 60 s |
| Statistics | computed after the run from the raw frame list |

**The reset gives a repeatable starting state, not a cool one.** Force-stopping the
app does cool the device (SKIN falls to ~45 °C in under a minute), but the app's own
bring-up — camera bind, ORT session creation, the first inferences — pushes SKIN back
up by ~10 °C within about 10 s. In the three reported runs the pre-timing SKIN readings
were 53.87 / 53.75 / 53.72 °C: repeatable to 0.15 °C, and hot. Do not describe this
as a cold start.

Frames are processed on a single-thread executor with `STRATEGY_KEEP_ONLY_LATEST`,
so a frame arriving while one is in flight is **dropped, not queued**. The frame
count is therefore a property of how fast the pipeline runs, not of the camera's
frame rate — `processed frames ≈ 60 s / mean E2E`. That is what makes the count
worth reporting: it shows the latency was not obtained by selecting single frames.

---

## Definitions (state these in the paper)

**End-to-end** — measured wall time around the whole per-frame pipeline on the
analyzer thread: YUV→RGB conversion → letterbox → inference → decode/NMS.
Excludes overlay drawing and PNG encoding.

```
T_E2E = T_pre + T_infer + T_post + T_overhead          (holds exactly, per frame)
```

`T_overhead` is the measured residual, dominated by the YUV→RGB conversion
(33–47 ms here, 12–14 % of end-to-end on this device). It has **not** been timed
separately, so the paper should call it *pipeline overhead, mainly the
camera-format conversion*. Grading it fully to `T_conv` would require its own
timer, which this build does not have.

**SD** — sample standard deviation (`n − 1`).

**P95** — nearest-rank percentile on the ascendingly sorted samples:

> The 95th-percentile latency was calculated using the nearest-rank definition,
> i.e. the ⌈0.95n⌉-th value of the ascendingly sorted latency samples.

No interpolation. On a sample this size the two definitions differ by a few
percent, and nearest-rank always returns a latency that actually occurred.

**FPS** — `1000 / mean(T_E2E)` over the same measured frames. Not the mean of
per-frame `1000/T_E2E`, and not the camera frame rate.

---

## Package roles — do not mix them

| | Control package (`full`) | Deployment package (`slim`) |
|---|---|---|
| Used for | **all performance measurement**, backend comparison | **APK size and memory only** |
| Runtimes | ONNX Runtime + TensorFlow Lite | ONNX Runtime only |
| Model assets | FSDH + YOLO11n baseline, ONNX + TFLite | the single deployed model (ONNX) |
| Performance conclusions drawn | **yes** | **no — none at all** |

**No FPS or latency comparison between the two packages is made anywhere.** See the
diagnostics section below for why that comparison is not available.

---

## Reporting: the primary result

Report per run. Do **not** concatenate runs into one large `n`: frames within a run are
highly autocorrelated, so a pooled SD would describe neither source of variance.

| Run | Pre-timing SKIN / °C | Valid frames | E2E mean±SD / ms | E2E P95 / ms | Infer mean±SD / ms | FPS |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 53.87 | 210 | 242.34 ± 17.86 | 284.63 | 205.43 ± 16.79 | 4.13 |
| 2 | 53.75 | 199 | 255.11 ± 20.66 | 305.54 | 216.11 ± 19.55 | 3.92 |
| 3 | 53.72 | 197 | 257.09 ± 18.09 | 292.96 | 217.33 ± 17.05 | 3.89 |
| **Range** | spread 0.15 | 606 | **242.34 – 257.09** | **284.63 – 305.54** | 205.43 – 217.33 | **3.89 – 4.13** |

The ± inside a column is the **within-session** frame-to-frame SD. There is deliberately
no across-session ±: the diagnostics below show these sessions are not identically
distributed, so averaging them would mislead.

**Report ranges only.** P95 as a range (already a per-session quantile), and latency as a
range rather than a benchmark number.

### What the reset does and does not buy

**Does**: across three adjacent runs it cuts the spread to **6 %**, versus **42 %** for the
same protocol run back to back with no reset (`sessions/_superseded_backtoback/`).
That is its entire justification.

**Does not**: make the measurement repeatable. The **same build** re-run **about 16 min
later**, at a pre-timing SKIN 0.20 °C higher, measured **21.5 % slower than its own first
session** (242.34 → 294.41 ms).

> **Do not write "the reset makes the measurement repeatable", and do not write that any
> of this demonstrates thermal throttling.** No CPU frequency, scheduler state or
> throttling event was recorded. A single skin-temperature reset does not guarantee a
> comparable performance state across periods: `T` also depends on SoC temperature, CPU
> frequency, scheduling, power policy, background load and runtime state — none of which
> were observed here.

---

## Diagnostic runs (not part of the protocol, not results)

The two `slim` sessions came out above the primary range, so three extra runs were made to
find out why. **They are an investigation, not part of the deployment experiment.**

| Run | Build | Pre-timing SKIN / °C | Valid frames | E2E mean±SD / ms | P95 / ms | FPS |
|---|---|---:|---:|---:|---:|---:|
| slim-1 | slim | 53.60 | 184 | 272.99 ± 36.49 | 344.44 | 3.66 |
| slim-2 | slim | 55.94 | 157 | 313.59 ± 27.45 | 354.31 | 3.19 |
| **full-recheck** | **full** | 54.07 | 169 | **294.41 ± 41.68** | 351.08 | 3.40 |

**The full-build recheck is the key diagnostic evidence.** Same build as primary run 1,
pre-timing SKIN 0.20 °C higher, measured **about 16 min later** — and **21.5 % slower**
(242.34 → 294.41 ms).

**Conclusions, worded precisely:**

1. The build effect and the session drift are **confounded**. **No equivalence conclusion
   is drawn between the two packages.**
2. It also **cannot** be claimed that the slowdown was caused by the deployment package.
   The evidence supports only: *no evidence that it was caused by that build difference*.
3. Across all six sessions the pre-timing SKIN range is 2.3 °C while the session-mean range
   is 1.29× — the drift is **not** explained by the temperature readings available here.
4. No causal attribution is made, here or in the paper.

---

## Session dependence — the unified statement

Two independent pieces of evidence, one statement:

1. The fixed-frame replay (`bench/`, appendix B.5.1): two sessions, same device and
   protocol, differed by **1.45–1.76×**.
2. This protocol: two sessions of the **same build**, about 16 min apart, pre-timing SKIN
   differing by 0.20 °C, differed by **1.22×** (242.34 → 294.41 ms, +21.5 %).

> Absolute latency on a mobile device is strongly session-dependent, and the device-state
> readings available here do not establish that two sessions ran under equivalent
> conditions. The cause is **undetermined**. Therefore: absolute latency is reported as
> ranges only, structural conclusions use within-session paired ratios, and no causal
> attribution is made anywhere.

**This is what bounds the deployment section as *preliminary on-device deployment
validation*, not a strict mobile performance benchmark.**

The two package comparison is stated in full in the diagnostics section above: the build
difference is **confounded with the drift and cannot be resolved**, so **no equivalence
claim is made**. The ORT-only package is used only for APK size and memory; every latency
figure comes from the full build.

---

## Thermal control — the aggregate status is not enough

The app's own gating (`PowerManager.getCurrentThermalStatus()`) read **NONE (0)** in
**every** run. `dumpsys thermalservice` meanwhile shows the HAL reporting the **SKIN
sensor at 53.6–55.9 °C with status 3 (SEVERE)**, with CPU at 66–76 °C and battery ~40 °C.

So the runs were **not** in the clean thermal state the aggregate status suggests, and
"thermal status = NONE" must not be presented as evidence that thermal state was
controlled.

**And temperature does not explain the drift anyway**: across all six sessions the pre-run
SKIN range is 2.3 °C while the session-mean range is 1.29×. Whatever moves the latency
between sessions is not something these readings capture.

Recorded per session: HAL CPU/SKIN/battery temperatures and level (`sessions/run3/`,
`sessions/*/runlog*.txt`).

---

## Scope limits (do not overclaim)

- **No real-time claim.** 3.89–4.13 FPS in the primary runs (2.62–3.73 in the superseded
  back-to-back set). Write *continuous camera-based detection* or *continuous on-device
  inference*, never *real-time detection*.
- **No accuracy claim from these frames.** One cluster, one camera position,
  consecutive frames. They are not an independent test set; no mAP, P or R is
  derived from them, and the 12 sampled frames are for qualitative display only.
- **No power claim.** Power was deliberately not measured; both the fuel-gauge
  limitation (charging readings are not attributable) and the decision to exclude
  it are in the engineering README. Do not derive efficiency from anything here.
- **No cross-platform latency comparison.** The iPhone 14 figures use a different
  end-to-end boundary (they include drawing) and a different runtime stack.
  Report the two platforms separately, and state that their absolute latencies
  are not directly compared.
- **No long-run stability claim.** 60 s per run. The most that can be said is
  that no inference failure was observed during the measurements.
