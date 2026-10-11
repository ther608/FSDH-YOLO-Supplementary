# Appendix C — Per-seed results

> Transcribed from the manuscript's Appendix C. Values are reproduced **verbatim**; nothing is recomputed or re-rounded here.
> Machine-readable equivalents: [`per_seed/`](per_seed).

Every experiment in this paper was run with three independent random seeds (0, 1, 2). The main text reports the arithmetic mean and the sample standard deviation (ddof = 1) of the three runs; this appendix reports the individual per-seed values, plus per-seed FPS measured under a separate uniform latency protocol (CUDA FP32, batch 1, 640×640, N = 100 warm-up / 500 timed iterations, timing range = forward + decode + class-aware NMS, excluding image I/O and input preprocessing).

**Reading note.** Per-seed accuracies are printed to 3 decimals, while the main text's mean ± SD was computed from full-precision values. Recomputing from the 3-decimal numbers therefore reproduces the paper to within ±0.001 in the last digit; see `reproduction_notes/README.md`.

---

## C.1  Multi-scale feature-enhancement module comparison

Table 3 in the main text. Six representative multi-scale modules, each inserted at the same position of the same YOLO11n baseline.

*The appendix does not repeat the YOLO11n and SHEB rows here; their per-seed values appear in C.11 (YOLO11n) and C.2 (YOLO11n +SHEB) respectively.*

| Model | seed | P(%) | R(%) | mAP50(%) | mAP50-95(%) | FPS |
|---|---|---|---|---|---|---|
| ASPP | 0 | 77.103 | 79.754 | 80.793 | 65.918 | 122.46 |
| ASPP | 1 | 75.772 | 76.710 | 78.881 | 64.349 | 122.35 |
| ASPP | 2 | 79.004 | 78.772 | 81.939 | 66.235 | 126.22 |
| SPP | 0 | 78.360 | 79.066 | 80.615 | 66.164 | 135.93 |
| SPP | 1 | 77.198 | 79.333 | 80.508 | 65.587 | 132.38 |
| SPP | 2 | 77.144 | 81.029 | 82.641 | 67.505 | 140.07 |
| DilatedConv | 0 | 76.499 | 80.966 | 81.543 | 66.357 | 137.68 |
| DilatedConv | 1 | 76.432 | 79.850 | 80.927 | 65.889 | 139.89 |
| DilatedConv | 2 | 76.146 | 81.447 | 80.995 | 66.397 | 138.04 |
| RepConv | 0 | 77.828 | 79.842 | 81.065 | 65.703 | 143.03 |
| RepConv | 1 | 75.614 | 80.702 | 80.478 | 65.892 | 148.44 |
| RepConv | 2 | 77.658 | 77.821 | 79.662 | 65.344 | 139.92 |
| MultiScaleDWConv | 0 | 78.327 | 79.506 | 80.934 | 66.452 | 130.56 |
| MultiScaleDWConv | 1 | 77.500 | 80.340 | 81.745 | 66.628 | 132.93 |
| MultiScaleDWConv | 2 | 76.525 | 79.555 | 80.389 | 65.992 | 133.48 |
| RFB | 0 | 77.499 | 81.230 | 82.507 | 67.348 | 124.21 |
| RFB | 1 | 76.363 | 79.522 | 79.093 | 65.174 | 124.12 |
| RFB | 2 | 77.355 | 77.728 | 77.711 | 64.372 | 123.57 |

*Source: [`per_seed/C01_multiscale_feature_enhancement_per_seed.csv`](per_seed/C01_multiscale_feature_enhancement_per_seed.csv)*

---

## C.2  Ablation study

Table 4 in the main text. `YOLO11n +SHEB` in this table is the same configuration as the `SHEB` row of C.1.

| Model | seed | P(%) | R(%) | mAP50(%) | mAP50-95(%) | FPS |
|---|---|---|---|---|---|---|
| YOLO11n-PM | 0 | 79.060 | 78.937 | 80.820 | 66.931 | 164.69 |
| YOLO11n-PM | 1 | 77.814 | 78.430 | 79.209 | 65.703 | 158.62 |
| YOLO11n-PM | 2 | 81.095 | 77.499 | 82.790 | 67.981 | 160.22 |
| YOLO11n +FSDConv | 0 | 75.375 | 82.672 | 81.487 | 66.541 | 148.49 |
| YOLO11n +FSDConv | 1 | 77.780 | 77.989 | 81.395 | 67.115 | 149.34 |
| YOLO11n +FSDConv | 2 | 77.626 | 76.902 | 78.806 | 65.221 | 149.14 |
| YOLO11n +SHEB | 0 | 78.173 | 79.970 | 83.031 | 67.388 | 122.57 |
| YOLO11n +SHEB | 1 | 78.297 | 79.081 | 79.013 | 65.106 | 122.80 |
| YOLO11n +SHEB | 2 | 78.556 | 77.513 | 81.233 | 66.152 | 120.67 |
| FSDH-YOLO | 0 | 78.581 | 78.364 | 80.931 | 66.670 | 124.34 |
| FSDH-YOLO | 1 | 78.900 | 79.608 | 81.500 | 67.140 | 120.09 |
| FSDH-YOLO | 2 | 82.485 | 77.358 | 82.866 | 68.080 | 125.74 |

*Source: [`per_seed/C02_ablation_per_seed.csv`](per_seed/C02_ablation_per_seed.csv)*

---

## C.3  Small-object detection performance

Table 5 in the main text. Metric is AP50-95 computed with COCOeval area-range *ignore* semantics.

`small(640)` = original-image box area in [0, 4096) px² — the COCO threshold 32² = 1024 px² mapped through the ≈2:1 linear (≈4:1 area) downscale from the ≈1280 px original images to the 640 px network input. `non-small` = [4096, 1e10) px².

Values are listed as `seed0 / seed1 / seed2`.

| Target | AreaBand | GT_count | YOLO11n_seed0/1/2 | FSDH-YOLO_seed0/1/2 |
|---|---|---|---|---|
| All | small(640) | 889 | 55.914 / 54.824 / 54.873 | 57.577 / 56.969 / 59.649 |
| All | non-small | 1886 | 69.293 / 68.014 / 68.022 | 70.685 / 71.423 / 72.016 |
| unripe | small(640) | 457 | 62.098 / 61.349 / 62.586 | 62.782 / 62.221 / 63.102 |
| unripe | non-small | 500 | 77.682 / 75.987 / 77.845 | 79.136 / 79.503 / 78.882 |
| semi-ripe | small(640) | 145 | 44.605 / 40.538 / 40.188 | 44.827 / 44.969 / 49.515 |
| semi-ripe | non-small | 508 | 61.972 / 61.046 / 56.669 | 60.650 / 62.342 / 61.542 |
| ripe | small(640) | 287 | 61.040 / 62.585 / 61.846 | 65.122 / 63.717 / 66.330 |
| ripe | non-small | 878 | 68.227 / 67.010 / 69.552 | 72.270 / 72.423 / 75.623 |

*Source: [`per_seed/C03_small_object_AP_per_seed.csv`](per_seed/C03_small_object_AP_per_seed.csv)*

---

## C.4  Localization performance (AP75)

Table 6 in the main text. AP75 is the AP at a single IoU threshold of 0.75, averaged in the AP50-95 manner.

Values are listed as `seed0 / seed1 / seed2`.

| Model | AP75(%)_seed0/1/2 |
|---|---|
| YOLO11n | 75.559 / 73.533 / 74.231 |
| FSDH-YOLO | 76.193 / 77.205 / 78.188 |

*Source: [`per_seed/C04_AP75_localization_per_seed.csv`](per_seed/C04_AP75_localization_per_seed.csv)*

---

## C.5  Bounding-box localization error on commonly detected targets

Table 7 in the main text. Only ground-truth objects matched correctly by *both* models under the same seed are included, which isolates the comparison from missed detections and class differences.

Protocol: conf = 0.25, class-aware one-to-one greedy matching, minimum IoU = 0.50, at most 300 predictions per image. Error = mean(1 − IoU) × 100 %; lower is better.

| seed | common_GT | YOLO11n_error(%) | FSDH-YOLO_error(%) | error_reduction(pp) |
|---|---|---|---|---|
| 0 | 2166 | 9.729 | 9.314 | 0.415 |
| 1 | 2120 | 9.319 | 9.279 | 0.041 |
| 2 | 2211 | 9.684 | 9.589 | 0.094 |
| 均值±SD | — | 9.577±0.224 | 9.394±0.170 | 0.183±0.202 |

*Source: [`per_seed/C05_bbox_localization_error_per_seed.csv`](per_seed/C05_bbox_localization_error_per_seed.csv)*

---

## C.6  Per-class maturity performance

Table 8 in the main text.

| Model | Class | seed | AP50(%) | AP50-95(%) |
|---|---|---|---|---|
| YOLO11n | unripe | 0 | 87.738 | 70.253 |
| YOLO11n | unripe | 1 | 86.032 | 69.146 |
| YOLO11n | unripe | 2 | 88.103 | 70.346 |
| YOLO11n | semi-ripe | 0 | 71.820 | 58.612 |
| YOLO11n | semi-ripe | 1 | 69.291 | 57.307 |
| YOLO11n | semi-ripe | 2 | 65.668 | 53.527 |
| YOLO11n | ripe | 0 | 80.646 | 66.496 |
| YOLO11n | ripe | 1 | 79.416 | 66.000 |
| YOLO11n | ripe | 2 | 82.139 | 68.174 |
| FSDH-YOLO | unripe | 0 | 88.985 | 71.518 |
| FSDH-YOLO | unripe | 1 | 88.818 | 71.537 |
| FSDH-YOLO | unripe | 2 | 89.890 | 71.615 |
| FSDH-YOLO | semi-ripe | 0 | 69.118 | 57.775 |
| FSDH-YOLO | semi-ripe | 1 | 70.783 | 59.211 |
| FSDH-YOLO | semi-ripe | 2 | 70.623 | 59.131 |
| FSDH-YOLO | ripe | 0 | 84.691 | 70.719 |
| FSDH-YOLO | ripe | 1 | 84.899 | 70.673 |
| FSDH-YOLO | ripe | 2 | 88.086 | 73.495 |

*Source: [`per_seed/C06_maturity_class_AP_per_seed.csv`](per_seed/C06_maturity_class_AP_per_seed.csv)*

---

## C.7  Row-normalized confusion matrices

Figure 10 in the main text. Fixed operating point: confidence threshold 0.25, matching IoU threshold 0.50. Within each seed the rows are normalized by true class, then averaged over the three seeds; the reported SD is the sample SD across seeds.

The `MissFN` column is the unmatched ground-truth fraction. Unmatched predictions are false positives and are **not** part of this row-normalized matrix, so the matrix describes the recall-side classification behaviour only and does not replace Precision-Recall or AP.

| Model | TrueClass | unripe_mean | unripe_sd | semi-ripe_mean | semi-ripe_sd | ripe_mean | ripe_sd | MissFN_mean | MissFN_sd |
|---|---|---|---|---|---|---|---|---|---|
| YOLO11n | unripe | 86.207 | 2.497 | 1.149 | 0.929 | 0.313 | 0.276 | 12.330 | 2.306 |
| YOLO11n | semi-ripe | 5.003 | 2.527 | 75.957 | 5.240 | 10.924 | 2.304 | 8.116 | 1.991 |
| YOLO11n | ripe | 0.486 | 0.179 | 14.535 | 1.035 | 78.083 | 1.006 | 6.896 | 0.902 |
| FSDH-YOLO | unripe | 87.078 | 2.351 | 1.428 | 0.784 | 0.348 | 0.121 | 11.146 | 1.464 |
| FSDH-YOLO | semi-ripe | 5.564 | 0.468 | 70.495 | 1.687 | 14.650 | 1.238 | 9.290 | 1.076 |
| FSDH-YOLO | ripe | 0.401 | 0.198 | 9.642 | 1.665 | 83.462 | 1.165 | 6.495 | 0.524 |

*Source: [`per_seed/C07_confusion_matrix_row_normalized.csv`](per_seed/C07_confusion_matrix_row_normalized.csv)*

---

## C.8  Comparison with general-purpose detectors

Table 9 in the main text. YOLO11n and FSDH-YOLO per-seed values are identical to those in C.2.

| Model | seed | P(%) | R(%) | mAP50(%) | mAP50-95(%) | FPS |
|---|---|---|---|---|---|---|
| YOLOv5nu | 0 | 76.997 | 77.039 | 78.671 | 63.617 | 161.33 |
| YOLOv5nu | 1 | 74.916 | 79.366 | 80.506 | 65.445 | 168.08 |
| YOLOv5nu | 2 | 80.253 | 76.904 | 80.650 | 65.796 | 170.82 |
| YOLOv7-tiny | 0 | 78.446 | 77.212 | 84.414 | 66.301 | 188.92 |
| YOLOv7-tiny | 1 | 78.155 | 77.399 | 84.257 | 66.412 | 190.42 |
| YOLOv7-tiny | 2 | 75.179 | 81.986 | 84.106 | 66.311 | 189.09 |
| YOLOv8n | 0 | 77.052 | 79.376 | 79.107 | 64.740 | 178.05 |
| YOLOv8n | 1 | 78.157 | 78.421 | 78.918 | 64.830 | 186.64 |
| YOLOv8n | 2 | 79.155 | 79.192 | 81.072 | 65.680 | 185.60 |
| YOLO26n | 0 | 77.406 | 75.954 | 81.579 | 67.001 | 136.45 |
| YOLO26n | 1 | 77.381 | 78.013 | 83.297 | 67.536 | 138.15 |
| YOLO26n | 2 | 77.964 | 77.427 | 81.866 | 66.277 | 135.39 |
| RT-DETRv2-S | 0 | 73.708 | 78.814 | 82.062 | 67.131 | 60.63 |
| RT-DETRv2-S | 1 | 76.244 | 73.781 | 80.892 | 65.272 | 63.12 |
| RT-DETRv2-S | 2 | 73.925 | 78.425 | 81.375 | 65.549 | 81.02 |

*Source: [`per_seed/C08_general_detector_comparison_per_seed.csv`](per_seed/C08_general_detector_comparison_per_seed.csv)*

---

## C.9  Comparison with recent fruit-detection methods

Table 10 in the main text. All methods were re-trained under the unified protocol; the comparison is relative to the current experimental conditions and is **not** compared against the numbers reported in the original papers.

| Model | seed | P(%) | R(%) | mAP50(%) | mAP50-95(%) | FPS |
|---|---|---|---|---|---|---|
| PEANet-YOLOv5-D | 0 | 78.442 | 78.593 | 81.795 | 66.207 | 137.78 |
| PEANet-YOLOv5-D | 1 | 78.310 | 78.669 | 79.320 | 65.455 | 140.29 |
| PEANet-YOLOv5-D | 2 | 75.853 | 81.305 | 80.672 | 66.699 | 141.90 |
| YOLOv8-FPDW | 0 | 79.714 | 78.201 | 80.291 | 66.133 | 142.38 |
| YOLOv8-FPDW | 1 | 79.012 | 78.329 | 81.137 | 66.805 | 154.47 |
| YOLOv8-FPDW | 2 | 77.704 | 78.053 | 78.953 | 64.760 | 150.79 |
| Edge-YOLOv11 | 0 | 80.186 | 78.275 | 81.142 | 66.704 | 67.16 |
| Edge-YOLOv11 | 1 | 77.380 | 78.825 | 77.350 | 63.872 | 68.53 |
| Edge-YOLOv11 | 2 | 79.903 | 76.605 | 80.927 | 66.609 | 66.51 |
| GPC-YOLO | 0 | 78.378 | 77.989 | 79.202 | 64.925 | 173.08 |
| GPC-YOLO | 1 | 78.783 | 77.120 | 80.455 | 66.563 | 163.96 |
| GPC-YOLO | 2 | 80.020 | 79.880 | 83.527 | 67.888 | 183.09 |

*Source: [`per_seed/C09_recent_method_comparison_per_seed.csv`](per_seed/C09_recent_method_comparison_per_seed.csv)*

---

## C.10  Cross-dataset applicability (Dataset B)

Tables 11 and 12 in the main text. Dataset B has four native maturity classes (0 green / 1 half / 2 red / 3 young) and 640×640 native images, so `AP_small(640)` uses the original COCO threshold (original-image area < 1024 px²), which is equivalent to standard COCO AP_s on this dataset.

*The appendix reports the overall per-seed metrics; the per-class AP means, SDs and paired differences are given in Table 12 of the main text.*

| Model | seed | P(%) | R(%) | mAP50(%) | mAP50-95(%) | AP75(%) | AP_small(640)(%) |
|---|---|---|---|---|---|---|---|
| YOLO11n | 0 | 80.060 | 84.385 | 85.729 | 61.466 | 70.069 | 30.335 |
| YOLO11n | 1 | 82.781 | 80.053 | 84.706 | 61.442 | 70.882 | 32.790 |
| YOLO11n | 2 | 80.451 | 83.075 | 84.272 | 60.807 | 70.779 | 33.870 |
| FSDH-YOLO-n | 0 | 79.807 | 83.488 | 83.993 | 59.683 | 69.845 | 32.795 |
| FSDH-YOLO-n | 1 | 83.530 | 82.141 | 85.301 | 60.895 | 71.015 | 33.697 |
| FSDH-YOLO-n | 2 | 79.882 | 82.831 | 84.181 | 60.051 | 70.023 | 31.080 |

*Source: [`per_seed/C10_cross_dataset_per_seed.csv`](per_seed/C10_cross_dataset_per_seed.csv)*

---

## C.11  YOLO11 model-scale selection (Appendix B.1)

Used to fix YOLO11n as the baseline. Also the source of the YOLO11n per-seed rows referenced by C.1, C.2, C.4, C.8 and C.9.

| Model | seed | P(%) | R(%) | mAP50(%) | mAP50-95(%) | FPS |
|---|---|---|---|---|---|---|
| YOLO11n | 0 | 77.117 | 80.436 | 80.068 | 65.120 | 150.57 |
| YOLO11n | 1 | 79.841 | 76.257 | 78.246 | 64.151 | 148.28 |
| YOLO11n | 2 | 77.063 | 77.829 | 78.637 | 64.016 | 141.23 |
| YOLO11s | 0 | 76.909 | 76.774 | 75.708 | 61.927 | 131.76 |
| YOLO11s | 1 | 75.107 | 78.814 | 77.089 | 62.899 | 135.74 |
| YOLO11s | 2 | 75.990 | 75.657 | 71.872 | 59.375 | 128.94 |
| YOLO11m | 0 | 78.007 | 75.290 | 76.765 | 62.837 | 102.85 |
| YOLO11m | 1 | 77.866 | 73.364 | 72.985 | 59.656 | 104.33 |
| YOLO11m | 2 | 76.746 | 78.684 | 81.758 | 66.552 | 103.02 |

*Source: [`per_seed/C11_yolo11_scale_selection_per_seed.csv`](per_seed/C11_yolo11_scale_selection_per_seed.csv)*

---

## C.12  SPDConv vs. FSDConv (Appendix B.2)

Two-level comparison: (i) input-stage module only — SPDConv vs. FSDConv; (ii) with the full SHEB kept — SPDConv+SHEB vs. FSDH-YOLO. Both implementations have identical output shapes, channel counts and parameter counts, so the difference cannot be attributed to capacity.

*The paper's Appendix B.2 summary table labels its fourth row `FSDConv+SHEB`; that row carries the SPDConv+SHEB and FPS values listed here. See `reproduction_notes/README.md` § Known manuscript issues.*

| Model | seed | P(%) | R(%) | mAP50(%) | mAP50-95(%) | FPS |
|---|---|---|---|---|---|---|
| SPDConv | 0 | 76.294 | 77.168 | 76.121 | 62.912 | 147.97 |
| SPDConv | 1 | 76.975 | 79.244 | 79.081 | 65.005 | 150.38 |
| SPDConv | 2 | 78.744 | 78.534 | 80.415 | 66.211 | 144.15 |
| SPDConv+SHEB | 0 | 76.774 | 80.833 | 80.527 | 66.701 | 125.36 |
| SPDConv+SHEB | 1 | 78.886 | 78.882 | 80.783 | 66.291 | 127.65 |
| SPDConv+SHEB | 2 | 78.515 | 78.842 | 81.009 | 66.954 | 123.33 |

*Source: [`per_seed/C12_spdconv_vs_fsdconv_per_seed.csv`](per_seed/C12_spdconv_vs_fsdconv_per_seed.csv)*

---

## C.13  SHEB branch ablation (Appendix B.3)

Local (L), mid-range (M) and global (G) branch combinations.

*`SHEB(-LMG)` is the full SHEB, i.e. the `YOLO11n +SHEB` row of C.2; `FSDH-YOLO` is the same row as in C.2. Appendix C does not repeat them here.*

| Model | seed | P(%) | R(%) | mAP50(%) | mAP50-95(%) | FPS |
|---|---|---|---|---|---|---|
| SHEB-L（仅局部） | 0 | 76.646 | 78.846 | 76.910 | 63.457 | 151.77 |
| SHEB-L（仅局部） | 1 | 77.984 | 79.254 | 81.500 | 66.589 | 144.69 |
| SHEB-L（仅局部） | 2 | 77.984 | 78.101 | 80.088 | 65.435 | 144.96 |
| SHEB-M（仅中程） | 0 | 78.351 | 80.461 | 82.794 | 67.724 | 150.11 |
| SHEB-M（仅中程） | 1 | 78.421 | 79.997 | 81.148 | 66.630 | 155.55 |
| SHEB-M（仅中程） | 2 | 78.809 | 79.870 | 81.627 | 66.914 | 148.48 |
| SHEB-G（仅全局） | 0 | 75.933 | 78.154 | 79.301 | 65.057 | 145.00 |
| SHEB-G（仅全局） | 1 | 75.948 | 81.812 | 81.710 | 66.705 | 144.17 |
| SHEB-G（仅全局） | 2 | 75.422 | 80.653 | 81.245 | 66.551 | 148.91 |
| SHEB-LM | 0 | 77.882 | 79.049 | 80.326 | 65.700 | 137.34 |
| SHEB-LM | 1 | 76.544 | 79.439 | 80.871 | 66.048 | 142.77 |
| SHEB-LM | 2 | 76.431 | 78.085 | 79.993 | 65.573 | 145.81 |
| SHEB-LG | 0 | 75.739 | 80.089 | 78.173 | 64.350 | 145.71 |
| SHEB-LG | 1 | 77.108 | 80.578 | 81.530 | 66.865 | 138.53 |
| SHEB-LG | 2 | 76.619 | 79.232 | 80.292 | 65.537 | 133.68 |
| SHEB-MG | 0 | 78.033 | 77.647 | 79.558 | 65.673 | 136.96 |
| SHEB-MG | 1 | 76.719 | 80.323 | 79.386 | 65.452 | 147.61 |
| SHEB-MG | 2 | 75.810 | 81.742 | 81.683 | 66.828 | 142.49 |
| FSDConv+SHEB-M | 0 | 75.832 | 80.153 | 79.147 | 65.081 | 149.84 |
| FSDConv+SHEB-M | 1 | 80.670 | 78.011 | 79.613 | 65.708 | 151.17 |
| FSDConv+SHEB-M | 2 | 78.553 | 78.794 | 81.759 | 67.052 | 145.43 |

*Source: [`per_seed/C13_sheb_branch_ablation_per_seed.csv`](per_seed/C13_sheb_branch_ablation_per_seed.csv)*

---
