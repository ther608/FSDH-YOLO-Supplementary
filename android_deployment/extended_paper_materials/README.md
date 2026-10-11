# Extended paper materials — mobile deployment

The three documents here describe the mobile deployment work. **They are the full
versions, not the condensed text that appears in the manuscript.**

| File | Manuscript counterpart |
|---|---|
| [`section_3.9_mobile_deployment.md`](section_3.9_mobile_deployment.md) | §3.9 — the manuscript renders this as a few paragraphs plus Table 13 |
| [`appendix_A7_deployment_protocol.md`](appendix_A7_deployment_protocol.md) | Appendix A.7 — the manuscript renders this as four paragraphs setting out the protocol |
| [`appendix_B5_deployment_experiments.md`](appendix_B5_deployment_experiments.md) | Appendix B.5 — the manuscript renders this as a single table of the three 60 s runs plus the post-hoc recheck |

The manuscript's versions are **condensed restatements** of these documents. Everything
here is a superset: it carries the full protocol definitions, the per-section tables
(fixed-input latency, export and decode verification, thermal state, post-hoc
diagnostics, back-to-back control, deployment footprint) and the reasoning behind the
qualifications in the text.

They are provided in full because the condensed manuscript text leaves several claims
without their supporting tables — in particular the fixed-input latency decomposition,
the L1/L2 verification chain, and the thermal state of the three sessions.

## Language

These are the manuscript's own working documents and are **in Chinese**, matching the
manuscript. Only this repository's own navigation text is in English. Translating them
would create a second, unofficial version of the paper's own text.

## Numerical agreement

Every table here was re-derived from the raw device records in
[`../deployment_evaluation_results/`](../deployment_evaluation_results) and checked
cell by cell. Two cells were corrected in this pass so that the tables agree with the
records:

| Table | Cell | Was | Now |
|---|---|---|---|
| B.5-1 | FSDH-YOLO `post` | 0.5 ms | **0.4 ms** — the rest of the table uses the rounds-1–3 caliber; 0.5 was the all-four-rounds mean |
| B.5-4 | Run 2, pre-timing charge | 81 % | **80 %** — 81 % was the cooldown reading; the pre-timing reading is 80 % |

Neither change affects a reported result: B.5-1's end-to-end figures (157.5 ms / 6.35 FPS)
and every temperature in B.5-4 are unchanged, and decode/NMS plus charge level are
diagnostic fields.
