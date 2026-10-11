# Deployment evaluation results

The measured results behind §3.9 and Appendix B.5, as summary tables. Every value is
derived from the raw device records in [`raw_records/`](raw_records) — nothing here was
transcribed by hand — and each table was cross-checked against the paper's own appendix.

| File | Paper item |
|---|---|
| [`fixed_input_latency.csv`](fixed_input_latency.csv) | Appendix B.5-1 — fixed-input ORT CPU validation |
| [`continuous_camera_sessions.csv`](continuous_camera_sessions.csv) | Appendix B.5-3 (primary), B.5-5 (diagnostics), B.5-6 (back-to-back control) |
| [`thermal_state.csv`](thermal_state.csv) | Appendix B.5-4 — thermal state of the three primary sessions |
| [`verification_results.csv`](verification_results.csv) | Appendix B.5-2 — L1 export equivalence and L2 on-device pipeline equivalence |
| [`deployment_footprint.md`](deployment_footprint.md) | Appendix B.5-7 — APK size, model size, runtime memory |
| [`results_summary.json`](results_summary.json) | Authoritative summary of all of the above, as written by the run harness |

---

## The three columns to read first

**`continuous_camera_sessions.csv`** carries a `section` column, and it matters:

| `section` value | Meaning |
|---|---|
| `predefined protocol (primary result)` | the three 60 s runs that **are** the reported deployment result |
| `post-hoc diagnostic (not a result)` | investigation of why the two builds differed; **not** a performance result |
| `back-to-back control (no reset between runs)` | methodological contrast showing what happens without the inter-run reset; **not** a result |

Sessions are never pooled. Each 60 s run is reported on its own, with its own frame
count, because the device's absolute latency drifts between sessions — that drift is
itself reported in Appendix B.5 and is why the paper treats the mobile work as a
preliminary validation rather than a controlled benchmark.

## Protocol in one table

| Item | Value |
|---|---|
| Session length | 60 s per run |
| Warm-up excluded | first 20 processed frames |
| Frame strategy | single-thread executor, `STRATEGY_KEEP_ONLY_LATEST` — stale frames are dropped, not queued |
| End-to-end latency | wall clock over YUV→RGB, letterbox, inference, decode and NMS; excludes rendering and PNG encoding |
| P95 | nearest-rank, no interpolation |
| SD | sample standard deviation (n − 1) |
| FPS | 1000 / mean end-to-end latency over the same frames |
| Inter-run reset | app force-stopped; restarted once the HAL SKIN sensor falls to ≤ 46 °C (300 s cap) |

The reset threshold only defines *when the app restarts*. Camera binding, ORT session
setup and the first inferences reheat the device within ~10 s, so the temperature at the
start of official timing is well above 46 °C. That reading is recorded separately as
`pre_timing_skin_c` and is what the paper reports.

## Two cells that were corrected against the raw record

Both are in diagnostic or record fields and neither affects a reported result. They were
found while deriving the tables above and have been corrected in the extended appendix
shipped with this repository
([`../extended_paper_materials/appendix_B5_deployment_experiments.md`](../extended_paper_materials/appendix_B5_deployment_experiments.md)):

| Cell | Was | Now | Why |
|---|---|---|---|
| Table B.5-1, FSDH-YOLO `post` | 0.5 ms | **0.4 ms** | Every other cell in that table uses the rounds-1–3 caliber (round 0 is the excluded cold start); 3-of-3 gives 0.4468 → 0.4. The 0.5 figure was the all-four-rounds mean (0.4562). Decode/NMS is a diagnostic split — the end-to-end 157.5 ms / 6.35 FPS are unaffected. |
| Table B.5-4, run 2, pre-timing charge | 81 % | **80 %** | 81 % was the *cooldown* reading (17:15:42); the pre-timing reading (17:15:53) is 80 %. Runs 1 and 3 happen to have identical values at both checkpoints, so only run 2 exposed the mix-up. Charge level is a record field, not a measurement. |

The corresponding CSVs in this folder had always carried the record-derived values
(0.4 and 80 %); the tables above are now consistent with them.

## Raw records

[`raw_records/`](raw_records) holds the unprocessed material: the per-frame session
records, the fixed-input benchmark runs, the protocol harness scripts, the export and
verification records, and the archive notes for the deployment chains that were removed
from the paper. Read [`raw_records/README.md`](raw_records/README.md) before interpreting
anything there — it lists the caveats that apply.
