package com.lychee.fsdh.infer

import android.graphics.Bitmap
import java.io.Closeable

/**
 * Everything the app needs from a runtime.
 *
 * The point of this interface is that the three candidate Android routes
 * (ONNX Runtime / TFLite+LiteRT / NCNN) differ ONLY here. Camera plumbing,
 * letterboxing, decoding, NMS, overlay drawing and the benchmark harness are
 * shared, so swapping runtimes is an A/B measurement rather than a rewrite.
 */
interface InferenceEngine : Closeable {

    /** Human-readable label shown in the UI and written into benchmark output. */
    val name: String

    /** Square network input side, e.g. 640. */
    val inputSize: Int

    fun detect(bitmap: Bitmap, params: DetectParams): InferenceResult

    /** Runs one throwaway inference so later timings exclude graph allocation. */
    fun warmUp(bitmap: Bitmap, params: DetectParams) {
        detect(bitmap, params)
    }
}

/**
 * Shared pipeline for engines whose preprocessing and decoding both live in
 * Kotlin: letterbox -> tensor -> runtime -> decode -> NMS.
 *
 * A runtime that does its own preprocessing (NCNN's C++ path, say) simply
 * implements [InferenceEngine] directly instead of extending this.
 */
abstract class KotlinSideEngine(
    final override val inputSize: Int = 640,
    /** NCHW for ONNX Runtime (as exported), NHWC for the TFLite re-emission. */
    interleavedInput: Boolean = false,
) : InferenceEngine {

    protected val preprocessor = Preprocessor(inputSize, interleavedInput)
    protected val detector = Detector()

    final override fun detect(bitmap: Bitmap, params: DetectParams): InferenceResult {
        val t0 = System.nanoTime()
        val (letterboxed, transform) = preprocessor.letterbox(bitmap)
        val tensor = preprocessor.toTensor(letterboxed)
        val t1 = System.nanoTime()

        val raw = runModel(tensor)
        val t2 = System.nanoTime()

        val decoded = detector.decode(raw, numAnchors(raw.size), Classes.COUNT, params, transform)
        val t3 = System.nanoTime()

        return InferenceResult(
            detections = decoded,
            preMs = (t1 - t0) / 1e6,
            inferMs = (t2 - t1) / 1e6,
            postMs = (t3 - t2) / 1e6,
            transform = transform,
            saturated = decoded.size >= params.maxDetections,
        )
    }

    /** Runs the graph and returns the raw (numChannels x numAnchors) tensor, row-major. */
    protected abstract fun runModel(tensor: java.nio.FloatBuffer): FloatArray

    /** Debug helper: the letterboxed frame the network was just fed. */
    fun lastLetterboxedCopy(): android.graphics.Bitmap? = preprocessor.lastLetterboxedCopy()

    /**
     * The head emits 4 box channels + one channel per class, so
     * numAnchors = totalValues / (4 + numClasses).
     */
    private fun numAnchors(totalValues: Int): Int = totalValues / (4 + Classes.COUNT)
}
