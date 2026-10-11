"""Does the .tflite still compute what the .onnx computes?

The .onnx inherited an exact equivalence proof against the .pt. The .tflite does
NOT -- it is a second, independent conversion, and onnx2tf is free to reorder
transposes or fold reshapes. Any latency number from the TFLite path is only
interpretable alongside an accuracy number, so this checks the two graphs agree
before the GPU/CPU comparison is worth running.

Handles the layout changes onnx2tf is allowed to make: the input may come back
NHWC instead of NCHW, and the output may be (1, 8400, 7) instead of (1, 7, 8400).
Both are normalised before comparison, and which one happened is reported.

Run with the conversion venv:
    D:\\tflite_convert\\venv\\Scripts\\python.exe scripts\\verify_tflite.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ONNX = ROOT / "exported" / "fsdh_yolo_n_rerun_seed0_640_fp32_opset17.onnx"
TFLITE = ROOT / "exported" / "fsdh_yolo_n_rerun_seed0_640_fp32.tflite"
RECORDS = ROOT / "records"

DATA = Path(
    r"D:\yolo\data\An RGB-D Image Dataset for Lychee Detection and Ma"
    r"\An RGB-D Image Dataset for Lychee Detection and Ma"
    r"\An RGB-D Image Dataset for Lychee\lychee_groupaware_clean_aug_v2"
)

IMGSZ = 640
N_IMAGES = 12
CONF = 0.25


def letterbox(im):
    h, w = im.shape[:2]
    r = min(IMGSZ / h, IMGSZ / w)
    nw, nh = int(round(w * r)), int(round(h * r))
    left = int(round((IMGSZ - nw) / 2 - 0.1))
    top = int(round((IMGSZ - nh) / 2 - 0.1))
    out = np.full((IMGSZ, IMGSZ, 3), 114, np.uint8)
    out[top:top + nh, left:left + nw] = cv2.resize(im, (nw, nh), interpolation=cv2.INTER_LINEAR)
    return out


def to_nchw(bgr):
    return (np.ascontiguousarray(bgr[..., ::-1].transpose(2, 0, 1))[None]
            .astype(np.float32) / 255.0)


def main() -> int:
    global np, cv2
    import cv2
    import numpy as np
    import onnxruntime as ort
    import tensorflow as tf

    for p in (ONNX, TFLITE):
        assert p.is_file(), f"missing {p}"

    sess = ort.InferenceSession(str(ONNX), providers=["CPUExecutionProvider"])
    onnx_in = sess.get_inputs()[0].name

    interp = tf.lite.Interpreter(model_path=str(TFLITE))
    interp.allocate_tensors()
    t_in = interp.get_input_details()[0]
    t_out = interp.get_output_details()[0]
    t_in_shape = list(t_in["shape"])
    t_out_shape = list(t_out["shape"])

    # onnx2tf may hand back NHWC; detect and adapt rather than assume
    input_is_nhwc = t_in_shape[-1] == 3 and t_in_shape[1] != 3
    output_is_anchors_last = t_out_shape[-1] == 7 and t_out_shape[1] != 7

    images = sorted((DATA / "images" / "test").glob("*.jpg"))[:N_IMAGES]
    assert images, "no test images"

    diffs = []
    preds = {"onnx": 0, "tflite": 0}
    max_coord_gap = 0.0

    for p in images:
        im = cv2.imread(str(p))
        lb = letterbox(im)
        x = to_nchw(lb)

        y_onnx = sess.run(None, {onnx_in: x})[0][0]           # (7, 8400)

        t_input = np.transpose(x, (0, 2, 3, 1)) if input_is_nhwc else x
        interp.set_tensor(t_in["index"], np.ascontiguousarray(t_input))
        interp.invoke()
        y_t = interp.get_tensor(t_out["index"])
        y_t = y_t[0]
        if output_is_anchors_last:
            y_t = y_t.T                                       # -> (7, 8400)

        diffs.append(np.abs(y_onnx - y_t))

        # decoded-side agreement at a deployment threshold
        s_o = y_onnx[4:].max(0)
        s_t = y_t[4:].max(0)
        preds["onnx"] += int((s_o > CONF).sum())
        preds["tflite"] += int((s_t > CONF).sum())
        m = (s_o > CONF) | (s_t > CONF)
        if m.any():
            max_coord_gap = max(
                max_coord_gap,
                float(np.abs(y_onnx[:4][:, m] - y_t[:4][:, m]).max()),
            )

    d = np.concatenate([a.ravel() for a in diffs])
    report = {
        "onnx": str(ONNX),
        "tflite": str(TFLITE),
        "images": len(images),
        # TFLite reports shapes with numpy ints, which json cannot encode
        "tflite_input_shape": [int(d) for d in t_in_shape],
        "tflite_output_shape": [int(d) for d in t_out_shape],
        "input_layout_converted": "NHWC" if input_is_nhwc else "NCHW",
        "output_layout_converted": "anchors-last" if output_is_anchors_last else "channels-first",
        "max_abs_diff": float(d.max()),
        "mean_abs_diff": float(d.mean()),
        "p99_abs_diff": float(np.percentile(d, 99)),
        "preds_above_conf_onnx": preds["onnx"],
        "preds_above_conf_tflite": preds["tflite"],
        "max_box_coord_gap_px": max_coord_gap,
    }
    print(json.dumps(report, indent=2))
    RECORDS.mkdir(exist_ok=True)
    (RECORDS / "tflite_verification.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"\n[+] {RECORDS / 'tflite_verification.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
