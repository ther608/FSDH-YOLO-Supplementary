# Scripts

These are the scripts that produced the export, the verification records and the
converted models in [`../measurement_records/`](../measurement_records). They are kept
**verbatim** — the same files that were run — so the recorded output can be traced back
to the exact code that generated it.

## Before running anything

Several scripts hardcode the path of the **training repository** (the working copy of
Ultralytics that contains the custom `FSDConv` and `SHEB` modules):

```python
REPO = Path(r"D:\yolo\paper\general journal\ultralytics-main\ultralytics-main")
```

This path was correct on the machine that produced the results and must be changed to
point at a local checkout of that repository. The affected scripts are:

| Script | Hardcoded path |
|---|---|
| `export_onnx.py` | training repo |
| `verify_onnx_vs_pt.py` | training repo |
| `validate_decode_logic.py` | training repo |
| `probe_rect_effect.py` | training repo |
| `op_inventory.py` | training repo, and the ONNX master under `exported/` |
| `convert_to_tflite.py` | a baseline ONNX in the sibling (retired) deployment folder |

Scripts that only use paths relative to this folder are `convert_to_coreml.py`,
`verify_tflite.py` and the two PowerShell scripts.

The PowerShell harness scripts under
[`../measurement_records/protocol/harness/`](../measurement_records/protocol/harness)
also reference `adb` and the package name of the installed build; see the
live-session protocol document alongside them.

## What each script does

| Script | Purpose |
|---|---|
| `export_onnx.py` | Export the trained checkpoint to ONNX (opset 17, static 640×640, batch 1, FP32, `nms=False`, `simplify=True`) and write `export_record.json` |
| `verify_onnx_vs_pt.py` | **L1** — compare `.pt` and `.onnx` on the full test split under a matched letterbox caliber; writes `verification.json` |
| `probe_rect_effect.py` | Resolve the `rect` caliber trap: `.pt` under the default `rect=True` is not the same letterbox as a static ONNX graph. Writes `rect_probe.json` |
| `validate_decode_logic.py` | **L2** — re-implement the Kotlin decode pipeline in Python and compare it box-by-box against the Ultralytics reference predictor; writes `decode_validation.json` |
| `op_inventory.py` | Inventory the ONNX operator set and count per-operator occurrences |
| `convert_to_tflite.py` | Convert the ONNX master to TFLite — an **archived** backend comparison, not part of the paper |
| `convert_to_coreml.py` | Convert to CoreML — **archived** Apple chain, removed from the paper |
| `verify_tflite.py` | Verify the TFLite conversion — archived |
| `build_apk.ps1` | Build and install the Android app |
| `setup_android_toolchain.ps1` | Set up the Android SDK / JDK toolchain on a fresh machine |

Archived scripts are kept for provenance only; see
[`../measurement_records/verification/ARCHIVE_ios_removed_from_paper.md`](../measurement_records/verification/ARCHIVE_ios_removed_from_paper.md)
and [`ARCHIVE_appendix_backend_comparison.md`](../measurement_records/verification/ARCHIVE_appendix_backend_comparison.md)
for why those chains were removed from the paper.

## Expected environment

The Python scripts require the training environment (PyTorch 2.10 +cu128, Ultralytics
8.4.11, `onnx` 1.23.1, `onnxruntime` 1.23.2, `onnxslim` 0.1.97). See
[`../../reproduction_notes/README.md`](../../reproduction_notes/README.md).
