# Reproduction notes

Environment, provenance, how to recompute the reported statistics, and the issues found
in the manuscript during preparation of this repository.

---

## 1. Environment (Dataset A / Dataset B experiments)

| Item | Value |
|---|---|
| OS | Windows 11 64-bit |
| CPU | Intel Core i5-14400F |
| GPU | NVIDIA GeForce RTX 5060 Ti 16 GB |
| Python | 3.10 |
| CUDA | 12.8 |
| PyTorch | 2.10 +cu128 |
| Ultralytics | 8.4.11 |

Training configuration (Table 2 of the manuscript):

| Parameter | Value |
|---|---|
| Input resolution | 640×640 |
| Batch size | 12 |
| Workers | 6 |
| Optimizer | SGD |
| SGD momentum | 0.937 |
| Epochs | 300 |
| Initial LR (`lr0`) | 0.01 |
| Final LR fraction (`lrf`) | 0.01 |
| Warm-up epochs | 3 |
| Weight decay | 0.0005 |
| Early-stopping patience | 100 |
| Seeds | 0, 1, 2 |

Automatic mixed precision and deterministic computation were enabled. Everything not
listed above follows the Ultralytics 8.4.11 defaults, including its default online
augmentation and letterbox resizing.

On-device deployment environment: see
[`../android_deployment/README.md`](../android_deployment/README.md).

---

## 2. Datasets

| | Dataset A | Dataset B |
|---|---|---|
| Source | RGB subset of *An RGB-D image dataset for lychee detection and maturity classification for robotic harvesting* (arXiv:2510.16800) | `lichi-maturity` (Mendeley Data V1, `doi:10.17632/c3rk9gv4w9.1`) |
| Images retained | 842 (36 indoor tabletop images removed) | 1,495 (154 augmented + 10 duplicate boxes removed) |
| Classes | 3 — `unripe` / `semi-ripe` / `ripe` | 4 — `green` / `half` / `red` / `young` |
| Split (train/val/test, images) | 590 / 126 / 126 | 1,071 / 212 / 212 |
| Split unit | 672 connected groups (group-aware; no group crosses splits) | 1,188 indivisible groups |
| Instances (train/val/test) | 11,083 / 2,690 / 2,775 | — |
| Augmentation | offline, training split only: each original yields 3 variants (one per perturbation family) | same protocol; training split expanded to 4,284 |

Augmentation families (identical for both datasets): (1) illumination/colour —
brightness-contrast or HSV; (2) imaging quality — Gaussian blur or sensor noise;
(3) slight geometry — horizontal flip, rotation, translation or scaling. Validation and
test splits always keep the original images and labels unchanged.

**The datasets are not redistributed in this repository.**

---

## 3. Recomputing the paper's statistics

Per-seed tables are printed to 3 decimals while the paper's mean ± SD was computed from
full-precision values. Recomputing from the 3-decimal numbers reproduces the printed
value to within **±0.001 in the last digit**; a handful of quantities land on the
rounding boundary and differ by exactly 0.001.

```python
import pandas as pd
df = pd.read_csv("Appendix_C/per_seed/C02_ablation_per_seed.csv")
g = df[df.Model == "FSDH-YOLO"]
print(g["mAP50-95(%)"].mean())            # 67.2966666...
print(g[ "mAP50-95(%)"].std(ddof=1))      # 0.71860...  -> paper prints 0.718
```

`FPS` columns use the same ddof = 1 convention.

---

## 4. Provenance map

Every published number traces back to a project artefact:

| Published content | Source artefact |
|---|---|
| Per-seed accuracy, all experiments | `runs/common_eval/paper16_unified_protocol/{model}/seed{0,1,2}__test_metrics_common.json`, indexed by `analysis/paper16_unified_common_protocol/per_seed.csv` |
| Mean ± SD | `analysis/paper16_unified_common_protocol/three_seed_mean_sd.csv` |
| AP75 | `analysis/paper_3_2_ap75_shab/AP75_逐种子.csv` |
| Area-stratified AP | `analysis/paper27_statistical_supplement/results.json` → `datasets.dataset_a.area_ap[]` |
| Confusion matrices | `analysis/paper27_statistical_supplement/confusion_matrices.csv` |
| Bootstrap CIs | `analysis/paper_3_4_review/paper35_extended_statistics/bootstrap_ci_extended.csv` |
| Six-module comparison | `analysis/shab_comparison_common_protocol/summary.json` |
| Dataset B | `analysis/paper_dataset_b_groupaware_clean_v4_aug_common_v2__main_results.json` |
| FPS | `runs/quality/{paper16_non_fsd_uniform_latency, paper16_spo_fsd_uniform_latency, shab_comparison_uniform_latency_fp32_b1_640}/uniform_latency_results.csv` |
| Deployment | see [`../android_deployment/PROVENANCE.md`](../android_deployment/PROVENANCE.md) |

These artefacts live in the analysis workspace of the project and are not redistributed;
they are listed so that the chain of custody is documented.

---

## 5. Verification performed

Three independent cross-checks were run before publishing this repository. All reads
were read-only; no source file was modified.

| Layer | Comparison | Quantities | Mismatches |
|---|---|---|---|
| 1 | Appendix C per-seed → recomputed mean ± SD vs. the paper's own summary tables | 434 (of which 434 agree to ≤ 0.001; none larger) | 0 |
| 2 | Appendix C per-seed ↔ experiment record document | 68 | 0 |
| 3 | Appendix C / record ↔ raw source artefacts (including 90 quantities read straight from the Common Evaluator JSONs, bypassing every intermediate summary) | 825 | 0 |
| 4 | Deployment tables ↔ raw device records | all | 0 |

Two appendix transcription errors that existed in an earlier manuscript revision —
two per-seed tables shifted up by one row, with the first `YOLO11n` label dropped and
each row carrying the previous model's data — are **confirmed fixed** in the current
revision; every row now matches the record.

---

## 6. Known manuscript issues

Four documentation defects remain in the current manuscript revision. **None affects a
computed result**; all four are labelling, scope or last-digit issues.

Items 1 and 2 are in the main analysis appendices. Items 3 and 4 are single cells in
deployment appendix tables — a diagnostic split and a record field respectively — and are
also noted in
[`../android_deployment/deployment_evaluation_results/README.md`](../android_deployment/deployment_evaluation_results/README.md).

### Issue 1 — Appendix B.2 summary table, row 4 (label does not match the data)

The summary table of *SPDConv and FSDConv module comparison* has a row labelled
`FSDConv+SHEB` carrying these values:

| Label in the paper | P / % | R / % | mAP50 / % | mAP50-95 / % | Params / M | FPS |
|---|---|---|---|---|---|---|
| `FSDConv+SHEB` | 78.058±1.128 | 79.519±1.138 | 80.773±0.241 | 66.649±**0.334** | 2.997 | 125.45±2.16 |

Those values belong to **`SPDConv+SHEB`**, not to `FSDConv+SHEB`:

- per-seed source (Appendix C, section C.12): seed0 76.774/80.833/80.527/66.701,
  seed1 78.886/78.882/80.783/66.291, seed2 78.515/78.842/81.009/66.954
- recomputing mAP50-95 from those three seeds gives mean 66.649 ✓ but
  **SD = 0.33459 → 0.335**, so the printed `0.334` is off by one unit in the last digit
- the FPS value 125.45±2.16 is also the `SPDConv+SHEB` measurement

`FSDConv+SHEB` and `FSDH-YOLO` denote the same configuration, and the table's next row is
already the correct `FSDH-YOLO` (79.989±2.168 / 78.443±1.127 / 81.766±0.994 /
67.297±0.718). The current table therefore has effectively two rows for one model, with
different data.

**Suggested fix:** relabel the row `SPDConv+SHEB` (matching the experiment record, which
describes the second comparison level as SPDConv+SHEB vs. FSDH-YOLO) and change the
mAP50-95 SD to **0.335**.

### Issue 2 — two paired-difference values in §3.8

The paper's Δ values should be computed as paired per-seed differences, then averaged —
not as the difference of two already-rounded means. Five such values were flagged; three
have been corrected in the current revision, two remain:

| Location | Printed | Paired-caliber value | Derivation |
|---|---|---|---|
| §3.8 text, young AP50 | +0.998 | **+0.999** | per-seed differences +1.0095 / +0.5369 / +1.4496 → mean +0.998679 |
| §3.8 text, red AP50 | −0.384 | **−0.385** | per-seed differences −0.6836 / +0.4035 / −0.8737 → mean −0.384589 |

The corresponding table columns in Table 12 are themselves correct; only the last digit
of these two text values differs.

### Issue 3 — Appendix B.5-1, FSDH-YOLO decode/NMS cell (mixed aggregation scope)

The fixed-input table splits the pipeline latency into preprocess / inference / decode-NMS.
For FSDH-YOLO the appendix prints `5.5 / 151.5 / 0.5`; the first two match the
rounds-1–3 means (round 0 is the excluded cold start), but the third matches the
**all-four-rounds** mean:

| Scope | preprocess | inference | decode/NMS |
|---|---|---|---|
| rounds 1–3 (the protocol) | 5.5229 → 5.5 | 151.4844 → 151.5 | 0.4468 → **0.4** |
| all 4 rounds | 5.2701 → 5.3 | 147.0049 → 147.0 | 0.4562 → 0.5 |

Every other cell in that table — including both rows of the YOLO11n baseline — uses
rounds 1–3. The end-to-end figure (157.5 ms, 6.35 FPS) is unaffected; decode/NMS is
recorded for diagnosis only. **Suggested fix:** change `0.5` to `0.4`, or state the scope.

### Issue 4 — Appendix B.5-4, run 2 charge level (wrong reading used)

The thermal table reports the run-2 pre-timing charge level as `81 %`. The raw session log
records 81 % at the *cooldown* check (17:15:42) and 80 % at the *pre-timing* reading
(17:15:53); runs 1 and 3 happen to have identical values at both points, so only run 2
exposes the mix-up.

| Run | pre-timing charge, log | appendix |
|---|---|---|
| 1 | 81 % | 81 % ✓ |
| 2 | **80 %** | 81 % ✗ |
| 3 | 80 % | 80 % ✓ |

Charge level is a record field, not a measurement. **Suggested fix:** change the run-2
value to `80 → 80 %`.

---

## 7. Reproducing the deployment results

See [`../android_deployment/README.md`](../android_deployment/README.md) § Reproducing.
The Android application is a complete, buildable Android Studio project; the exact ONNX
model used for every on-device measurement is included.
