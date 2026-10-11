# Deployment footprint: APK size and memory

Two builds from one source tree, with **fixed and separate roles**:

| | `full` (control package) | `slim` (deployment package) |
|---|---|---|
| Used for | **all performance measurement**, backend comparison | **APK size and memory only** |
| Runtimes | ONNX Runtime + TensorFlow Lite | ONNX Runtime only |
| Model assets | FSDH + YOLO11n baseline, ONNX + TFLite | the single deployed model (ONNX) |
| Performance conclusions drawn | **yes** | **no — none at all** |

**No FPS or latency comparison is made between the two packages** — the build effect
and the session drift are confounded (see the last section).

Both are **debug** builds. These are the numbers to quote only with that stated.

---

## APK size (debug, arm64-v8a + armeabi-v7a)

Sizes are **MiB** (1 MiB = 1 048 576 B) throughout.

| Part | full | slim |
|---|---:|---:|
| `lib/` (native) | 49.1 MiB | 30.1 MiB |
| `assets/` (models) | 43.5 MiB | 11.7 MiB |
| `classes*.dex` | 12.0 MiB | 12.0 MiB |
| `res/` + `resources.arsc` | 1.6 MiB | 1.6 MiB |
| **Total** | **99.1 MiB** | **48.2 MiB** |

What leaves the slim build:

- `assets/` — the YOLO11n ONNX (10.6 MiB) and both `.tflite` conversions
  (12.2 + 10.5 MiB). Only `fsdh_yolo_n_rerun_seed0_640_fp32.onnx` (11.7 MiB) stays.
- `lib/` — `libtensorflowlite_jni.so`, `libtensorflowlite_gpu_jni.so`,
  `libgpu_delegate_plugin.so`. What stays: `libonnxruntime.so` (28.5 MiB, ×2 ABIs),
  `libonnxruntime4j_jni.so` (1.5 MiB, ×2), `libimage_processing_util_jni.so`.

Model file on its own: **12 281 244 B = 11.71 MiB** (ONNX fp32, static 640×640, batch 1).

Two further levers, not applied here: an **arm64-only** ABI filter would roughly
halve `lib/` (30 → ~15 MiB), and a **release** build with R8 enabled would shrink
the dex. Neither changes the inference path.

---

## Memory (`adb shell dumpsys meminfo com.lychee.fsdh`)

Sampled ~25 s after launch, camera running, engine loaded, inference in steady
state — i.e. after the model is resident, not at startup.

**Report ranges, not single values.** Across four samples the PSS spread is about
15 MiB and comes from GC timing, not from a state difference.

| | full | slim |
|---|---:|---:|
| **TOTAL PSS** | ~231 MiB | **~209–218 MiB** (after model init) |
| TOTAL RSS | ~355 MiB | ~333–346 MiB |
| Native Heap (PSS) | ~128 MiB | ~119–126 MiB |
| Java Heap (PSS) | ~42 MiB | ~25–37 MiB |
| Graphics (PSS) | ~10 MiB | ~10–13 MiB |

After a 60 s session the slim build was sampled at **PSS ~215–242 MiB / RSS
~346–373 MiB** — the runtime's arena has grown and buffers are held.
**Always state when the sample was taken**; a reading after a run and a reading at
startup are tens of MiB apart. Peak memory is **not** reported, because sampling was
not continuous over the 60 s.

### The result that matters

**A 51 % smaller APK does not translate into a proportional memory reduction.**
The native libraries removed from the slim build are never `dlopen`ed by the full
build either — TFLite is only loaded when a TFLite engine is actually constructed,
and the deployment path never constructs one. Unloaded native libraries cost disk,
not RAM.

**But do not quantify the memory difference.** The PSS sampling spread (~15 MiB
across four samples, driven by GC timing) is the same order as the difference
between the two packages. Report the effect qualitatively — trimming affects
**storage**, not a measurable fraction of resident memory — and do **not** state a
"memory reduced by X %" figure. The paper material is written this way on purpose.

### What actually dominates

Native Heap, ~119–126 MiB in the slim build, against an 11.71 MiB model file. It is
the runtime's allocation arena plus CameraX frame buffers, not the weights.
Reporting "memory footprint ≈ 210 MiB" without that note invites the reading that
the model needs 210 MiB, which it does not.

Report PSS as a **range, not a single value**: 209–218 MiB after model
initialization, 215–242 MiB after a 60 s session. The spread is GC timing, not a
state difference. Peak memory is not reported — sampling was not continuous.

---

## Reproducing

```powershell
powershell -File scripts\build_apk.ps1 -Flavor all     # both APKs
adb install -r android\app\build\outputs\apk\slim\debug\app-slim-debug.apk
adb shell am start -n com.lychee.fsdh/.MainActivity
# wait ~25 s for stable inference, then:
adb shell dumpsys meminfo com.lychee.fsdh
```

**No performance re-measurement is implied, and no equivalence is claimed.**
Removing the TFLite dependency is a build-configuration change, so two 60 s sessions
were run on the slim build (`raw_records/sessions/slim_sanity/`, `raw_records/sessions/slim_sanity2/`).
Their latencies (272.99 and 313.59 ms) sit **above** the primary three-run range
(242.34–257.09 ms), which prompted a third run on the **full** build
(`raw_records/sessions/full_recheck/`, 294.41 ms).

That recheck is the same build as primary run 1 and, at a pre-timing SKIN only
0.20 °C higher, measured 21.5 % slower about 16 min later. So the build effect and
the session drift are **confounded** and cannot be separated here.

Consequences, which the paper material follows:

- the slim sessions are **post-hoc diagnostics, not results**;
- **no equivalence conclusion** is drawn between the two packages, and it also
  cannot be claimed that the slowdown was caused by the slim build;
- the slim package is used **only** for APK size and memory, and takes part in
  **no** FPS or latency comparison;
- every reported latency figure comes from the **full** build (runs 1–3).
