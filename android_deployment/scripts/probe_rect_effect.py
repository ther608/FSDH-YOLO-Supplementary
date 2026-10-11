"""Isolate the source of the .pt vs .onnx mAP gap: is it the export, or the
evaluator's `rect` preprocessing behaving differently per backend?

Runs the same test-split validation four ways:
    pt   rect=True   (ultralytics default; matches the paper's protocol)
    pt   rect=False  (fixed 640x640 letterbox -- the deployment setting)
    onnx rect=True
    onnx rect=False

Run:  D:\\pytorch_120\\envs\\yolo8\\python.exe probe_rect_effect.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

REPO = Path(r"D:\yolo\paper\general journal\ultralytics-main\ultralytics-main")
sys.path.insert(0, str(REPO))

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

BASE = dict(split="test", imgsz=640, conf=0.001, iou=0.7, max_det=300, batch=16, device="cpu", workers=0, plots=False, verbose=False)

CASES = [("pt", PT, True), ("pt", PT, False), ("onnx", ONNX, True), ("onnx", ONNX, False)]


def main() -> int:
    out = {}
    for tag, src, rect in CASES:
        name = f"{tag}_rect{int(rect)}"
        r = YOLO(str(src)).val(data=str(DATA), project=str(RECORDS / "rect_probe"), name=name, exist_ok=True, rect=rect, **BASE)
        out[name] = {
            "map50": round(float(r.box.map50), 6),
            "map50_95": round(float(r.box.map), 6),
            "per_class": [round(float(v), 6) for v in r.box.maps],
            "inference_ms": round(float(r.speed["inference"]), 2),
        }
        print(f"{name:14s} mAP50={out[name]['map50']:.6f}  mAP50-95={out[name]['map50_95']:.6f}  {out[name]['per_class']}")
    (RECORDS / "rect_probe.json").write_text(json.dumps(out, indent=2), encoding="utf-8")
    print(f"\n[+] {RECORDS / 'rect_probe.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
