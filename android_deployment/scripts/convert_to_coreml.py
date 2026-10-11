"""Feasibility probe for a native iOS deployment: ONNX chain -> CoreML.

coremltools cannot read ONNX, so this piggybacks on the TFLite work: onnx2tf
already emitted a TF SavedModel *with signaturedefs* (that is what
output_signaturedefs=True buys), and coremltools can read a SavedModel. No torch
install, no second graph to maintain.

WHAT THIS DOES AND DOES NOT ANSWER
----------------------------------
Answers:  does the graph survive CoreML conversion at all, and what does
          coremltools complain about while doing it?
Does NOT: whether the Neural Engine will actually run it. Op placement is a
          runtime decision made by the CoreML runtime, and coremltools' own
          predict()/compute-plan inspection is macOS-only. Windows can convert
          but cannot place.

That gap matters because of what the Android run just showed: the TFLite GPU
delegate *accepted* the FSDH graph, then quietly fell back to CPU for the three
SHAB `MEAN` ops and lost most of the acceleration. CoreML's op coverage is much
broader, so this is likely better -- but "likely" is not "measured", and the
number only exists once it runs on a device.

Run:
    D:\\tflite_convert\\venv\\Scripts\\python.exe scripts\\convert_to_coreml.py
"""

from __future__ import annotations

import json
import sys
import traceback
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPORTED = ROOT / "exported"
RECORDS = ROOT / "records"

MODELS = {
    "fsdh": EXPORTED / "fsdh_yolo_n_rerun_seed0_640_fp32_saved_model",
    "baseline": EXPORTED / "yolo11n_baseline_640_fp32_saved_model",
}


def inspect_signature(saved_dir: Path) -> dict:
    """What does onnx2tf's SavedModel actually expose?"""
    import tensorflow as tf

    loaded = tf.saved_model.load(str(saved_dir))
    sigs = list(loaded.signatures.keys())
    info = {"signatures": sigs}
    if sigs:
        fn = loaded.signatures[sigs[0]]
        info["inputs"] = {k: {"shape": v.shape.as_list(), "dtype": str(v.dtype)}
                          for k, v in fn.structured_input_signature[1].items()}
        info["outputs"] = {k: {"shape": v.shape.as_list(), "dtype": str(v.dtype)}
                           for k, v in fn.structured_outputs.items()}
    return info


def convert(saved_dir: Path, out_path: Path) -> dict:
    import numpy as np
    import coremltools as ct

    sig = inspect_signature(saved_dir)
    print(f"[i] SavedModel signature: {json.dumps(sig, ensure_ascii=False)}")

    first_input = next(iter(sig["inputs"].values()))
    shape = first_input["shape"]

    print("[i] coremltools.convert (mlprogram, fp32) ...")
    mlmodel = ct.convert(
        str(saved_dir),
        source="tensorflow",
        convert_to="mlprogram",              # required for ANE; the legacy
                                             # neuralnetwork format is not the
                                             # path Apple maintains now
        minimum_deployment_target=ct.target.iOS16,
        inputs=[ct.TensorType(name="images", shape=tuple(shape), dtype=np.float32)],
        compute_precision=ct.precision.FLOAT32,
    )
    mlmodel.save(str(out_path))
    print(f"[+] saved {out_path}")

    spec = mlmodel.get_spec()
    ops = Counter()
    try:
        for f in spec.mlProgram.functions.values():
            for op in f.block.operations:
                ops[op.type] += 1
    except Exception as e:
        ops["<unavailable: %s>" % e] = 0

    print(f"[i] mlprogram op histogram: {dict(ops.most_common())}")

    return {
        "saved_model": str(saved_dir),
        "artifact": str(out_path),
        "artifact_bytes": out_path.stat().st_size if out_path.exists() else None,
        "signature": sig,
        "converted_input_shape": shape,
        "coreml_op_histogram": dict(ops),
        "coremltools_version": ct.__version__,
        "note": (
            "Conversion only. Op placement / ANE usage is decided by the CoreML "
            "runtime on device and cannot be inspected from Windows."
        ),
    }


def main() -> int:
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", action="store_true")
    args = ap.parse_args()

    tag = "baseline" if args.baseline else "fsdh"
    saved = MODELS[tag]
    if not saved.is_dir():
        print(f"[!] no SavedModel at {saved} -- run convert_to_tflite.py first")
        return 1

    out = EXPORTED / f"{saved.name.replace('_saved_model', '')}.mlpackage"
    RECORDS.mkdir(exist_ok=True)

    try:
        info = convert(saved, out)
    except Exception:
        traceback.print_exc()
        print(f"\n[!] CoreML conversion FAILED for {tag}")
        return 1

    (RECORDS / f"coreml_convert_{tag}.json").write_text(
        json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"\n[+] {out}  ({info['artifact_bytes']/1e6:.2f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
