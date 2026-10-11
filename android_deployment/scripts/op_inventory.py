"""Concrete converter-risk assessment: what ops are actually in the exported graph?

Checks the ONNX against the op-support surface of the three Android routes
(TFLite GPU delegate, ORT Android, ncnn) instead of guessing.
"""
import sys, json
from collections import Counter
from pathlib import Path

REPO = Path(r"D:\yolo\paper\general journal\ultralytics-main\ultralytics-main")
sys.path.insert(0, str(REPO))

import onnx
import numpy as np

ONNX = Path(r"D:\yolo\paper\general journal\ultralytics-main\deploy_fsdh_yolo_n_android_native\exported\fsdh_yolo_n_rerun_seed0_640_fp32_opset17.onnx")
m = onnx.load(str(ONNX))
g = m.graph

ops = Counter(n.op_type for n in g.node)
print("[i] total nodes:", len(g.node))
print("[i] opset       :", [(o.domain or 'ai.onnx', o.version) for o in m.opset_import])
print("\n=== op histogram (sorted) ===")
for op, c in ops.most_common():
    print(f"  {op:<24} {c}")

# domains: any custom/contrib domain would be a converter blocker
domains = Counter((n.domain or "ai.onnx") for n in g.node)
print("\n[i] domains:", dict(domains))

# Transpose perms - ncnn/tflite care about 4D permute support
print("\n=== Transpose perms ===")
for n in g.node:
    if n.op_type == "Transpose":
        perm = None
        for a in n.attribute:
            if a.name == "perm":
                perm = list(a.ints)
        print(f"  {n.name:<40} perm={perm}")

# Reshape targets that are constant-folded
print("\n=== Reshape / Expand / Tile / Slice / Gather (dynamic-shape risk) ===")
risky = {"Reshape", "Expand", "Tile", "Slice", "Gather", "GatherND", "ScatterND", "Range", "NonZero", "TopK", "Where"}
for n in g.node:
    if n.op_type in risky:
        print(f"  {n.op_type:<12} {n.name}")

# input / output signature
def sig(vs):
    out = []
    for v in vs:
        dims = [d.dim_value or d.dim_param for d in v.type.tensor_type.shape.dim]
        out.append((v.name, dims, v.type.tensor_type.elem_type))
    return out

print("\n[i] inputs :", sig(g.input))
print("[i] outputs:", sig(g.output))

# initializer dtype survey (INT64 weights can break some converters)
dts = Counter(ini.data_type for ini in g.initializer)
print("[i] initializer dtypes (raw enum):", dict(dts))
print("    (1=FLOAT 7=INT64 6=INT32 9=BOOL)")

# find nodes consuming INT64 initializers -> common TFLite friction
int64_inits = {ini.name for ini in g.initializer if ini.data_type == 7}
if int64_inits:
    print("\n=== nodes consuming INT64 initializers (TFLite friction) ===")
    for n in g.node:
        hits = [i for i in n.input if i in int64_inits]
        if hits:
            print(f"  {n.op_type:<12} {n.name:<45} <- {hits}")
