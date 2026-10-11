"""ONNX -> TFLite (SavedModel -> .tflite) via onnx2tf.

Feeds the Arm GPU-delegate comparison, which is the one Android execution path
the ONNX Runtime build cannot reach: ORT's NNAPI provider turned out to be ~2.2x
slower than plain CPU on the test device, and ORT Android ships no GPU delegate
of its own.

This is a SECOND conversion, so unlike the .onnx it does NOT inherit the exact
equivalence proof against the .pt. Anything from this path must be re-verified
(scripts/verify_tflite.py) before its numbers are quoted.

Run with the conversion venv, which is deliberately separate from the training
env so that installing TensorFlow cannot disturb numpy/protobuf there:

    D:\\tflite_convert\\venv\\Scripts\\python.exe scripts\\convert_to_tflite.py
    D:\\tflite_convert\\venv\\Scripts\\python.exe scripts\\convert_to_tflite.py --baseline
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPORTED = ROOT / "exported"
RECORDS = ROOT / "records"
IMGSZ = 640

DATA = Path(
    r"D:\yolo\data\An RGB-D Image Dataset for Lychee Detection and Ma"
    r"\An RGB-D Image Dataset for Lychee Detection and Ma"
    r"\An RGB-D Image Dataset for Lychee\lychee_groupaware_clean_aug_v2"
)


def letterbox(im):
    """Same rounding as the app and the other export scripts."""
    import cv2
    import numpy as np
    h, w = im.shape[:2]
    r = min(IMGSZ / h, IMGSZ / w)
    nw, nh = int(round(w * r)), int(round(h * r))
    left = int(round((IMGSZ - nw) / 2 - 0.1))
    top = int(round((IMGSZ - nh) / 2 - 0.1))
    out = np.full((IMGSZ, IMGSZ, 3), 114, np.uint8)
    out[top:top + nh, left:left + nw] = cv2.resize(im, (nw, nh), interpolation=cv2.INTER_LINEAR)
    return out

def _prepare_env() -> None:
    """Two environment fixes onnx2tf needs on Windows.

    1. It shells out to the `onnxsim` executable, which lives in the venv's
       Scripts directory. Running the venv's python by absolute path does NOT
       put that directory on PATH, so the call raised WinError 2 and the
       simplification silently degraded to a warning.
    2. `download_test_image_data()` loads a bundled .npy that contains pickled
       data; current numpy refuses that with allow_pickle=False and the whole
       conversion aborts. The array is only consumed by an output-comparison
       check that is off by default, so supplying a real letterboxed test image
       in its place is both sufficient and more meaningful than a dummy.
    """
    import os
    venv_scripts = Path(sys.executable).parent
    os.environ["PATH"] = str(venv_scripts) + os.pathsep + os.environ.get("PATH", "")

    import numpy as np
    import onnx2tf.onnx2tf as o2t

    def _test_data() -> "np.ndarray":
        # Deterministic (not constant -- a constant input can hide shape bugs
        # in some ops) and dependency-free. Installing opencv into this venv
        # just to feed a disabled comparison check is not worth it.
        rng = np.random.default_rng(0)
        return rng.random((1, IMGSZ, IMGSZ, 3), dtype=np.float32)

    o2t.download_test_image_data = _test_data


def convert_one(onnx_path: Path, tflite_path: Path) -> dict:
    import onnx2tf
    import tensorflow as tf

    _prepare_env()

    saved_dir = EXPORTED / (tflite_path.stem + "_saved_model")
    if saved_dir.exists():
        shutil.rmtree(saved_dir)

    print(f"[i] onnx2tf: {onnx_path.name} -> {saved_dir.name}")
    onnx2tf.convert(
        input_onnx_file_path=str(onnx_path),
        output_folder_path=str(saved_dir),
        non_verbose=False,
        # Required: without it the SavedModel writer rejects ONNX node names
        # such as `/model.12/m/m.0/attn/pe/conv/Conv/kernel`, which do not match
        # TensorFlow's `^[A-Za-z0-9.][A-Za-z0-9_.\/>-]*$` pattern. All 370 ops
        # convert fine; only the write-out step needs this.
        output_signaturedefs=True,
        # The GPU delegate is the whole point of this path, so ask onnx2tf to
        # report up front whether the graph is even eligible for it. A "no"
        # here is exactly the negative result worth recording.
        check_gpu_delegate_compatibility=True,
    )

    print("[i] TFLiteConverter: SavedModel -> .tflite")
    converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_dir))
    # keep fp32 weights: the GPU delegate does its own fp16 internally, and
    # quantising here would confound "GPU vs CPU" with "fp32 vs fp16"
    converter.optimizations = []
    converter.target_spec.supported_types = [tf.float32]
    tflite_model = converter.convert()

    tflite_path.write_bytes(tflite_model)

    interp = tf.lite.Interpreter(model_content=tflite_model)
    in_det = interp.get_input_details()[0]
    out_det = interp.get_output_details()[0]

    # numpy int32 is not JSON serialisable; TFLite reports its shapes with it
    info = {
        "source_onnx": str(onnx_path),
        "artifact": str(tflite_path),
        "artifact_bytes": int(len(tflite_model)),
        "input": {
            "name": str(in_det["name"]),
            "shape": [int(d) for d in in_det["shape"]],
            "dtype": str(in_det["dtype"]),
        },
        "output": {
            "name": str(out_det["name"]),
            "shape": [int(d) for d in out_det["shape"]],
            "dtype": str(out_det["dtype"]),
        },
        "tf_version": tf.__version__,
    }
    print(json.dumps(info, indent=2))
    return info


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx", type=Path, default=None)
    ap.add_argument("--out", type=Path, default=None)
    ap.add_argument("--baseline", action="store_true", help="convert the YOLO11n comparison model")
    args = ap.parse_args()

    RECORDS.mkdir(exist_ok=True)

    if args.baseline:
        src = Path(r"D:\yolo\paper\general journal\ultralytics-main\deploy_fsdh_yolo_n_android\exported\yolo11n_baseline_seed2_640_fp32_opset17.onnx")
        dst = EXPORTED / "yolo11n_baseline_640_fp32.tflite"
        tag = "baseline"
    else:
        src = args.onnx or EXPORTED / "fsdh_yolo_n_rerun_seed0_640_fp32_opset17.onnx"
        dst = args.out or EXPORTED / "fsdh_yolo_n_rerun_seed0_640_fp32.tflite"
        tag = "fsdh"

    assert src.is_file(), f"missing onnx: {src}"

    try:
        info = convert_one(src, dst)
    except Exception:
        traceback.print_exc()
        print(f"\n[!] conversion FAILED for {tag}")
        return 1

    (RECORDS / f"tflite_convert_{tag}.json").write_text(
        json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"\n[+] {dst}  ({info['artifact_bytes']/1e6:.2f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
