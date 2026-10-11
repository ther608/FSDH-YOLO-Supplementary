# FSDH-YOLO — Supplementary Materials

Supplementary materials for the manuscript

> **一种面向自然果园荔枝成熟度检测的空间到深度重排与层级特征增强模型**
>
> *Spatial-to-Depth Rearrangement and Hierarchical Feature Enhancement for
> Litchi Maturity Detection in Natural Orchards* (English rendering of the
> working title; the manuscript itself is written in Chinese).

**Model name:** FSDH-YOLO (*Feature-preserving Space-to-Depth and Hierarchical-enhancement YOLO*),
built on a YOLO11n baseline.

---

## What this repository is

This is a **supplementary materials repository**, not an open-source release of the
full training pipeline. It provides the material a reviewer needs in order to
(a) read every number that appears in the paper's per-seed appendix,
(b) re-derive the reported means and standard deviations,
and (c) inspect the Android on-device deployment package end to end.

| Provided here | Not provided here |
|---|---|
| Appendix C — per-seed results for all 13 experiment tables | Training / evaluation source code of the full framework |
| The Android deployment package: Kotlin app, deployment protocol, all raw measurement records, exported ONNX models | The datasets (see *Data availability*) |
| Evaluation-protocol description and reproducibility notes | PyTorch checkpoints (`.pt`) — regenerating them requires training |

The complete training implementation is based on the publicly available
[Ultralytics](https://github.com/ultralytics/ultralytics) framework (v8.4.11).

---

## Contents

```
.
├── Appendix_C/                  Per-seed results of every paper experiment
│   ├── Appendix_C.md              Appendix C rendered as markdown tables
│   └── per_seed/*.csv             13 machine-readable CSVs
│
├── android_deployment/          §3.9 + Appendix A.7 + Appendix B.5
│   ├── extended_paper_materials/  §3.9, A.7 and B.5 in full
│   ├── android_app/               Android Studio project (Kotlin)
│   ├── deployment_evaluation_results/  summary tables + raw device records
│   ├── scripts/                   export & verification scripts
│   └── PROVENANCE.md              artifact hashes and source-run mapping
│
├── evaluation_protocol/         How every reported metric was computed
│
└── reproduction_notes/          Environment, commands, and provenance map
```

---

## Appendix C in one paragraph

Every experiment in the paper was run with **three independent random seeds (0, 1, 2)**.
The main text reports the arithmetic mean and the **sample standard deviation (ddof = 1)**
of those three runs; Appendix C reports the individual per-seed values, together with
per-seed FPS measured under a separate, uniform latency protocol.

`Appendix_C/per_seed/` contains one CSV per appendix table. Values are transcribed
**verbatim** from the manuscript — no recomputation, no re-rounding.

> **Note on last-digit differences.** Appendix C prints per-seed values to 3 decimals,
> while the paper's mean ± SD was computed from full-precision values. Recomputing a
> mean/SD from the 3-decimal per-seed numbers therefore reproduces the paper's value to
> within ±0.001 in the last digit. This was checked exhaustively (434 quantities) and
> **no difference larger than 0.001 was found**.

---

## Headline results (Dataset A test split, 3 seeds)

| Model | P / % | R / % | mAP50 / % | mAP50-95 / % | Params / M | FPS |
|---|---|---|---|---|---|---|
| YOLO11n | 78.007±1.588 | 78.174±2.110 | 78.984±0.959 | 64.429±0.603 | 2.583 | 146.69±4.87 |
| **FSDH-YOLO** | **79.989±2.168** | 78.443±1.127 | **81.766±0.994** | **67.297±0.718** | **2.997** | 123.39±2.94 |

Android on-device (Redmi K40 Gaming Enhanced Edition, ONNX Runtime CPU backend):

| Scenario | Model | Latency / ms | FPS |
|---|---|---|---|
| Fixed input | YOLO11n | 128.8 ± 6.9 | 7.76 |
| Fixed input | FSDH-YOLO | 157.5 ± 8.3 | 6.35 |
| Continuous camera (3 × 60 s) | FSDH-YOLO | 242.34 – 257.09 | 3.89 – 4.13 |

---

## Data availability

The datasets used in this study were obtained from publicly available sources and are
**not redistributed** in this repository.

- **Dataset A** — RGB subset of the public RGB-D litchi dataset of Zhang et al.,
  *An RGB-D image dataset for lychee detection and maturity classification for robotic
  harvesting*, arXiv:2510.16800 (`doi:10.48550/arXiv.2510.16800`); after removal of
  36 indoor tabletop images, 842 natural-orchard images were retained and re-split
  under a group-aware protocol (590 / 126 / 126).
- **Dataset B** — the public `lichi-maturity` dataset (Zhiqing Zhao, Mendeley Data, V1,
  `doi:10.17632/c3rk9gv4w9.1`).

Please refer to the original dataset sources and to the manuscript for data access.

---

## Code availability

This repository provides supplementary resources, experimental configurations and
deployment artefacts. It does **not** contain the complete training implementation,
the datasets, or the trained PyTorch checkpoints.

The deployment package under `android_deployment/` is a complete, buildable
Android Studio project and is the exact application used to produce the mobile
results in §3.9 and Appendix B.5.

---

## Verification performed before publication

All numbers in this repository were re-derived from the project's own source
artefacts and cross-checked against the manuscript:

| Layer | Comparison | Quantities | Mismatches |
|---|---|---|---|
| 1 | Appendix C per-seed → paper mean ± SD | 434 | 0 |
| 2 | Appendix C per-seed ↔ experiment record | 68 | 0 |
| 3 | Appendix C / record ↔ raw source artefacts | 825 | 0 |
| 4 | Deployment tables ↔ raw device records | all | 0 |

See [`reproduction_notes/`](reproduction_notes) for the full provenance map.

Two documentation defects were found **in the manuscript** during this pass, both
labelling/rounding issues that do not affect a computed result; they are reported in
`reproduction_notes/README.md` § *Known manuscript issues* so they can be fixed before
submission.

Separately, two cells in the **extended deployment appendix** shipped here disagreed with
the raw device records. Those have been **corrected in this repository** — see
[`android_deployment/extended_paper_materials/README.md`](android_deployment/extended_paper_materials/README.md).

---

## Citation

See [`CITATION.cff`](CITATION.cff). The authors are listed as **Anonymous Authors**
because the manuscript is under review; the real author list, the article title and the
DOI will replace them once the paper is published.

---

## License

- Source code (`android_deployment/android_app/`, `android_deployment/scripts/`) — [MIT](LICENSE)
- Data, documentation and results — [CC BY 4.0](LICENSE-docs)

---

## Contact

Issue reports and questions are welcome via the GitHub issue tracker.
