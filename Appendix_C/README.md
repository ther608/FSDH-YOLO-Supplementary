# Appendix C — Per-seed results

This folder contains the per-seed results of every experiment reported in the paper.

| File | Contents |
|---|---|
| [`Appendix_C.md`](Appendix_C.md) | The whole appendix rendered as markdown tables |
| [`per_seed/C01_…CSV`](per_seed) | Multi-scale feature-enhancement modules (Table 3) |
| [`per_seed/C02_…CSV`](per_seed) | Ablation study (Table 4) |
| [`per_seed/C03_…CSV`](per_seed) | Small-object AP by area band (Table 5) |
| [`per_seed/C04_…CSV`](per_seed) | AP75 localization (Table 6) |
| [`per_seed/C05_…CSV`](per_seed) | Bounding-box localization error (Table 7) |
| [`per_seed/C06_…CSV`](per_seed) | Per-class maturity AP (Table 8) |
| [`per_seed/C07_…CSV`](per_seed) | Row-normalized confusion matrices (Figure 10) |
| [`per_seed/C08_…CSV`](per_seed) | General-purpose detector comparison (Table 9) |
| [`per_seed/C09_…CSV`](per_seed) | Recent fruit-detection methods (Table 10) |
| [`per_seed/C10_…CSV`](per_seed) | Cross-dataset applicability, Dataset B (Tables 11–12) |
| [`per_seed/C11_…CSV`](per_seed) | YOLO11 model-scale selection (Appendix B.1) |
| [`per_seed/C12_…CSV`](per_seed) | SPDConv vs. FSDConv (Appendix B.2) |
| [`per_seed/C13_…CSV`](per_seed) | SHEB branch ablation (Appendix B.3) |

## Conventions

- **Seeds** — every configuration was trained independently with seeds **0, 1, 2**.
- **Mean ± SD** — the main text reports the arithmetic mean and the **sample** standard
  deviation (ddof = 1) of the three seeded runs. Per-seed tables give the raw values,
  so any summary statistic can be recomputed.
- **Percent units** — all accuracy columns are percentages (`P(%)`, `R(%)`, `mAP50(%)`,
  `mAP50-95(%)`), matching the paper's printed values.
- **FPS** — measured under a separate uniform latency protocol, not during training:
  CUDA FP32, batch 1, 640×640, 100 warm-up + 500 timed iterations with a CUDA sync around
  each, timing range = forward pass + prediction decode + class-aware NMS (conf 0.25,
  IoU 0.70), excluding image I/O and input preprocessing. Each seeded checkpoint was
  timed independently, then summarized as mean ± SD.
- **Params** — fused inference-time parameter count (Ultralytics `summary (fused)`).

## Reproducing the paper's mean ± SD

```python
import pandas as pd
df = pd.read_csv("per_seed/C02_ablation_per_seed.csv")
g = df[df.Model == "FSDH-YOLO"]
print(g["mAP50-95(%)"].mean(), g["mAP50-95(%)"].std(ddof=1))
```

Per-seed tables are printed to 3 decimals, whereas the paper's statistics were computed
from full-precision values, so a small fraction of recomputed means/SDs differ from the
printed value by 0.001 in the last digit. This was checked exhaustively across all
434 quantities in the appendix and **no larger difference exists**; see
[`../reproduction_notes/README.md`](../reproduction_notes/README.md).
