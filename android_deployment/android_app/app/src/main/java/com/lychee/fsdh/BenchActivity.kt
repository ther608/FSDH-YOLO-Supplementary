package com.lychee.fsdh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.lychee.fsdh.bench.Benchmark
import com.lychee.fsdh.bench.PowerMonitor
import com.lychee.fsdh.camera.YuvToRgb
import com.lychee.fsdh.databinding.ActivityBenchBinding
import com.lychee.fsdh.infer.Backend
import com.lychee.fsdh.infer.DetectParams
import com.lychee.fsdh.infer.EngineFactory
import com.lychee.fsdh.infer.InferenceEngine
import com.lychee.fsdh.infer.ModelRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Captures a fixed frame set, then replays it through every (model x provider)
 * combination that loads, rotating the order each round. See [Benchmark] for
 * why the harness is shaped this way.
 */
class BenchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBenchBinding
    private val executor = Executors.newSingleThreadExecutor()

    private val frames = ArrayList<Bitmap>()
    private var json: String? = null

    private lateinit var power: PowerMonitor

    /** Set while the sampler thread should keep polling the fuel gauge. */
    @Volatile
    private var sampling = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBenchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCapture.setOnClickListener { captureFrames() }
        binding.btnRun.setOnClickListener { runBenchmark() }
        binding.btnShare.setOnClickListener { share() }

        binding.status.movementMethod = android.text.method.ScrollingMovementMethod()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)

        log("Device: ${Benchmark.deviceInfo()}")
        log("Order: 1. Capture → 2. Run → 3. Export.")
        log("Keep the phone still after capture; the same frames are replayed.")
    }

    private fun log(s: String) {
        runOnUiThread {
            binding.status.append("\n$s")
            // The log outgrows the viewport within seconds, and nothing scrolls
            // it -- the power summary, which lands last, was invisible without
            // this. Post so the layout has the new line before we scroll to it.
            //
            // Scroll to the END of the content, not to the top of the last line.
            // Scrolling to getLineTop(last) puts the final line at the top of
            // the viewport and, while the log is still shorter than the pane,
            // leaves everything above it scrolled out of sight -- the screen
            // showed one line of text above a large blank area.
            binding.status.post {
                val layout = binding.status.layout ?: return@post
                binding.status.scrollTo(
                    0, (layout.height - binding.status.height).coerceAtLeast(0)
                )
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                // overloaded setter -> no Kotlin synthetic property; call it directly
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }
            binding.preview.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER

            // Must match MainActivity's configuration, or the benchmark measures
            // a different preprocessing path than the live screen: the captured
            // frames are letterboxed, and a different stream aspect means a
            // different letterbox. (The previous build used the deprecated
            // setTargetResolution(1280, 720) here and produced 960x960 frames
            // while the live view ran at 480x640 -- not comparable.)
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy -> capture(proxy) }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private var capturing = false

    private fun captureFrames() {
        frames.forEach { it.recycle() }
        frames.clear()
        capturing = true
        binding.btnRun.isEnabled = false
        binding.btnShare.isEnabled = false
        log("\nCapturing frames — keep the lychees in view…")
    }

    private fun capture(proxy: ImageProxy) {
        if (!capturing) { proxy.close(); return }
        try {
            val bmp = YuvToRgb.convert(proxy, proxy.imageInfo.rotationDegrees)
            // Downscale so a dozen retained frames don't pin tens of MB of heap.
            val scaled = downscale(bmp, MAX_FRAME_SIDE)
            synchronized(frames) { frames.add(scaled) }
            val n = frames.size
            if (n % 4 == 0) log("  captured $n / $FRAME_COUNT")
            if (n >= FRAME_COUNT) {
                capturing = false
                runOnUiThread {
                    binding.btnRun.isEnabled = true
                    log("Capture done: $n frames (${scaled.width}x${scaled.height})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
        } finally {
            proxy.close()
        }
    }

    /**
     * Returns a bitmap the caller owns. Never recycles [src]: the incoming frame
     * is YuvToRgb's shared, reused-every-frame buffer, and recycling it here
     * would make the next frame's setPixels throw.
     */
    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= maxSide) return src.copy(Bitmap.Config.ARGB_8888, false)
        val ratio = maxSide.toFloat() / long
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun runBenchmark() {
        val snapshot = synchronized(frames) { ArrayList(frames) }
        if (snapshot.isEmpty()) {
            Toast.makeText(this, "Capture frames first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnRun.isEnabled = false
        binding.btnCapture.isEnabled = false

        executor.execute {
            val engines = ArrayList<InferenceEngine>()
            val skipped = ArrayList<String>()

            // Every model x backend pair that actually loads. Failures are the
            // interesting part here, not noise: a missing .tflite, or a GPU
            // delegate that refuses the graph because of the opening 6-D
            // Transpose, both surface as a skipped entry with its reason rather
            // than as a silently slow "GPU" row.
            for (model in ModelRegistry.MODELS) {
                for (b in Backend.entries) {
                    try {
                        engines.add(EngineFactory.create(this, model, b))
                        log("Loaded ${model.displayName} · ${b.label}")
                    } catch (e: Exception) {
                        skipped.add("${model.displayName} · ${b.label}: ${e.message}")
                        log("Skipped ${model.displayName} · ${b.label} — ${e.message}")
                    }
                }
            }

            if (engines.isEmpty()) {
                log("No engine loaded — stopping.")
                runOnUiThread { binding.btnCapture.isEnabled = true }
                return@execute
            }

            // ---- idle baseline -------------------------------------------------
            // Power is measured as a DIFFERENCE, so the idle window has to be
            // taken immediately before the load window on the same device state.
            power = PowerMonitor(this)
            log("\nIdle baseline ${IDLE_BASELINE_MS / 1000}s — keep the screen on, do not touch the phone…")
            startSampling()
            Thread.sleep(IDLE_BASELINE_MS)
            val idle = stopSampling()
            PowerMonitor.log(idle)
            log(
                "  baseline: %s  samples=%d  battery=%d%%  charging=%s".format(
                    if (idle.supported) "%.1f mA / %.2f V → %.0f mW".format(
                        idle.meanCurrentMa, idle.meanVoltageV, idle.meanPowerMw
                    ) else "device exposes no current/voltage",
                    idle.samples, idle.batteryPercent, idle.charging
                )
            )
            if (idle.charging) {
                log("  ⚠ Charging — power readings are invalid; unplug and rerun.")
            }

            log("\nMeasuring: ${engines.size} engines x ${snapshot.size} frames x $ROUNDS rounds (rotated)")
            log("Each round waits for cooldown; this can take 10+ minutes.")
            val params = DetectParams()
            val thermalLog = ArrayList<String>()
            val totalInferences = engines.size * snapshot.size * ROUNDS

            startSampling()
            val (samples, summaries) = Benchmark.run(
                engines = engines,
                frames = snapshot,
                params = params,
                rounds = ROUNDS,
                beforeRound = { r ->
                    val before = thermalStatus()
                    var waited = 0L
                    if (r > 0) {
                        log("round $r: thermal $before, waiting for cooldown…")
                        waited = waitForCool()
                        log("  waited ${waited / 1000}s → ${thermalStatus()}")
                    }
                    thermalLog.add(
                        "round$r before=$before after=${thermalStatus()} waitedMs=$waited"
                    )
                },
                onProgress = { log("  $it") },
            )
            val load = stopSampling(totalInferences)
            PowerMonitor.log(load)

            val idleMw = idle.meanPowerMw
            val loadMw = load.meanPowerMw
            val deltaMw = PowerMonitor.deltaMw(idle, load)
            log("\n===== Power (whole-device fuel gauge, NOT app-attributable) =====")
            if (!PowerMonitor.usable(load)) {
                log("  unusable: supported=${load.supported} charging=${load.charging} " +
                    "samples=${load.samples}")
            } else {
                log("  idle %.0f mW  →  load %.0f mW  (delta %+.0f mW)".format(idleMw, loadMw, deltaMw ?: 0.0))
                log("  median current: idle %.0f mA → load %.0f mA".format(
                    idle.medianCurrentMa, load.medianCurrentMa))
                load.energyDeltaJoules?.let {
                    log("  load-window energy %.2f J".format(it))
                }
                load.energyPerInferenceMj?.let {
                    log("  energy per inference %.3f mJ (%d inferences)".format(it, totalInferences))
                }
            }

            log("\n===== Summary (headline = first round dropped) =====")
            for (s in summaries) {
                log(
                    "%-34s %.2f ms  SD %.2f  %.2f FPS".format(
                        s.model, s.dropFirstMeanMs, s.dropFirstSdMs, s.fpsDropFirst
                    )
                )
                log(
                    "    all %.2f ms (SD %.2f) · r0 %.2f ms · cold %+.1f%%".format(
                        s.allRoundsMeanMs, s.allRoundsSdMs,
                        s.firstRoundMeanMs, s.coldStartPenaltyPct
                    )
                )
            }

            json = buildJson(
                snapshot, samples, summaries, skipped, params, thermalLog, idle, load
            )
            val f = File(getExternalFilesDir(null), "bench_fsdh_${System.currentTimeMillis()}.json")
            runCatching { f.writeText(json!!) }
                .onSuccess { log("\nWritten: ${f.absolutePath}") }
                .onFailure { log("\nWrite failed: ${it.message}") }

            engines.forEach { runCatching { it.close() } }
            frames.forEach { runCatching { it.recycle() } }
            frames.clear()

            runOnUiThread {
                binding.btnCapture.isEnabled = true
                binding.btnShare.isEnabled = true
            }
        }
    }

    private fun buildJson(
        snapshot: List<Bitmap>,
        samples: List<Benchmark.FrameSample>,
        summaries: List<Benchmark.ModelSummary>,
        skipped: List<String>,
        params: DetectParams,
        thermal: List<String>,
        idle: PowerMonitor.Reading,
        load: PowerMonitor.Reading,
    ): String {
        val root = JSONObject()
        root.put("schema", "fsdh-yolo-android-bench/1")
        root.put("device", JSONObject().apply {
            Benchmark.deviceInfo().forEach { (k, v) -> put(k, v as Any) }
        })
        root.put("params", JSONObject().apply {
            put("confThreshold", params.confThreshold.toDouble())
            put("iouThreshold", params.iouThreshold.toDouble())
            put("maxDetections", params.maxDetections)
            put("classAwareNms", params.classAwareNms)
        })
        root.put("input", JSONObject().apply {
            put("frames", snapshot.size)
            put("width", snapshot.first().width)
            put("height", snapshot.first().height)
        })
        root.put("rounds", ROUNDS)
        root.put("thermal", JSONArray(thermal))

        root.put("power", JSONObject().apply {
            put("scope", "whole-device fuel gauge. This is NOT power attributable " +
                "to this app -- it includes display, radio and idle system load. " +
                "Only the idle->load DELTA is meaningful for inference cost.")
            put("idle", powerJson(idle))
            put("load", powerJson(load))
            put("deltaMw", PowerMonitor.deltaMw(idle, load) ?: JSONObject.NULL)
            put(
                "usable",
                PowerMonitor.usable(load) &&
                    PowerMonitor.usable(idle) &&
                    !load.charging
            )
            put(
                "caveats",
                "Fuel gauges quantise coarsely (50-100 mA steps are common) and " +
                    "update on the order of seconds, so quote the median current " +
                    "and treat short windows as unreliable. The device must be " +
                    "unplugged; a charging reading is meaningless and is flagged."
            )
        })
        root.put("methodology", JSONObject().apply {
            put("headline", "end-to-end only; pre/infer/post recorded for diagnosis")
            put("ordering", "engine order rotated every round to spread thermal drift")
            put("coldStart", "round 0 reported but excluded from dropFirst* figures")
            put(
                "thermal",
                "each round waits until the SoC reports at most LIGHT throttling; " +
                    "see the thermal array for the per-round status"
            )
        })

        root.put("samples", JSONArray().apply {
            for (s in samples) put(JSONObject().apply {
                put("round", s.round); put("model", s.model)
                put("totalMs", s.totalMs); put("preMs", s.preMs)
                put("inferMs", s.inferMs); put("postMs", s.postMs)
                put("detections", s.detections)
            })
        })

        root.put("summary", JSONArray().apply {
            for (s in summaries) put(JSONObject().apply {
                put("model", s.model); put("rounds", s.rounds)
                put("firstRoundMeanMs", s.firstRoundMeanMs)
                put("allRoundsMeanMs", s.allRoundsMeanMs)
                put("allRoundsSdMs", s.allRoundsSdMs)
                put("dropFirstMeanMs", s.dropFirstMeanMs)
                put("dropFirstSdMs", s.dropFirstSdMs)
                put("fpsDropFirst", s.fpsDropFirst)
                put("meanDetections", s.meanDetections)
                put("coldStartPenaltyPct", s.coldStartPenaltyPct)
            })
        })

        if (skipped.isNotEmpty()) {
            root.put("skipped", JSONArray(skipped))
        }
        return root.toString(2)
    }

    // --------------------------------------------------------------- power ---

    /**
     * Polls the fuel gauge on its own thread.
     *
     * Deliberately NOT on [executor]: that executor is saturated by the
     * benchmark itself, so sampling from it would record one reading per engine
     * instead of one per 200 ms and would miss the load peaks entirely.
     */
    private fun startSampling() {
        power.start()
        sampling = true
        Thread {
            while (sampling) {
                power.sample()
                try {
                    Thread.sleep(SAMPLE_PERIOD_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.start()
    }

    private fun stopSampling(inferences: Int = 0): PowerMonitor.Reading {
        sampling = false
        // give the sampler a beat to observe the flag before reading out
        Thread.sleep(SAMPLE_PERIOD_MS)
        return power.stop(inferences)
    }

    // ------------------------------------------------------------ thermals ---

    /**
     * Blocks until the SoC has come back down to at most LIGHT throttling.
     *
     * A 6-engine sweep at 12 frames x 4 rounds is ~90 s of continuous inference;
     * without this the last round measured 40-90% slower than the first and the
     * FSDH/YOLO11n ratio swung between 0.99 and 1.39 -- unusable. Returns the
     * milliseconds spent waiting.
     */
    private fun waitForCool(timeoutMs: Long = 120_000): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // no thermal API; a fixed pause is the best available control
            Thread.sleep(30_000)
            return 30_000
        }
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (thermalLevel() <= PowerManager.THERMAL_STATUS_LIGHT) break
            Thread.sleep(1_000)
        }
        return System.currentTimeMillis() - t0
    }

    private fun thermalLevel(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return (getSystemService(PowerManager::class.java))?.currentThermalStatus ?: -1
    }

    private fun thermalStatus(): String = when (thermalLevel()) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "n/a"
    }

    private fun powerJson(r: PowerMonitor.Reading): JSONObject = JSONObject().apply {
        put("supported", r.supported)
        put("samples", r.samples)
        put("durationMs", r.durationMs)
        put("meanCurrentMa", r.meanCurrentMa)
        put("medianCurrentMa", r.medianCurrentMa)
        put("meanVoltageV", r.meanVoltageV)
        put("meanPowerMw", r.meanPowerMw)
        put("energyDeltaJoules", r.energyDeltaJoules ?: JSONObject.NULL)
        put("energyPerInferenceMj", r.energyPerInferenceMj ?: JSONObject.NULL)
        put("charging", r.charging)
        put("batteryPercent", r.batteryPercent)
        put("note", r.note)
    }

    private fun share() {
        val body = json ?: run {
            Toast.makeText(this, "No results yet", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FSDH-YOLO Android benchmark")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(i, "Export benchmark result"))
    }

    override fun onDestroy() {
        super.onDestroy()
        frames.forEach { runCatching { it.recycle() } }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "BenchActivity"

        /**
         * 40, not 12. Twelve frames was enough to compare backends, but too thin
         * to carry a formal performance claim -- and the paired ratio this
         * deployment section now leans on is only as good as the sample behind
         * it. 40 frames across varied target density and occlusion also makes
         * the per-frame work more representative than one narrow view would.
         */
        private const val FRAME_COUNT = 40
        private const val ROUNDS = 4
        private const val MAX_FRAME_SIDE = 960

        /** Idle baseline window, measured immediately before the load window. */
        private const val IDLE_BASELINE_MS = 10_000L

        /** Fuel gauges update on the order of seconds; 200 ms is plenty. */
        private const val SAMPLE_PERIOD_MS = 200L
    }
}
