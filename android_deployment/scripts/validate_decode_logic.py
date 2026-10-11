"""Prove the Android decoder is correct BEFORE it ever reaches a phone.

The Kotlin side (infer/Detector.kt + infer/Preprocessor.kt) makes three
assumptions about the exported graph:

  1. rows 0..3 of the (1,7,8400) output are cx,cy,w,h ALREADY in input-pixel
     space (no anchor decoding needed),
  2. rows 4..6 are class scores ALREADY sigmoid-ed (no sigmoid needed),
  3. NMS is class-aware, matching ultralytics' agnostic=False default.

If any of those is wrong the app still runs and still draws boxes -- it just
draws the wrong ones, which is the worst possible failure mode for a paper.

So this script re-implements the exact Kotlin pipeline in Python (same
letterbox rounding, same inverse transform, same NMS) and compares its output
against ultralytics' own predictor on the same images. Agreement to sub-pixel
means the decoder is right.

Run:  D:\\pytorch_120\\envs\\yolo8\\python.exe validate_decode_logic.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

REPO = Path(r"D:\yolo\paper\general journal\ultralytics-main\ultralytics-main")
sys.path.insert(0, str(REPO))

import cv2  # noqa: E402
import numpy as np  # noqa: E402
import onnxruntime as ort  # noqa: E402
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

IMGSZ = 640
CONF = 0.25
IOU = 0.7
MAX_DET = 300
N_IMAGES = 12


# --------------------------------------------------------------------------
# Kotlin-equivalent pipeline
# --------------------------------------------------------------------------
def letterbox_as_kotlin(im: np.ndarray):
    """Mirrors Preprocessor.letterbox(): same rounding, and it reports the pad
    that was ACTUALLY applied (integer) rather than the nominal float pad."""
    h, w = im.shape[:2]
    r = min(IMGSZ / h, IMGSZ / w)

    new_w = int(round(w * r))
    new_h = int(round(h * r))

    pad_x = (IMGSZ - new_w) / 2.0
    pad_y = (IMGSZ - new_h) / 2.0
    left = int(round(pad_x - 0.1))
    top = int(round(pad_y - 0.1))

    resized = cv2.resize(im, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    out = np.full((IMGSZ, IMGSZ, 3), 114, dtype=np.uint8)
    out[top:top + new_h, left:left + new_w] = resized
    return out, r, float(left), float(top)


def to_tensor_as_kotlin(bgr: np.ndarray) -> np.ndarray:
    """RGB, CHW, float32, /255."""
    x = bgr[..., ::-1].transpose(2, 0, 1)
    return np.ascontiguousarray(x)[None].astype(np.float32) / 255.0


def nms_class_aware(boxes, scores, classes, iou_thr, max_det):
    """Greedy class-aware NMS -- same semantics as Detector.decode()."""
    order = np.argsort(-scores)
    suppressed = np.zeros(len(order), dtype=bool)
    keep = []

    def iou(a, b):
        x1 = max(a[0], b[0]); y1 = max(a[1], b[1])
        x2 = min(a[2], b[2]); y2 = min(a[3], b[3])
        iw = x2 - x1; ih = y2 - y1
        if iw <= 0 or ih <= 0:
            return 0.0
        inter = iw * ih
        ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return inter / ua if ua > 0 else 0.0

    for i in range(len(order)):
        if suppressed[i]:
            continue
        ai = order[i]
        keep.append(ai)
        if len(keep) >= max_det:
            break
        for j in range(i + 1, len(order)):
            if suppressed[j]:
                continue
            aj = order[j]
            if classes[ai] != classes[aj]:
                continue
            if iou(boxes[ai], boxes[aj]) > iou_thr:
                suppressed[j] = True
    return keep


def decode_as_kotlin(raw: np.ndarray, scale, pad_x, pad_y, src_w, src_h):
    """raw: (7, 8400). Returns (boxes_xyxy, scores, classes) in source pixels."""
    n_anchors = raw.shape[1]
    cx, cy, w, h = raw[0], raw[1], raw[2], raw[3]
    cls_scores = raw[4:]                       # already sigmoid-ed
    cls_id = np.argmax(cls_scores, axis=0)
    best = cls_scores[cls_id, np.arange(n_anchors)]

    mask = best > CONF
    if not mask.any():
        return np.zeros((0, 4)), np.zeros(0), np.zeros(0, dtype=int)

    cx, cy, w, h = cx[mask], cy[mask], w[mask], h[mask]
    scores = best[mask]
    classes = cls_id[mask]

    x1 = (cx - w / 2 - pad_x) / scale
    y1 = (cy - h / 2 - pad_y) / scale
    x2 = (cx + w / 2 - pad_x) / scale
    y2 = (cy + h / 2 - pad_y) / scale

    x1 = np.clip(x1, 0, src_w - 1); y1 = np.clip(y1, 0, src_h - 1)
    x2 = np.clip(x2, 0, src_w - 1); y2 = np.clip(y2, 0, src_h - 1)

    boxes = np.stack([x1, y1, x2, y2], axis=1)
    keep = nms_class_aware(boxes, scores, classes, IOU, MAX_DET)
    return boxes[keep], scores[keep], classes[keep]


def main() -> int:
    test_dir = DATA.parent / "lychee_groupaware_clean_aug_v2" / "images" / "test"
    images = sorted(test_dir.glob("*"))[:N_IMAGES]
    assert images, f"no images under {test_dir}"

    sess = ort.InferenceSession(str(ONNX), providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    predictor = YOLO(str(PT))

    per_image = []
    all_box_gaps = []
    total_mine = total_ref = 0
    class_mismatch = 0

    for p in images:
        im = cv2.imread(str(p))
        src_h, src_w = im.shape[:2]

        lb, scale, pad_x, pad_y = letterbox_as_kotlin(im)
        raw = sess.run(None, {in_name: to_tensor_as_kotlin(lb)})[0][0]   # (7,8400)
        my_boxes, my_scores, my_classes = decode_as_kotlin(
            raw, scale, pad_x, pad_y, src_w, src_h
        )

        # ultralytics reference on the same file, fixed-size preprocessing
        r = predictor.predict(
            source=str(p), imgsz=IMGSZ, conf=CONF, iou=IOU, max_det=MAX_DET,
            rect=False, verbose=False, device="cpu",
        )[0]
        ref_boxes = r.boxes.xyxy.cpu().numpy()
        ref_scores = r.boxes.conf.cpu().numpy()
        ref_classes = r.boxes.cls.cpu().numpy().astype(int)

        total_mine += len(my_boxes)
        total_ref += len(ref_boxes)

        gap = None
        if len(my_boxes) == len(ref_boxes) and len(my_boxes) > 0:
            # match by score ordering (both are deterministic greedy NMS)
            o_mine = np.argsort(-my_scores)
            o_ref = np.argsort(-ref_scores)
            gap = float(np.abs(my_boxes[o_mine] - ref_boxes[o_ref]).max())
            all_box_gaps.append(gap)
            if not np.array_equal(my_classes[o_mine], ref_classes[o_ref]):
                class_mismatch += 1

        per_image.append({
            "image": p.name,
            "mine": int(len(my_boxes)),
            "ultralytics": int(len(ref_boxes)),
            "count_match": len(my_boxes) == len(ref_boxes),
            "max_box_gap_px": gap,
        })

    report = {
        "onnx": str(ONNX),
        "images": len(images),
        "params": {"conf": CONF, "iou": IOU, "max_det": MAX_DET, "imgsz": IMGSZ},
        "totals": {"mine": total_mine, "ultralytics": total_ref},
        "images_with_matching_count": sum(1 for r in per_image if r["count_match"]),
        "images_with_class_mismatch": class_mismatch,
        "max_box_gap_px": float(max(all_box_gaps)) if all_box_gaps else None,
        "mean_box_gap_px": float(np.mean(all_box_gaps)) if all_box_gaps else None,
        "per_image": per_image,
    }

    print(json.dumps({k: v for k, v in report.items() if k != "per_image"}, indent=2))
    RECORDS.mkdir(exist_ok=True)
    (RECORDS / "decode_validation.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"\n[+] {RECORDS / 'decode_validation.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
