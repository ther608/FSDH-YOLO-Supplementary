package com.lychee.fsdh.infer

/** One detected lychee, in the coordinate space of the upright source bitmap. */
data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    val classId: Int,
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
}

/**
 * Result of the letterbox step: how to map network-space boxes back onto the
 * source bitmap.
 *
 * x_src = (x_net - padX) / scale
 */
data class LetterboxTransform(
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val srcWidth: Int,
    val srcHeight: Int,
)

/** Per-inference timing breakdown. All values in milliseconds. */
data class InferenceResult(
    val detections: List<Detection>,
    val preMs: Double,
    val inferMs: Double,
    val postMs: Double,
    /** The letterbox actually applied. Carried out so diagnostics can print the
     *  exact geometry a frame went through instead of re-deriving it. */
    val transform: LetterboxTransform,
    /** Frames whose candidate count hit the maxDetections cap. A non-zero value
     *  means the on-screen count is a floor, not the true detection count. */
    val saturated: Boolean = false,
) {
    val totalMs: Double get() = preMs + inferMs + postMs
}

/** Thresholds, surfaced in the UI so they can be tuned per deployment. */
data class DetectParams(
    val confThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f,
    val maxDetections: Int = 300,
    val classAwareNms: Boolean = true,
)

/** The three maturity stages, in the class-index order the model was trained on. */
object Classes {
    val NAMES = arrayOf("unripe", "semi-ripe", "ripe")

    /** Single-letter labels; matches the web app's compact label mode. */
    val SHORT = arrayOf("U", "S", "R")

    const val COUNT = 3
}
