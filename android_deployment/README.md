# Android on-device deployment

Materials for §3.9 (Mobile deployment validation), Appendix A.7 (Deployment evaluation
protocol) and Appendix B.5 (Deployment supplementary experiments) of the manuscript.

The deployment chain is:

> **PyTorch → ONNX (opset 17, static 640×640, FP32, no in-graph NMS) → Android + ONNX Runtime CPU**

| | |
|---|---|
| Device | Redmi K40 Gaming Enhanced Edition, model `M2012K10C` (`ares`) |
| SoC | MediaTek Dimensity 1200 (MT6893), 8 cores, 12 GB RAM |
| OS | Android 13 (API 33) |
| Runtime | ONNX Runtime Android 1.19.2, CPU execution provider |
| Threads | `intra_op_num_threads = 4`, `inter_op_num_threads = 1` |
| App operating point | conf 0.25 / NMS IoU 0.45 / max 300 detections |

---

## Contents

```
android_deployment/
├── paper_texts/                    the three paper sections, verbatim (Chinese)
│   ├── section_3.9_mobile_deployment.md
│   ├── appendix_A7_deployment_protocol.md
│   └── appendix_B5_deployment_experiments.md
│
├── android_app/                    Android Studio project (Kotlin)
│   ├── app/src/main/                 shared UI, camera, preprocessing, ORT engine, decoder
│   ├── app/src/full/                 control build: ORT + TFLite backends, baseline model
│   ├── app/src/slim/                 deployment build: ORT + final ONNX model only
│   └── app/src/main/assets/          the deployed ONNX model
│
├── deployment_evaluation_results/    the measured results, as summary tables
│   ├── fixed_input_latency.csv         Appendix B.5-1
│   ├── continuous_camera_sessions.csv  Appendix B.5-3 / B.5-5 / B.5-6
│   ├── thermal_state.csv               Appendix B.5-4
│   ├── verification_results.csv        Appendix B.5-2
│   ├── deployment_footprint.md         Appendix B.5-7
│   ├── results_summary.json            authoritative summary written by the harness
│   └── raw_records/                    unprocessed sessions, bench runs, harness, verification
│
├── scripts/                        export, conversion, and verification scripts
└── PROVENANCE.md                   artefact hashes and source-run mapping
```

---

## The two builds (Appendix A.7.3)

The app is generated from one source tree in two variants:

| Variant | Contents | Reported use |
|---|---|---|
| **full** (control package) | ONNX Runtime + TensorFlow Lite, baseline and improved models | all fixed-input and continuous-camera performance measurements, plus the YOLO11n baseline |
| **slim** (deployment package) | ONNX Runtime + the final ONNX model only | APK size and runtime memory footprint |

The paper deliberately makes **no FPS or latency comparison between the two builds**,
because in the post-hoc tests the build difference and the device session drift were
confounded.

> **Note on the TFLite backends.** The `full` variant's Kotlin sources reference TFLite,
> which was part of an archived backend comparison that is **not** in the paper. The
> `.tflite` binaries are omitted from this repository to keep it small; regenerate them
> with `scripts/convert_to_tflite.py` if you need the full build. The paper's chain uses
> **ONNX Runtime only**.

---

## Key results

Fixed input (Appendix B.5.1) — pipeline latency
`T_pipeline = T_preprocess + T_inference + T_decode/NMS`, excluding rendering:

| Model | T_pipeline / ms | SD | FPS | pre / infer / post / ms | mean detections |
|---|---:|---:|---:|---|---:|
| YOLO11n baseline | 128.8 | 6.9 | 7.76 | 5.1 / 123.3 / 0.4 | 23.7 |
| **FSDH-YOLO-n** (deployed) | **157.5** | 8.3 | **6.35** | 5.5 / 151.5 / 0.5 | 25.9 |

The same-session paired latency ratio is **R = 1.22**, comparable to the ≈1.19 ratio of
theoretical compute between the two models.

Continuous camera, 3 × 60 s (Appendix B.5.3, Table B.5-3):

| Run | Pre-timing SKIN / °C | Valid frames | T_E2E mean ± SD / ms | P95 / ms | FPS |
|---|---:|---:|---:|---:|---:|
| 1 | 53.87 | 210 | 242.34 ± 17.86 | 284.63 | 4.13 |
| 2 | 53.75 | 199 | 255.11 ± 20.66 | 305.54 | 3.92 |
| 3 | 53.72 | 197 | 257.09 ± 18.09 | 292.96 | 3.89 |
| **range** | 53.72 – 53.87 | **606 total** | **242.34 – 257.09** | 284.63 – 305.54 | **3.89 – 4.13** |

Verification chain (Appendix A.7.4 / B.5.2):

| Level | Comparison | Result |
|---|---|---|
| L1 — export equivalence | `.pt` ↔ `.onnx`, full test split, same letterbox caliber | mAP50 0.855298 / mAP50-95 0.709519, **identical to 6 decimals** including per-class AP |
| L2 — on-device pipeline equivalence | Kotlin decode pipeline ↔ Ultralytics reference, 12 test images | **207 / 207** detections, 12/12 images matched, **0 class mismatches**, max box gap 1.0 px (mean 0.28 px) |

> **L1 caliber caveat.** `.pt` under Ultralytics' default `rect=True` uses rectangular
> batching, which is *not* the same letterbox as a static 640×640 ONNX graph. Comparing
> them directly yields a difference unrelated to the export. L1 must therefore be run
> between `.pt rect=False` and the ONNX model. This is stated in Appendix A.7.4 and
> B.5.2 of the manuscript.

---

## Reproducing

```bash
# 1. export ONNX from a trained checkpoint (requires the training environment)
python scripts/export_onnx.py

# 2. verify export equivalence on the full test split
python scripts/verify_onnx_vs_pt.py

# 3. verify the on-device decode pipeline against the Ultralytics reference
python scripts/validate_decode_logic.py

# 4. build and install the Android app
#    (see scripts/build_apk.ps1 and scripts/setup_android_toolchain.ps1)
```

See [`../reproduction_notes/README.md`](../reproduction_notes/README.md) for the full
environment description, and [`PROVENANCE.md`](PROVENANCE.md) for artefact hashes.

---

## Reading the results

[`deployment_evaluation_results/`](deployment_evaluation_results) holds the measured
results as summary tables, one per paper item, each derived from the raw device records
rather than transcribed. Read its README first: the `section` column of
`continuous_camera_sessions.csv` separates the **three predefined runs that are the
reported result** from the post-hoc diagnostics and the back-to-back control, which are
**not** performance results.

The most important caveats:

- Session-level results are **never pooled across sessions**; each 60 s run is reported
  on its own.
- The app-level `PowerManager.getCurrentThermalStatus()` kept returning `NONE` while the
  HAL SKIN sensor reported a high thermal state; it is a record field only and **is not
  evidence that thermal conditions were controlled**.
- The unprocessed material lives under
  [`deployment_evaluation_results/raw_records/`](deployment_evaluation_results/raw_records);
  its README lists the caveats that apply before interpreting it.
