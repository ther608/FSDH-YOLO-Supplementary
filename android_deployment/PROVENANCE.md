# Provenance

Artefact hashes and the mapping between published files and the runs that produced them.

---

## 1. Source model

| Item | Value |
|---|---|
| Training run | `dsA_groupaware_clean_aug_v2__fsd_completion__fsd_shab__seed0` |
| Checkpoint | `weights/best.pt` |
| Checkpoint bytes | 6,331,272 |
| Checkpoint SHA-256 | `19675c53ada751302555211c6e912219a602834cf5bda647b7790f865e3dd630` |
| Architecture | `yolo11n_FSDConv-SHAB.yaml` — FSDConv ×1 at the input stage, SHEB ×3 (P3/P4/P5), nc = 3 |
| Parameters | 3,004,777 |

The checkpoint itself is **not** published (see the repository README, *Code availability*).
It belongs to the same training campaign as the seed-0 model reported in the paper's
main results tables.

---

## 2. Published ONNX models

| File | Bytes | SHA-256 |
|---|---:|---|
| `android_app/app/src/main/assets/fsdh_yolo_n_rerun_seed0_640_fp32.onnx` | 12,281,244 | `ca1c7063f89a6411bff1832471b3e1fa78b9db598dd0febf450b2946307fd23e` |
| `android_app/app/src/full/assets/yolo11n_baseline_640_fp32.onnx` | 10,605,814 | `91e96370402c9d2045ebf20125efd4728a92c6d6081251dc71610c57476dacf9` |

Export settings for both (see `scripts/export_onnx.py` and
`deployment_evaluation_results/raw_records/verification/export_record.json`):

```python
format="onnx", imgsz=640, batch=1, dynamic=False, half=False,
simplify=True, nms=False, opset=17, device="cpu"
```

| Graph property | Value |
|---|---|
| Opset | `ai.onnx` 17, IR version 8 |
| Input | `images` — `float32[1, 3, 640, 640]` |
| Output | `output0` — `float32[1, 7, 8400]` (3 classes + 4 box coordinates, no NMS in graph) |
| Nodes | 369 |

Toolchain at export time (from `export_record.json`):

| Component | Version |
|---|---|
| Ultralytics | 8.4.11 |
| PyTorch | 2.10.0+cu128 |
| onnx | 1.23.1 |
| onnxslim | 0.1.97 |
| onnxruntime | 1.23.2 |

### Note on the `exported/` copy

The original working directory also contained a separately re-simplified copy at
`exported/fsdh_yolo_n_rerun_seed0_640_fp32_opset17.onnx`
(SHA-256 `e6027a85173a259734f579660cc1797369179a739ecf4782b9bc401a13452fec`,
12,189,788 bytes, 239 instead of 240 initializers). That file was produced later for an
archived TFLite conversion route and **replaced** the file at that path; the export
record still describes the earlier artefact.

The published file here is the one that:

- matches `export_record.json` (`ca1c7063f89a6411…`, 12,281,244 bytes), and
- is the model actually bundled into the APK that produced the on-device measurements.

The two graphs were compared with ONNX Runtime on the same random input:
**max absolute output difference 0.0 — bitwise identical**. They differ only in how far
the simplifier folded constants, so publishing a single copy loses nothing. To regenerate
the re-simplified variant, re-run `scripts/export_onnx.py`.

---

## 3. Mapping: published records → paper tables

| Paper item | Source file |
|---|---|
| Table 13 (deployment validation results) | `deployment_evaluation_results/fixed_input_latency.csv` (derived from `deployment_evaluation_results/raw_records/bench/bench_fsdh_1791274886517.json`); the continuous-camera range from `deployment_evaluation_results/results_summary.json` → `primary.ranges` |
| Appendix B.5-1 (fixed-input ORT CPU) | same bench file, `summary[]` entries `FSDH-YOLO-n … ORT CPU` and `YOLO11n baseline … ORT CPU` |
| Appendix B.5-2 (L1 export equivalence) | `deployment_evaluation_results/verification_results.csv`, raw in `deployment_evaluation_results/raw_records/verification/rect_probe.json` |
| Appendix B.5-2 (L2 on-device decode) | `deployment_evaluation_results/verification_results.csv`, raw in `deployment_evaluation_results/raw_records/verification/decode_validation.json` |
| Appendix B.5-3 (three 60 s runs) | `deployment_evaluation_results/continuous_camera_sessions.csv`; per-frame detail in `deployment_evaluation_results/raw_records/sessions/run{1,2,3}/record.json` |
| Appendix B.5-4 (thermal state) | `deployment_evaluation_results/thermal_state.csv`, raw in `deployment_evaluation_results/raw_records/sessions/runlog_cooled.txt` |
| Appendix B.5-5 (post-hoc diagnostics) | `deployment_evaluation_results/continuous_camera_sessions.csv` (rows marked *post-hoc diagnostic*); raw in `deployment_evaluation_results/results_summary.json` → `diagnostics.runs` and `raw_records/sessions/slim_sanity*/`, `raw_records/sessions/full_recheck/` |
| Appendix B.5-6 (back-to-back control) | `deployment_evaluation_results/continuous_camera_sessions.csv` (rows marked *back-to-back control*); raw in `deployment_evaluation_results/raw_records/sessions/_superseded_backtoback/run{1,2,3}/record.json` |
| Appendix B.5-7 (deployment footprint) | `deployment_evaluation_results/deployment_footprint.md` |
| Figure 11 (app interface) | reproduced from the app itself; screenshots are not redistributed here |

Records whose folder name starts with `_superseded_` are retained because the paper
cites them (the back-to-back control and the pre-protocol run); they are provenance, not
primary results.

---

## 4. What was deliberately excluded

| Excluded | Reason |
|---|---|
| Root engineering log (41 KB developer diary) | process record, not paper material |
| `DEPLOYMENT_OPEN_ISSUES.md` | internal pre-submission checklist, explicitly marked "not paper text, do not submit" |
| iPhone / WebGPU / desktop-WebGPU chains (incl. `.github/workflows/coreml-placement.yml`, `webapp/`) | removed from the paper on 2026-10-08; see `deployment_evaluation_results/raw_records/verification/ARCHIVE_ios_removed_from_paper.md` |
| TFLite backend comparison (`.tflite` binaries, saved models) | removed from the paper; see `ARCHIVE_appendix_backend_comparison.md` |
| Per-frame PNG screenshots (≈11 MB per session) | not needed to reproduce any reported number |
| `weights/*.pt` | training artefact; export script included instead |
| `_scratch/` (debug images, superseded APKs, CoreML packages) | scratch space |
