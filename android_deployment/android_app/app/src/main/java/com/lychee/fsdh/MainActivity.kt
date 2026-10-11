package com.lychee.fsdh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.lychee.fsdh.bench.Benchmark
import com.lychee.fsdh.bench.LiveSession
import com.lychee.fsdh.camera.YuvToRgb
import com.lychee.fsdh.databinding.ActivityMainBinding
import com.lychee.fsdh.infer.Backend
import com.lychee.fsdh.infer.DetectParams
import com.lychee.fsdh.infer.EngineFactory
import com.lychee.fsdh.infer.InferenceEngine
import com.lychee.fsdh.infer.ModelRegistry
import com.lychee.fsdh.infer.ModelSpec
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Live detection: CameraX preview -> letterbox -> runtime -> decode -> overlay.
 *
 * Two invariants this screen depends on, both learned the hard way on device:
 *
 *  - Preview and ImageAnalysis are pinned to the SAME aspect ratio, and both the
 *    PreviewView and OverlayView use contain. If the streams disagree, the
 *    overlay is drawn against a differently-framed image than the user sees and
 *    every box lands in the wrong place.
 *  - Inference runs on a single-thread executor with KEEP_ONLY_LATEST, so a
 *    frame that arrives while one is still in flight is dropped rather than
 *    queued; otherwise a device slower than the camera builds an unbounded
 *    backlog and preview latency climbs without limit.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    private var engine: InferenceEngine? = null
    private var params = DetectParams(confThreshold = 0.45f, iouThreshold = 0.70f)

    private var modelSpec: ModelSpec = ModelRegistry.DEFAULT

    /** Written by the UI thread when the user picks a backend, and written back
     *  by the analysis thread when a backend falls back to CPU. */
    @Volatile
    private var backend: Backend = Backend.ORT_CPU

    @Volatile
    private var useFrontCamera = false

    private val busy = AtomicBoolean(false)

    /** Set while the spinner is moved programmatically, so the listener that
     *  would otherwise fire does not re-enter rebuildEngine. */
    @Volatile
    private var suppressSpinner = false

    private var framesSeen = 0

    /**
     * Rolling DISPLAY window. Not a result: every figure in the paper comes
     * from [LiveSession], which keeps the raw per-frame list and is summarised
     * after the run.
     *
     * All four metrics share this one window, and FPS is derived from the same
     * mean. Mixing windows is what produced the "E2E 227 ms but the stages sum
     * to 257 ms" screenshot: E2E was a 20-frame average while pre/infer/post
     * were the last frame only, which reads as an arithmetic error.
     */
    private val window = Window(WINDOW)

    private var framesProcessed = 0

    /** Stream size of the frames being processed; goes into the session record. */
    private var lastStreamW = 0
    private var lastStreamH = 0

    /** Non-null while a live measurement session is recording. Written only on
     *  [analysisExecutor]; the UI reads it for the button label and the status
     *  strip. */
    @Volatile
    private var session: LiveSession? = null

    /** Deployment details captured when the session starts, so the record can
     *  never describe a model/backend that was selected after the run. */
    @Volatile
    private var sessionCtx: LiveSession.Context? = null

    /** Wall-clock id used in the session's filenames. */
    private var sessionStamp = 0L

    /**
     * Set when a session ends, cleared when the next one starts or the engine
     * changes. While it is set the timing block shows the COMPLETED session's
     * figures instead of the rolling ones.
     *
     * Without this the screen and the record disagree: the rolling window keeps
     * moving after the run (the frame counter had already passed the session's
     * own count by the time a screenshot was taken), so a figure read off the
     * screen would not be the figure in the JSON. The live overlay, legend and
     * detection count stay live -- only the measurement block is held.
     */
    @Volatile
    private var frozen: LiveSession.Summary? = null

    /**
     * Screenshot plumbing. The frame saved into the PNG is the one the MODEL
     * processed, taken from the analyzer -- not the preview surface.
     *
     * PreviewView.getBitmap() was tried first and returns null in this
     * configuration, which produced a screenshot with a black rectangle where
     * the camera image belonged, and no error. The analyzer frame is both
     * reliable and the more honest artefact: it is literally the image the
     * network saw, at the same aspect the overlay is drawn against.
     */
    @Volatile
    private var captureRequest = false

    @Volatile
    private var capturedFrame: Bitmap? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            binding.permissionHint.visibility = View.GONE
            startCamera()
        } else {
            binding.permissionHint.visibility = View.VISIBLE
            binding.status.text = getString(R.string.perm_rationale)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()
        binding.preview.scaleType = PreviewView.ScaleType.FIT_CENTER

        setupControls()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            binding.permissionHint.visibility = View.VISIBLE
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ------------------------------------------------------------------ UI ---

    private fun setupControls() {
        binding.seekConf.setOnSeekBarChangeListener(simpleListener { p ->
            val conf = p / 100f
            params = params.copy(confThreshold = conf)
            binding.labelConf.text = getString(R.string.label_conf_value, conf)
        })
        binding.labelConf.text = getString(R.string.label_conf_value, params.confThreshold)

        binding.seekIou.setOnSeekBarChangeListener(simpleListener { p ->
            val iou = p / 100f
            params = params.copy(iouThreshold = iou)
            binding.labelIou.text = getString(R.string.label_iou_value, iou)
        })
        binding.labelIou.text = getString(R.string.label_iou_value, params.iouThreshold)

        binding.spinModel.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            ModelRegistry.MODELS.map { it.displayName }
        )
        binding.spinModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val next = ModelRegistry.MODELS[pos]
                if (next.onnxAsset != modelSpec.onnxAsset) {
                    modelSpec = next
                    rebuildEngine()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        binding.spinEngine.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Backend.entries.map { it.label }
        )
        binding.spinEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressSpinner) return
                val next = Backend.entries[pos]
                if (next != backend) {
                    backend = next
                    rebuildEngine()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        binding.btnBench.setOnClickListener {
            startActivity(Intent(this, BenchActivity::class.java))
        }
        // Starting and stopping both have to touch the analyzer thread's state,
        // so the stop is queued rather than run on the UI thread.
        binding.btnSession.setOnClickListener {
            if (session == null) startSession()
            else analysisExecutor.execute { finishSession("stopped by user") }
        }
        binding.btnShot.setOnClickListener { saveScreenshot() }
        binding.btnSwitch.setOnClickListener {
            useFrontCamera = !useFrontCamera
            startCamera()
        }
    }

    private inline fun simpleListener(
        crossinline onProgress: (Int) -> Unit
    ) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) =
            onProgress(progress)

        override fun onStartTrackingTouch(sb: SeekBar?) = Unit
        override fun onStopTrackingTouch(sb: SeekBar?) = Unit
    }

    // -------------------------------------------------------------- camera ---

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()
            this.cameraProvider = cameraProvider

            // One aspect ratio for BOTH streams: the overlay maps analysis
            // coordinates onto a view that is showing the preview stream, so if
            // the two are framed differently no mapping can be correct.
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(selector)
                .build()
            // setSurfaceProvider is overloaded (one variant takes an Executor as
            // well), and Kotlin only synthesises a property when the name is
            // unambiguous -- so call the method explicitly.
            preview.setSurfaceProvider(binding.preview.surfaceProvider)

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyse(proxy) }

            val lens = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, lens, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                binding.status.text = "Camera bind failed: ${e.message}"
                return@addListener
            }

            framesSeen = 0
            framesProcessed = 0
            window.clear()
            rebuildEngine()
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------ inference ---

    /** Loads the selected model on the selected provider; falls back to CPU if
     *  the provider cannot be set up.
     *
     *  On fallback the spinner is moved back to CPU behind [suppressSpinner].
     *  Without that guard, setSelection fires onItemSelected, which sees
     *  provider != CPU and calls straight back into rebuildEngine -- a loop. */
    private fun rebuildEngine() {
        analysisExecutor.execute {
            // An engine cannot change underneath a running session: the recorded
            // backend has to be the one that produced the frames.
            if (session != null) finishSession("engine changed mid-session")
            // and a held result from the previous backend must not sit under a
            // label for the new one
            frozen = null
            engine?.let { runCatching { it.close() } }
            engine = null
            runOnUiThread { binding.overlay.clear() }

            val requested = backend
            val built = runCatching {
                EngineFactory.create(this, modelSpec, requested)
            }

            val resolved = built.getOrElse { err ->
                Log.w(TAG, "engine build failed for $requested: ${err.message}")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "${requested.label} unavailable — falling back to ORT CPU: ${err.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (requested == Backend.ORT_CPU) throw err
                val fallback = EngineFactory.create(this, modelSpec, Backend.ORT_CPU)
                backend = Backend.ORT_CPU
                runOnUiThread {
                    suppressSpinner = true
                    binding.spinEngine.setSelection(Backend.ORT_CPU.ordinal)
                    suppressSpinner = false
                }
                fallback
            }

            engine = resolved
        }
    }

    private fun analyse(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        try {
            val current = engine ?: return

            // The measured window: YUV->RGB conversion, letterbox, inference,
            // decode/NMS. Everything else in this method is deliberately outside
            // it, so end-to-end is a real measurement rather than pre+infer+post
            // with the conversion quietly missing from all three.
            val t0 = System.nanoTime()
            val bitmap = YuvToRgb.convert(proxy, proxy.imageInfo.rotationDegrees)
            val result = current.detect(bitmap, params)
            val wallMs = (System.nanoTime() - t0) / 1e6

            // captured as locals: YuvToRgb reuses its bitmap across frames
            val bmpW = bitmap.width
            val bmpH = bitmap.height
            val rot = proxy.imageInfo.rotationDegrees
            val imgW = proxy.width
            val imgH = proxy.height
            lastStreamW = bmpW
            lastStreamH = bmpH

            framesProcessed++
            session?.let { s ->
                val f = LiveSession.Frame(
                    index = s.processedFrames,
                    tMs = s.elapsedMs(SystemClock.elapsedRealtime()),
                    wallMs = wallMs,
                    preMs = result.preMs,
                    inferMs = result.inferMs,
                    postMs = result.postMs,
                    detections = result.detections.size,
                )
                val shot = s.onFrame(f)
                if (shot >= 0) {
                    // The copy is outside the measured window above and the PNG
                    // encode happens on the UI thread: neither may land in a
                    // frame's cost, or the sample stops being the one measured.
                    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val name = s.shotName(shot, sessionStamp)
                    Log.i(TAG, "session shot $shot -> $name at t=${f.tMs}ms")
                    runOnUiThread { composeScreenshot(copy, quiet = true, name = name) }
                }
                if (s.isFinished(f)) finishSession("window elapsed")
            }

            // YuvToRgb reuses its buffer, so the screenshot needs a copy -- made
            // only when one has been asked for.
            if (captureRequest) {
                captureRequest = false
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                capturedFrame = copy
                Log.i(TAG, "capture: frame copied ${copy?.width}x${copy?.height}")
            }

            framesSeen++
            if (framesSeen <= DIAG_FRAMES) {
                val t = result.transform
                // The distribution matters, not the top-scoring box: logging only
                // `first` once sent me chasing a phantom for a whole build cycle.
                val ys = result.detections.map { it.y1 }.sorted()
                val xs = result.detections.map { it.x1 }.sorted()
                Log.i(
                    TAG,
                    "frame#$framesSeen stream=${imgW}x$imgH rot=$rot " +
                        "bitmap=${bmpW}x$bmpH " +
                        "lbScale=%.4f pad=(%.1f,%.1f) ".format(t.scale, t.padX, t.padY) +
                        "stage=${binding.stage.width}x${binding.stage.height} " +
                        "dets=${result.detections.size} sat=${result.saturated} " +
                        "y1[min=%.0f med=%.0f max=%.0f] ".format(
                            ys.firstOrNull() ?: -1f, ys.getOrNull(ys.size / 2) ?: -1f,
                            ys.lastOrNull() ?: -1f
                        ) +
                        "x1[min=%.0f med=%.0f max=%.0f]".format(
                            xs.firstOrNull() ?: -1f, xs.getOrNull(xs.size / 2) ?: -1f,
                            xs.lastOrNull() ?: -1f
                        )
                )

                // Dump the exact network input once, so the preprocessing can be
                // inspected rather than deduced.
                if (framesSeen == 1) {
                    runCatching {
                        val lb = (current as? com.lychee.fsdh.infer.KotlinSideEngine)
                            ?.lastLetterboxedCopy()
                        if (lb != null) {
                            // external files dir, not filesDir: the private dir
                            // is unreadable over adb on a non-rooted device
                            val f = java.io.File(getExternalFilesDir(null), "debug_letterbox.png")
                            f.outputStream().use { lb.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            lb.recycle()
                            Log.i(TAG, "saved network input -> ${f.absolutePath}")
                        }
                    }.onFailure { Log.w(TAG, "dump failed: ${it.message}") }
                }
            }

            runOnUiThread {
                binding.overlay.setDetections(result.detections, bmpW, bmpH)
                updateReadout(result, wallMs, bmpW, bmpH)
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyse failed", e)
        } finally {
            busy.set(false)
            proxy.close()
        }
    }

    private fun updateReadout(
        result: com.lychee.fsdh.infer.InferenceResult,
        wallMs: Double,
        bmpW: Int,
        bmpH: Int,
    ) {
        window.add(wallMs, result.preMs, result.inferMs, result.postMs)

        // One window, four means, and FPS derived from that same mean. The unit
        // lives in the cell label (see strings.xml) so the values stay at full
        // size instead of being autosized down to fit "1234.5 ms" into a
        // quarter of the screen.
        val done = frozen
        if (done != null) {
            binding.mE2e.text = "%.1f".format(done.e2e.mean)
            binding.mInf.text = "%.1f".format(done.infer.mean)
            binding.mPre.text = "%.1f".format(done.pre.mean)
            binding.mPost.text = "%.1f".format(done.post.mean)
            binding.mFps.text = "%.1f".format(done.fps)
            binding.mFrames.text = done.processedFrames.toString()
        } else {
            val e2e = window.meanE2e()
            binding.mE2e.text = "%.1f".format(e2e)
            binding.mInf.text = "%.1f".format(window.meanInfer())
            binding.mPre.text = "%.1f".format(window.meanPre())
            binding.mPost.text = "%.1f".format(window.meanPost())
            binding.mFps.text = "%.1f".format(1000.0 / e2e)
            binding.mFrames.text = framesProcessed.toString()
        }

        var u = 0
        var s = 0
        var r = 0
        for (d in result.detections) when (d.classId) {
            0 -> u++
            1 -> s++
            else -> r++
        }

        // A saturated count is a floor, not the truth; say so rather than
        // printing "300" as if it were the number of lychees on screen.
        binding.mDet.text = if (result.saturated) "${result.detections.size}+" else "${result.detections.size}"

        binding.legendUnripe.text = getString(R.string.legend_unripe, u)
        binding.legendSemi.text = getString(R.string.legend_semi, s)
        binding.legendRipe.text = getString(R.string.legend_ripe, r)

        // engine.name already reads "<model> · <provider>", so printing
        // modelSpec.displayName here as well duplicated it. The threshold is on
        // the slider label right below, and the rotation is visible in the
        // image itself -- on a 393dp-wide phone even the remaining fields only
        // just fit on one line, and a wrapped line pushes the stage down.
        val rec = session
        binding.status.text = when {
            rec != null -> getString(
                R.string.status_recording,
                rec.elapsedMs(SystemClock.elapsedRealtime()) / 1000.0,
                rec.durationMs / 1000.0,
                rec.processedFrames,
            )
            // Held after the run rather than written once: the next frame's
            // readout would otherwise overwrite it ~250 ms later, which is how
            // the first build managed to show the result to nobody.
            done != null -> headline(done)
            else -> "%s · %dx%d".format(engine?.name ?: "—", bmpW, bmpH)
        }
    }

    /** One-line session result for the status strip. Kept under ~46 characters,
     *  which is what 11sp fits on a 393dp screen. */
    private fun headline(s: LiveSession.Summary): String =
        "%.0fs %df · E2E %.1f · P95 %.1f · %.1f FPS".format(
            s.durationActualMs / 1000.0, s.processedFrames, s.e2e.mean, s.e2e.p95, s.fps,
        )

    // ------------------------------------------------------------- session ---

    /**
     * Starts the continuous measurement window (60 s over one target cluster).
     *
     * The display window is cleared so the rolling averages describe this run
     * rather than this run mixed with whatever was on screen before it.
     */
    private fun startSession() {
        val started = SystemClock.elapsedRealtime()
        sessionStamp = System.currentTimeMillis()
        val s = LiveSession(startedAtMs = started)

        window.clear()
        frozen = null
        framesProcessed = 0
        binding.mFrames.text = "0"
        binding.btnSession.text = getString(R.string.btn_session_stop)
        binding.status.text = getString(R.string.status_starting_rec)

        analysisExecutor.execute {
            if (engine == null) {
                runOnUiThread {
                    binding.btnSession.text = getString(R.string.btn_session)
                    Toast.makeText(this, "Engine is not ready yet", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }
            // Captured now, so the record can never end up describing a model or
            // backend that was selected after the run.
            sessionCtx = LiveSession.Context(
                engine = engine?.name ?: "—",
                model = modelSpec.displayName,
                backend = backend.label,
                // Straight off the enum, next to the construction calls in
                // EngineFactory, so the recorded value cannot drift from what
                // the engines are actually built with -- and so the slim build,
                // which has no TFLite entries, still compiles.
                threads = backend.threadsNote,
                // stream size is only known once a frame has arrived
                streamWidth = 0,
                streamHeight = 0,
                networkSize = engine?.inputSize ?: 640,
                device = Benchmark.deviceInfo(),
            )
            session = s
            Log.i(TAG, "session start: ${engine?.name} stamp=$sessionStamp")
        }
    }

    /** Closes the session, writes its record. Must run on the analyzer thread. */
    private fun finishSession(reason: String) {
        val s = session ?: return
        session = null

        if (s.measuredFrames < 2) {
            Log.w(TAG, "session ended after ${s.processedFrames} frames -- too short")
            runOnUiThread {
                binding.btnSession.text = getString(R.string.btn_session)
                Toast.makeText(
                    this,
                    "Session too short (${s.processedFrames} frames) -- nothing recorded",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }

        val summary = s.summary()
        val captured = sessionCtx
        val ctx = (captured ?: LiveSession.Context(
            engine = "—", model = "—", backend = "—", threads = "—",
            streamWidth = 0, streamHeight = 0, networkSize = 0,
            device = Benchmark.deviceInfo(),
        )).copy(streamWidth = lastStreamW, streamHeight = lastStreamH)

        val out = File(getExternalFilesDir(null), "live_session_$sessionStamp.json")
        val write = runCatching { out.writeText(s.toJson(ctx, summary, sessionStamp, reason)) }

        frozen = summary
        Log.i(TAG, "session end ($reason): ${s.headline(summary)}")
        runOnUiThread {
            binding.btnSession.text = getString(R.string.btn_session)
            // set directly as well as through updateReadout: if the camera stops
            // with the run (onStop), no further frame arrives to render it
            binding.status.text = headline(summary)
            Toast.makeText(
                this,
                if (write.isSuccess) "Saved: ${out.absolutePath}"
                else "Write failed: ${write.exceptionOrNull()?.message}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // ------------------------------------------------------------ capture ---

    /**
     * Saves one PNG containing the camera frame, the detection overlay and the
     * live readout -- the figure the deployment section needs for the
     * application-chain demonstration.
     *
     * View.draw() cannot capture a SurfaceView: the preview lives on its own
     * surface composited by SurfaceFlinger, so a plain hierarchy draw leaves a
     * black rectangle where the camera image should be. PreviewView.getBitmap()
     * supplies that frame; the trick is ORDER. The frame is painted into the
     * bitmap first, the stage background and the PreviewView are hidden for the
     * duration so the hierarchy draw cannot paint over it, and the overlay then
     * lands on top exactly as it does on screen.
     */
    private fun saveScreenshot() {
        val root = binding.root
        if (root.width <= 0 || root.height <= 0) return

        // Ask the analyzer for the next frame. It arrives at camera cadence
        // (~160 ms here), so wait off the main thread and compose once it does;
        // blocking the UI thread would stall the very pipeline being captured.
        capturedFrame = null
        captureRequest = true
        Toast.makeText(this, "Capturing frame…", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "capture: requested")

        Thread {
            val deadline = System.currentTimeMillis() + 3000
            while (capturedFrame == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val frame = capturedFrame
            capturedFrame = null
            Log.i(
                TAG,
                "capture: wait done frame=${frame?.let { "${it.width}x${it.height}" } ?: "NULL"} " +
                    "elapsed=${3000 - (deadline - System.currentTimeMillis())}ms"
            )
            runOnUiThread { composeScreenshot(frame) }
        }.start()
    }

    /**
     * Order matters, and getting it wrong is silent.
     *
     * The first attempt painted the camera frame and THEN called root.draw() --
     * which begins by filling its own background, so the frame was erased and
     * the result looked exactly like a failed capture. The hierarchy now goes
     * down first, with the preview hidden and the stage background cleared, and
     * the frame is painted into the vacated stage area afterwards. The overlay
     * is then redrawn LAST, because the hierarchy pass already drew it once --
     * underneath the frame that has just covered it.
     */
    private fun composeScreenshot(frame: Bitmap?, quiet: Boolean = false, name: String? = null) {
        val root = binding.root
        val out = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val stageBg = binding.stage.background
        val previewVisibility = binding.preview.visibility
        binding.stage.background = null
        binding.preview.visibility = View.INVISIBLE
        try {
            root.draw(canvas)
        } finally {
            binding.preview.visibility = previewVisibility
            binding.stage.background = stageBg
        }

        var frameDrawn = false
        frame?.let {
            val stageW = binding.stage.width
            val stageH = binding.stage.height
            val stageLoc = IntArray(2).also { loc -> binding.stage.getLocationInWindow(loc) }
            val rootLoc = IntArray(2).also { loc -> root.getLocationInWindow(loc) }
            val dx = (stageLoc[0] - rootLoc[0]).toFloat()
            val dy = (stageLoc[1] - rootLoc[1]).toFloat()

            // same contain mapping as OverlayView: uniform scale, centred
            val s = min(stageW.toFloat() / frame.width, stageH.toFloat() / frame.height)
            val dw = frame.width * s
            val dh = frame.height * s
            val left = (stageW - dw) / 2f
            val top = (stageH - dh) / 2f

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.save()
            canvas.translate(dx, dy)
            canvas.clipRect(0f, 0f, stageW.toFloat(), stageH.toFloat())
            canvas.drawBitmap(frame, null, RectF(left, top, left + dw, top + dh), paint)
            // boxes back on top of the image that just covered them
            binding.overlay.draw(canvas)
            canvas.restore()
            frameDrawn = true
        }

        val f = File(
            getExternalFilesDir(null),
            name ?: "fsdh_capture_${System.currentTimeMillis()}.png",
        )
        runCatching {
            f.outputStream().use { sink -> out.compress(Bitmap.CompressFormat.PNG, 100, sink) }
        }.onSuccess {
            // Session shots fire 12 times per run: a toast each would cover the
            // preview the figure is supposed to show.
            if (quiet) {
                Log.i(TAG, "shot saved -> ${f.name} (${f.length() / 1024} KB)")
            } else {
                Toast.makeText(
                    this,
                    if (frameDrawn) "Saved: ${f.absolutePath}"
                    else "Saved, but no camera frame was available (image is black): ${f.absolutePath}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            if (!frameDrawn) Log.w(TAG, "screenshot saved without a camera frame")
        }.onFailure {
            if (quiet) Log.w(TAG, "shot failed: ${it.message}")
            else Toast.makeText(this, "Screenshot failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
        // ~10 MB per ARGB_8888 frame; a 12-shot session would otherwise leave
        // all twelve to the collector.
        frame?.recycle()
        out.recycle()
    }

    /**
     * The analyzer stops with the lifecycle, so a session left running would
     * never reach the end of its window. Close it out with what was recorded
     * rather than losing the run.
     */
    override fun onStop() {
        super.onStop()
        if (session != null) analysisExecutor.execute { finishSession("activity stopped") }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.let { runCatching { it.close() } }
        engine = null
        analysisExecutor.shutdown()
    }

    /**
     * Fixed-size rolling window holding all four metrics together, so they can
     * only ever be averaged over the same set of frames. Four parallel arrays
     * would let a future edit update one and not another, which is exactly the
     * mixed-window bug this replaced.
     */
    private class Window(private val n: Int) {
        private val e2e = DoubleArray(n)
        private val pre = DoubleArray(n)
        private val infer = DoubleArray(n)
        private val post = DoubleArray(n)
        private var idx = 0
        private var fill = 0

        fun add(e2eMs: Double, preMs: Double, inferMs: Double, postMs: Double) {
            e2e[idx] = e2eMs
            pre[idx] = preMs
            infer[idx] = inferMs
            post[idx] = postMs
            idx = (idx + 1) % n
            if (fill < n) fill++
        }

        fun meanE2e(): Double = mean(e2e)
        fun meanPre(): Double = mean(pre)
        fun meanInfer(): Double = mean(infer)
        fun meanPost(): Double = mean(post)

        fun clear() {
            idx = 0
            fill = 0
        }

        private fun mean(a: DoubleArray): Double {
            if (fill == 0) return Double.NaN
            var sum = 0.0
            for (i in 0 until fill) sum += a[i]
            return sum / fill
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val WINDOW = 20
        private const val DIAG_FRAMES = 3
    }
}
