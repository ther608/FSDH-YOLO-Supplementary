package com.lychee.fsdh.bench

import org.json.JSONArray
import org.json.JSONObject

/**
 * One continuous live-camera measurement session (the 60 s single-cluster run).
 *
 * Four decisions are baked in, all of them about not letting the reported
 * number drift away from what was actually measured:
 *
 *  1. EVERY frame the analyzer processed is recorded, with its own wall time.
 *     End-to-end is MEASURED around the whole per-frame pipeline (YUV->RGB
 *     conversion -> letterbox -> inference -> decode/NMS) rather than computed
 *     as pre + infer + post. Those are not the same thing: the conversion and
 *     the scheduling around it cost real time, and defining E2E as the sum
 *     silently drops it. The difference is carried as `overhead` and reported,
 *     so T_e2e = T_pre + T_infer + T_post + T_overhead holds by construction
 *     instead of by assumption.
 *
 *  2. The leading [warmupFrames] frames are recorded but EXCLUDED from the
 *     statistics, so the figures describe the steady state rather than the
 *     clock ramp and lazy graph setup.
 *
 *  3. Statistics are computed HERE, from the raw list, after the run. The
 *     20-frame rolling average on the live screen is a display convenience and
 *     is never a result.
 *
 *  4. Screenshots are taken at fixed points in the window (5 s, 10 s, ... 60 s
 *     for 12 over 60 s) instead of by hand, so the sampled frames are spread
 *     evenly across the run rather than clustered wherever someone tapped.
 *     Their filenames are deterministic, which is what lets the JSON list all
 *     of them even though the last PNG is still being encoded when the session
 *     closes.
 */
class LiveSession(
    /** SystemClock.elapsedRealtime() when recording started. */
    val startedAtMs: Long,
    val durationMs: Long = 60_000,
    val warmupFrames: Int = 20,
    val shotCount: Int = 12,
) {

    data class Frame(
        val index: Int,
        /** Milliseconds since the session started. */
        val tMs: Long,
        /** Measured wall time of the whole per-frame pipeline. */
        val wallMs: Double,
        val preMs: Double,
        val inferMs: Double,
        val postMs: Double,
        val detections: Int,
    ) {
        /** Pipeline time not attributed to any stage: YUV->RGB, scheduling, JNI. */
        val overheadMs: Double get() = (wallMs - preMs - inferMs - postMs).coerceAtLeast(0.0)
    }

    /** Everything about the run that is not a per-frame number. */
    data class Context(
        val engine: String,
        val model: String,
        val backend: String,
        val threads: String,
        val streamWidth: Int,
        val streamHeight: Int,
        val networkSize: Int,
        val device: Map<String, String>,
    )

    val frames = ArrayList<Frame>()
    private var shotsTaken = 0

    val processedFrames: Int get() = frames.size

    /** Frames the statistics will cover: everything past the warm-up. */
    val measuredFrames: Int get() = (frames.size - warmupFrames).coerceAtLeast(0)

    fun elapsedMs(nowMs: Long): Long = nowMs - startedAtMs

    /** True once the frame that crosses the end of the window has been recorded. */
    fun isFinished(f: Frame): Boolean = f.tMs >= durationMs

    /**
     * Records one processed frame and answers whether a screenshot is due.
     *
     * Returns the index of the screenshot to take for this frame, or -1. The
     * caller is expected to have copied the frame's bitmap already: the timing
     * of this frame must not include the copy or the PNG encode, and both are
     * the caller's problem to keep outside the measured window.
     */
    fun onFrame(f: Frame): Int {
        frames.add(f)
        if (shotsTaken >= shotCount) return -1
        // shot k is due at (k+1) * duration / shotCount, so the last one lands
        // exactly on the end of the window
        val due = (shotsTaken + 1).toLong() * durationMs / shotCount
        if (f.tMs < due) return -1
        return shotsTaken++
    }

    /** Deterministic name for shot [index]; see decision 4 above. */
    fun shotName(index: Int, stamp: Long): String {
        val atMs = (index + 1).toLong() * durationMs / shotCount
        return "live_${stamp}_t%03ds.png".format(atMs / 1000)
    }

    fun shotCount(): Int = shotsTaken

    /** All shot names, whether or not the files have been written yet. */
    fun shotNames(stamp: Long): List<String> = (0 until shotCount).map { shotName(it, stamp) }

    fun summary(): Summary {
        val measured = frames.drop(warmupFrames)
        require(measured.isNotEmpty()) { "no frames past the warm-up" }
        return Summary(
            durationActualMs = frames.last().tMs,
            processedFrames = frames.size,
            measuredFrames = measured.size,
            warmupFrames = warmupFrames.coerceAtMost(frames.size),
            e2e = Stats.summarise(measured.map { it.wallMs }),
            pre = Stats.summarise(measured.map { it.preMs }),
            infer = Stats.summarise(measured.map { it.inferMs }),
            post = Stats.summarise(measured.map { it.postMs }),
            overhead = Stats.summarise(measured.map { it.overheadMs }),
        )
    }

    data class Summary(
        val durationActualMs: Long,
        val processedFrames: Int,
        val measuredFrames: Int,
        val warmupFrames: Int,
        val e2e: Stats.Summary,
        val pre: Stats.Summary,
        val infer: Stats.Summary,
        val post: Stats.Summary,
        val overhead: Stats.Summary,
    ) {
        /** FPS from the mean end-to-end time, the same window as every other figure. */
        val fps: Double get() = 1000.0 / e2e.mean
    }

    /** One-line result for the status strip; kept short enough not to wrap. */
    fun headline(s: Summary): String =
        "%.0fs · %d frames · E2E %.1f ms · P95 %.1f ms · %.2f FPS".format(
            s.durationActualMs / 1000.0, s.processedFrames,
            s.e2e.mean, s.e2e.p95, s.fps,
        )

    fun toJson(ctx: Context, s: Summary, stamp: Long, endedReason: String): String {
        val root = JSONObject()
        root.put("schema", "fsdh-yolo-android-live-session/1")
        root.put("endedReason", endedReason)

        root.put("device", JSONObject().apply { ctx.device.forEach { (k, v) -> put(k, v) } })

        root.put(
            "deployment",
            JSONObject().apply {
                put("model", ctx.model)
                put("engine", ctx.engine)
                put("backend", ctx.backend)
                put("threads", ctx.threads)
                put("networkInput", "${ctx.networkSize}x${ctx.networkSize}")
                put("cameraStream", "${ctx.streamWidth}x${ctx.streamHeight}")
                put("inputLayout", "NCHW")
            },
        )

        root.put(
            "protocol",
            JSONObject().apply {
                put("durationTargetMs", durationMs)
                put("durationActualMs", s.durationActualMs)
                put("warmupFramesExcluded", s.warmupFrames)
                put("shotCount", shotCount)
                put("shotIntervalMs", durationMs / shotCount)
                put(
                    "e2eDefinition",
                    "Measured wall time around the whole per-frame pipeline on the " +
                        "analyzer thread: YUV->RGB conversion, letterbox, inference, " +
                        "decode and NMS. Excludes overlay drawing and PNG encoding. " +
                        "T_e2e = T_pre + T_infer + T_post + T_overhead holds by " +
                        "construction; overhead is reported, not dropped.",
                )
                put(
                    "statistics",
                    "Computed after the run from every recorded frame past the " +
                        "warm-up. SD is the sample SD (n-1). P95 is the nearest-rank " +
                        "percentile on the sorted sample (no interpolation).",
                )
                put(
                    "scope",
                    "Preliminary on-device deployment validation. One camera run over " +
                        "one target cluster; frames are not independent samples and no " +
                        "detection-accuracy metric is derived from them.",
                )
            },
        )

        root.put(
            "counts",
            JSONObject().apply {
                put("processedFrames", s.processedFrames)
                put("measuredFrames", s.measuredFrames)
                put("excludedWarmupFrames", s.warmupFrames)
                put("shotsSaved", shotCount)
            },
        )

        root.put(
            "summary",
            JSONObject().apply {
                put("fpsFromMeanE2e", s.fps)
                put("e2e", statsJson(s.e2e))
                put("infer", statsJson(s.infer))
                put("pre", statsJson(s.pre))
                put("post", statsJson(s.post))
                put("overhead", statsJson(s.overhead))
            },
        )

        root.put("shots", JSONArray(shotNames(stamp)))

        // Every frame, warm-up included, so the exclusions can be re-derived
        // rather than taken on trust.
        root.put(
            "frames",
            JSONArray().apply {
                for (f in frames) put(
                    JSONObject().apply {
                        put("i", f.index)
                        put("tMs", f.tMs)
                        put("wallMs", f.wallMs)
                        put("preMs", f.preMs)
                        put("inferMs", f.inferMs)
                        put("postMs", f.postMs)
                        put("overheadMs", f.overheadMs)
                        put("dets", f.detections)
                        put("excluded", f.index < warmupFrames)
                    },
                )
            },
        )
        return root.toString(2)
    }

    private fun statsJson(s: Stats.Summary): JSONObject = JSONObject().apply {
        put("n", s.n)
        put("mean", s.mean)
        put("sd", s.sd)
        put("median", s.median)
        put("p95", s.p95)
        put("min", s.min)
        put("max", s.max)
    }
}
