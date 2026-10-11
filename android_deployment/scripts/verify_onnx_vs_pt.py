"""Prove the exported ONNX is numerically equivalent to the seed-2 .pt.

Two independent checks:

  (a) raw-tensor equivalence -- identical letterboxed input fed to the torch
      model and to onnxruntime, outputs compared elementwise.
  (b) end-to-end -- ultralytics `val` on the test split with identical
      arguments for .pt and .onnx, comparing mAP50 / mAP50-95.

Run:  D:\\pytorch_120\\envs\\yolo8\\python.exe verify_onnx_vs_pt.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

REPO = Path(r"D:\yolo\paper\general journal\ultralytics-main\ultralytics-main")
sys.path.insert(0, str(REPO))

import cv2  # noqa: E402
import numpy as np  # noqa: E402
import onnx  # noqa: E402
import onnxruntime as ort  # noqa: E402
import torch  # noqa: E402
from ultralytics import YOLO  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
PT = ROOT / "weights" / "fsdh_yolo_n_rerun_seed0.pt"
ONNX = ROOT / "exported" / "fsdh_yolo_n_rerun_seed0_640_fp32_opset17.onnx"
RECORDS = ROOT / "records"

DATA = Path(
    r"D:\yolo\data\An RGB-D Image Dataset for Lychee Detection and Ma"
    r"\An RGB-D Image Dataset for Lychee Detection and Ma"
    r"\An RGB-D Image Dataset for Lychee\lychee_groupaware_clean_aug_v2.yaml"
)
TEST_IMAGES = DATA.parent / "lychee_groupaware_clean_aug_v2" / "images" / "test"

IMGSZ = 640
N_RAW = 24            # images used for the raw-tensor check
VAL_ARGS = dict(split="test", imgsz=IMGSZ, conf=0.001, iou=0.7, max_det=300, batch=16, device="cpu", workers=0, plots=False, verbose=False)


def letterbox(im: np.ndarray, new_shape=(IMGSZ, IMGSZ), color=(114, 114, 114)) -> np.ndarray:
    """Ultralytics-standard resize+pad; returns a BGR image (as read by cv2)."""
    h, w = im.shape[:2]
    r = min(new_shape[0] / h, new_shape[1] / w)
    new_unpad = (int(round(w * r)), int(round(h * r)))
    dw, dh = (new_shape[1] - new_unpad[0]) / 2, (new_shape[0] - new_unpad[1]) / 2
    im = cv2.resize(im, new_unpad, interpolation=cv2.INTER_LINEAR)
    top, bottom = int(round(dh - 0.1)), int(round(dh + 0.1))
    left, right = int(round(dw - 0.1)), int(round(dw + 0.1))
    return cv2.copyMakeBorder(im, top, bottom, left, right, cv2.BORDER_CONSTANT, value=color)


def to_tensor(im_bgr: np.ndarray) -> torch.Tensor:
    """Match BasePredictor.preprocess: BGR->RGB, HWC->CHW, float32, /255."""
    x = im_bgr[..., ::-1].transpose(2, 0, 1)
    return torch.from_numpy(np.ascontiguousarray(x)).float().div(255).unsqueeze(0)


def torch_raw_output(model, x: torch.Tensor) -> torch.Tensor:
    with torch.no_grad():
        out = model(x)
    if isinstance(out, (tuple, list)):
        cands = [o for o in out if isinstance(o, torch.Tensor)]
        out = next((o for o in cands if o.ndim == 3), cands[0])
    return out


def check_a() -> dict:
    print("\n=== (a) raw-tensor equivalence ===")
    images = sorted(TEST_IMAGES.glob("*"))[:N_RAW]
    assert images, f"no test images under {TEST_IMAGES}"

    pt_model = YOLO(str(PT)).model.fuse().eval()
    sess = ort.InferenceSession(str(ONNX), providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name

    diffs, shapes = [], set()
    for p in images:
        im = cv2.imread(str(p))
        x = to_tensor(letterbox(im))
        y_pt = torch_raw_output(pt_model, x).numpy()
        y_ox = sess.run(None, {in_name: x.numpy()})[0]
        shapes.add((y_pt.shape, y_ox.shape))
        diffs.append(np.abs(y_pt - y_ox))

    d = np.concatenate([a.ravel() for a in diffs])
    # decoded-side check: how many predictions clear a deployment threshold,
    # and how far apart those coordinates are between the two backends
    thr = 0.25
    n_pt = n_ox = 0
    coord_gap = 0.0
    for p in images:
        im = cv2.imread(str(p))
        x = to_tensor(letterbox(im))
        y_pt = torch_raw_output(pt_model, x).numpy()[0]      # [7, 8400]
        y_ox = sess.run(None, {in_name: x.numpy()})[0][0]
        s_pt, s_ox = y_pt[4:].max(0), y_ox[4:].max(0)
        m = (s_pt > thr) | (s_ox > thr)
        n_pt += int((s_pt > thr).sum())
        n_ox += int((s_ox > thr).sum())
        if m.any():
            coord_gap = max(coord_gap, float(np.abs(y_pt[:4][:, m] - y_ox[:4][:, m]).max()))

    res = {
        "images_checked": len(images),
        "shapes": [list(map(list, s)) for s in shapes],
        "max_abs_diff": float(d.max()),
        "mean_abs_diff": float(d.mean()),
        "p99_abs_diff": float(np.percentile(d, 99)),
        "preds_above_conf0.25_pt": n_pt,
        "preds_above_conf0.25_onnx": n_ox,
        "max_box_coord_gap_px_above_conf0.25": coord_gap,
    }
    print(json.dumps(res, indent=2))
    return res


def check_b() -> dict:
    print("\n=== (b) end-to-end val on test split ===")
    out = {}
    for tag, src in (("pt", PT), ("onnx", ONNX)):
        m = YOLO(str(src))
        r = m.val(data=str(DATA), project=str(RECORDS / "val_runs"), name=tag, exist_ok=True, **VAL_ARGS)
        out[tag] = {
            "map50": float(r.box.map50),
            "map": float(r.box.map),
            "mp": float(r.box.mp),
            "mr": float(r.box.mr),
            "per_class_map50_95": [float(v) for v in r.box.maps],
            "speed_ms": {k: float(v) for k, v in r.speed.items()},
        }
        print(f"[{tag}] mAP50={out[tag]['map50']:.6f}  mAP50-95={out[tag]['map']:.6f}  P={out[tag]['mp']:.6f}  R={out[tag]['mr']:.6f}")
    out["delta"] = {
        "map50": out["onnx"]["map50"] - out["pt"]["map50"],
        "map50_95": out["onnx"]["map"] - out["pt"]["map"],
    }
    print(json.dumps(out["delta"], indent=2))
    return out


def main() -> int:
    RECORDS.mkdir(exist_ok=True)
    m = onnx.load(str(ONNX))
    onnx.checker.check_model(m)
    meta = {
        "opset": [(o.domain or "ai.onnx", o.version) for o in m.opset_import],
        "inputs": [(i.name, [d.dim_value or d.dim_param for d in i.type.tensor_type.shape.dim]) for i in m.graph.input],
        "outputs": [(o.name, [d.dim_value or d.dim_param for d in o.type.tensor_type.shape.dim]) for o in m.graph.output],
    }
    print(f"[i] onnx checker: PASS  {json.dumps(meta)}")

    report = {"artifact": str(ONNX), "onnx_meta": meta, "raw_tensor": check_a(), "end_to_end": check_b()}
    (RECORDS / "verification.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\n[+] report: {RECORDS / 'verification.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
