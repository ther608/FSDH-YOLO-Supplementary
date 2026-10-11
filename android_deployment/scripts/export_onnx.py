"""FSDH-YOLO-n (seed 2) -> ONNX master export.

Read-only with respect to the paper deliverables: the source run directory is
never written to. The weight was copied into this deployment folder first, and
the export output is moved into ./exported/.

Run:  D:\\pytorch_120\\envs\\yolo8\\python.exe export_onnx.py
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

# The fork's ultralytics (8.4.11) must win over any installed release, because
# SPOConv (FSDConv) and SHAB only exist in this repository.
REPO = Path(r"D:\yolo\paper\general journal\ultralytics-main\ultralytics-main")
sys.path.insert(0, str(REPO))

import torch  # noqa: E402
import ultralytics  # noqa: E402
from ultralytics import YOLO  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SRC_WEIGHT = ROOT / "weights" / "fsdh_yolo_n_rerun_seed0.pt"
OUT_TAG = "fsdh_yolo_n_rerun_seed0"
EXPORT_DIR = ROOT / "exported"
RECORDS = ROOT / "records"

IMGSZ = 640
# opset 17 is the sweet spot for downstream converters (onnx2tf / TensorRT /
# RKNN all accept it). torch's own exporter warns above 20, and the default
# auto-pick produced a file stamped opset 22 that downstream tools reject.
OPSET = 17
EXPORT_KWARGS = dict(
    format="onnx",
    imgsz=IMGSZ,
    batch=1,
    dynamic=False,
    half=False,      # FP32 master: most portable across runtimes
    simplify=True,
    nms=False,       # keep raw outputs so the app can tune the conf threshold
    opset=OPSET,
    device="cpu",
)


def sha256(path: Path, n: int = 16) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:n]


def versions() -> dict:
    import importlib.metadata as md

    out = {"ultralytics_source": ultralytics.__version__, "ultralytics_module": str(Path(ultralytics.__file__).resolve()), "torch": torch.__version__}
    for pkg in ("onnx", "onnxslim", "onnxruntime"):
        try:
            out[pkg] = md.version(pkg)
        except Exception:
            out[pkg] = "NOT INSTALLED"
    return out


def main() -> int:
    global SRC_WEIGHT, OUT_TAG
    ap = argparse.ArgumentParser()
    ap.add_argument("--half", action="store_true", help="导出 FP16（CPU 路径会用 onnxruntime.transformers 转换，保留 FP32 输入输出）")
    ap.add_argument("--weights", type=Path, default=None, help="改用其它权重（用于导出 YOLO11n 基线对照）")
    ap.add_argument("--tag", default=None, help="产物文件名前缀，默认 fsdh_yolo_n_seed2")
    cli = ap.parse_args()
    EXPORT_KWARGS["half"] = cli.half
    if cli.weights:
        SRC_WEIGHT = cli.weights
    if cli.tag:
        OUT_TAG = cli.tag

    EXPORT_DIR.mkdir(exist_ok=True)
    RECORDS.mkdir(exist_ok=True)

    assert SRC_WEIGHT.is_file(), f"missing weight: {SRC_WEIGHT}"
    src_hash = sha256(SRC_WEIGHT)
    print(f"[i] source weight : {SRC_WEIGHT}")
    print(f"[i] source sha256 : {src_hash}")
    print(f"[i] half          : {cli.half}")
    print(f"[i] versions      : {json.dumps(versions(), indent=2)}")

    model = YOLO(str(SRC_WEIGHT))
    print(f"[i] model         : {type(model.model).__name__}, nc={model.model.nc}, names={model.model.names}")

    produced = Path(model.export(**EXPORT_KWARGS))
    print(f"[i] raw export    : {produced}")

    final = EXPORT_DIR / f"{OUT_TAG}_{IMGSZ}_{'fp16' if cli.half else 'fp32'}_opset{OPSET}.onnx"
    if produced.resolve() != final.resolve():
        if final.exists():
            superseded = EXPORT_DIR / "_superseded"
            superseded.mkdir(exist_ok=True)
            shutil.move(str(final), str(superseded / final.name))
        shutil.move(str(produced), str(final))
    print(f"[+] master onnx   : {final}  ({final.stat().st_size / 1e6:.2f} MB, sha256[:16]={sha256(final)})")

    record = {
        "source_weight": str(SRC_WEIGHT),
        "source_sha256_16": src_hash,
        "export_kwargs": {k: (v if isinstance(v, (str, int, float, bool)) else str(v)) for k, v in EXPORT_KWARGS.items()},
        "artifact": str(final),
        "artifact_sha256_16": sha256(final),
        "artifact_bytes": final.stat().st_size,
        "versions": versions(),
    }
    (RECORDS / "export_record.json").write_text(json.dumps(record, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[+] record        : {RECORDS / 'export_record.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
