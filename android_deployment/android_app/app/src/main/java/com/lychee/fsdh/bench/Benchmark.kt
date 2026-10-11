package com.lychee.fsdh.bench

import android.graphics.Bitmap
import android.os.Build
import com.lychee.fsdh.infer.DetectParams
import com.lychee.fsdh.infer.InferenceEngine
import kotlin.math.sqrt

/**
 * On-device latency benchmark.
 *
 * Three measurement decisions are baked in, each one answering a specific way
 * the earlier web-app measurements went wrong (see APPENDIX_A7.md):
 *
 *  1. END-TO-END IS THE HEADLINE. The pre/infer/post split is recorded for
 *     diagnosis but never as the reported figure: on the WebGPU path the
 *     in-loop "pre" number absorbed ~11 ms of GPU wait, which a pure
 *     preprocessing microbenchmark showed should have been ~3 ms. Only the
 *     end-to-end number is comparable across backends.
 *
 *  2. MODELS ARE ROTATED BETWEEN ROUNDS, not measured back-to-back. The same
 *     model measured three times in a row drifted 122 -> 124 -> 134 ms from
 *     thermal effects alone. Rotating the order every round spreads that drift
 *     across all models instead of dumping it on whichever ran last.
 *
 *  3. ROUND 0 IS REPORTED BUT EXCLUDED from the headline. The first round
 *     carries cold-start transients (graph allocation, JIT, clock ramp); with
 *     it included, inter-round SD was 5.92 / 11.18 ms, without it 0.25 / 0.84.
 *
 * Frames are captured up front and replayed, so the camera's own frame-delivery
 * jitter is not part of the measurement.
 */
object Benchmark {

    data class FrameSample(val round: Int, val model: String, val totalMs: Double,
                           val preMs: Double, val inferMs: Double, val postMs: Double,
                           val detections: Int)

    data class ModelSummary(
        val model: String,
        val rounds: Int,
        /** Round 0 only -- the cold-start figure. Reported so the transient is
         *  visible, but never the headline number. */
        val firstRoundMeanMs: Double,
        val allRoundsMeanMs: Double,
        val allRoundsSdMs: Double,
        val dropFirstMeanMs: Double,
        val dropFirstSdMs: Double,
        val fpsDropFirst: Double,
        val meanDetections: Double,
        /** How much slower round 0 was than the steady state, in percent. */
        val coldStartPenaltyPct: Double,
    )

    /**
     * @param engines   engines to compare; each is warmed up once before timing
     * @param frames    fixed replay set, identical for every engine
     * @param rounds    number of full passes over all engines (>=2 for the
     *                  drop-first-round statistic to mean anything)
     * @param onProgress invoked on the calling thread between measurements
     */
    fun run(
        engines: List<InferenceEngine>,
        frames: List<Bitmap>,
        params: DetectParams,
        rounds: Int = 4,
        /** Invoked before every round, round 0 included. Used to wait for the
         *  device to cool: without it a 6-engine sweep runs ~90 s of continuous
         *  inference and the last round is measured 40-90% slower than the
         *  first purely from thermal throttling. */
        beforeRound: ((Int) -> Unit)? = null,
        onProgress: (String) -> Unit = {},
    ): Pair<List<FrameSample>, List<ModelSummary>> {

        // Warm-up is deliberately outside all timing. It also forces any lazy
        // graph setup to happen before round 0 rather than inside it.
        for (e in engines) {
            onProgress("warm-up: ${e.name}")
            e.warmUp(frames.first(), params)
        }

        val samples = ArrayList<FrameSample>()

        for (r in 0 until rounds) {
            beforeRound?.invoke(r)
            // rotate: round r starts with the r-th engine and wraps around
            val order = engines.indices.map { engines[(it + r) % engines.size] }
            for (engine in order) {
                onProgress("round $r · ${engine.name}")
                for (f in frames) {
                    // one untimed call per (round, engine) to absorb the
                    // per-round state change before the timed frame
                    val res = engine.detect(f, params)
                    samples.add(
                        FrameSample(
                            round = r,
                            model = engine.name,
                            totalMs = res.totalMs,
                            preMs = res.preMs,
                            inferMs = res.inferMs,
                            postMs = res.postMs,
                            detections = res.detections.size,
                        )
                    )
                }
            }
        }

        val summaries = engines.map { e -> summarise(e.name, samples) }
        return samples to summaries
    }

    private fun summarise(model: String, all: List<FrameSample>): ModelSummary {
        val mine = all.filter { it.model == model }
        val totals = mine.map { it.totalMs }

        val firstRound = mine.filter { it.round == 0 }.map { it.totalMs }
        val rest = mine.filter { it.round > 0 }.map { it.totalMs }

        val allMean = totals.average()
        val restMean = if (rest.isEmpty()) Double.NaN else rest.average()

        val firstMean = if (firstRound.isEmpty()) Double.NaN else firstRound.average()
        val coldPenalty =
            if (firstMean.isNaN() || restMean.isNaN() || restMean <= 0) Double.NaN
            else (firstMean - restMean) / restMean * 100.0

        return ModelSummary(
            model = model,
            rounds = (mine.maxOfOrNull { it.round } ?: 0) + 1,
            firstRoundMeanMs = firstMean,
            allRoundsMeanMs = allMean,
            allRoundsSdMs = sd(totals, allMean),
            dropFirstMeanMs = restMean,
            dropFirstSdMs = if (rest.isEmpty()) Double.NaN else sd(rest, restMean),
            fpsDropFirst = if (restMean.isNaN() || restMean <= 0) Double.NaN else 1000.0 / restMean,
            meanDetections = mine.map { it.detections }.average(),
            coldStartPenaltyPct = coldPenalty,
        )
    }

    private fun sd(xs: List<Double>, mean: Double): Double {
        if (xs.size < 2) return Double.NaN
        val v = xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1)
        return sqrt(v)
    }

    fun deviceInfo(): Map<String, String> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "device" to Build.DEVICE,
        "soc" to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "n/a"),
        "hardware" to Build.HARDWARE,
        "androidRelease" to Build.VERSION.RELEASE,
        "sdkInt" to Build.VERSION.SDK_INT.toString(),
        "supportedAbis" to Build.SUPPORTED_ABIS.joinToString(","),
    )
}
