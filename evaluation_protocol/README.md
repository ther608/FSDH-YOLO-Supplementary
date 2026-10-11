# Evaluation protocol

A description of how every metric in the paper was computed. This is a reference
summary written for this repository — the authoritative text is Appendix A of the
manuscript (Chinese).

The purpose of the unified evaluator is that models trained under different frameworks
are compared on identical ground: default prediction thresholds, class indices,
post-processing and AP implementations all differ between frameworks, so every model's
predictions are converted to a common format and scored by an evaluator decoupled from
the training framework ("Common Evaluator", built on pycocotools `COCOeval`).

---

## 1. Detection metrics

| Item | Setting |
|---|---|
| Input size | 640×640, **square** inference |
| Prediction confidence floor | **0.001** — keeps the low-confidence predictions needed for a complete Precision-Recall curve |
| NMS | **class-aware**, IoU = **0.70** |
| Models without NMS (RT-DETR) | native decoder / top-k retained, **no** NMS imposed |
| Max predictions per image | **300** |
| AP interpolation | **COCO 101-point** |
| IoU thresholds | 0.50 → 0.95, step 0.05 |
| P / R operating point | the confidence threshold at which the **class-averaged F1 is maximal**, with class-aware matching at IoU ≥ 0.50; macro-averaged |

The confidence threshold used for P/R is found by scanning and is used **only** for P/R.
AP is computed from the full threshold sweep and does not depend on it.

Because each model has its own optimal F1 threshold, P and R are reported as a joint
description of the precision/recall balance rather than as a ranking criterion on their
own; overall detection performance is judged on mAP50 and mAP50-95.

> The evaluation confidence floor (0.001) and the application's operating point in the
> Android deployment (0.25) are **different things** and must not be conflated.

## 2. Area-stratified metrics

Box areas are computed in **original-image coordinates**.

| Metric | Area range (original image) | Meaning |
|---|---|---|
| `COCO AP_s` | < 1024 px² | strict COCO small-object definition (37 GT in the test split) |
| `AP_small(640)` | [0, 4096) px² | task-adapted small-object metric |
| `AP_non-small` | [4096, 1e10) px² | the complement |

Both area metrics use **COCOeval area-range *ignore* semantics**: when evaluating one
area band, ground-truth objects outside the band are marked `ignore` rather than being
removed from the evaluation.

`AP_small(640)` exists because the test split contains too few objects under the strict
COCO threshold for a stable per-class / per-acquisition-batch comparison. The factor of 4
in area follows from the ≈2:1 linear downscale between the ≈1280 px original images and
the 640 px network input: COCO's 32² = 1024 px² threshold, mapped back to original-image
coordinates, becomes 64² = 4096 px².

The two metrics have different definitions, different sample sizes and different
interpretations. They are reported separately and must not be conflated.

## 3. Confusion matrices

| Item | Setting |
|---|---|
| Confidence threshold | **0.25** (fixed, not the per-model F1 point) |
| Matching IoU | **0.50** |
| Matching | one-to-one, highest IoU first, among candidate pairs satisfying the IoU threshold |
| Normalization | within each seed, each true-class row is normalized to sum to 1; the figure reports the mean over the three seeds |
| Miss/FN column | unmatched ground-truth objects |

Unmatched predictions are false positives and are **not** part of the row-normalized
matrix. The matrix therefore describes recall-side classification behaviour and the
direction of confusions; it does not replace Precision-Recall or AP.

## 4. Paired image-level bootstrap

| Item | Setting |
|---|---|
| Resampling unit | the test image (126 images) |
| Replicates | **10,000**, sampled with replacement |
| Pairing | in replicate *b*, all compared models **and all three seeds** use the identical resampled image index set |
| Difference order | within a seed first, then averaged over the three seeds |
| Interval | percentile method, 95% |

The interval quantifies uncertainty from **test-image sampling only**, given the trained
models. Training randomness is handled separately, by the three-seed mean ± SD and by
whether the per-seed paired differences agree in sign.

## 5. Parameters and FPS

| Item | Setting |
|---|---|
| Params | inference-time **fused** model, Ultralytics `summary (fused)`; "M" = 10⁶ |
| Hardware | NVIDIA GeForce RTX 5060 Ti |
| Precision | CUDA FP32, batch 1, 640×640 |
| Warm-up / timing | input tensor materialized in advance; 100 warm-up iterations, then 500 timed iterations |
| Synchronization | CUDA sync immediately before and after each timed iteration |
| Timing range | forward pass + prediction decode + required model-side post-processing. Excludes image loading, CPU↔GPU transfer and input preprocessing |
| NMS during timing | conf 0.25, IoU 0.70 (a fixed inference load — different from the evaluation floor of 0.001) |
| Summary | each of the three seeded checkpoints timed independently under the same protocol; mean ± SD reported |

This is a **model-side throughput** figure under a uniform GPU protocol. It is not an
end-to-end system speed and does not include capture, transfer or preprocessing.

## 6. Training and model selection

| Item | Setting |
|---|---|
| Framework | Ultralytics 8.4.11 |
| Checkpoint selection | Ultralytics default fitness — validation-set mAP50-95 |
| Test split | never used for training, checkpoint selection or hyper-parameter tuning |
| Initialization | Ultralytics pretrained weights; parameters new to modified modules are initialized by the framework |
| Seeds | 0, 1, 2 — each configuration trained independently |

The test-split metrics reported in the paper are produced by running the selected
`best.pt` on the independent test split and re-scoring with the Common Evaluator.
Ultralytics' own native validation metrics are **not** the reported values and are not
comparable to them.
