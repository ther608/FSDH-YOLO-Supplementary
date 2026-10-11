package com.lychee.fsdh.infer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ultralytics-compatible letterbox + tensor marshalling.
 *
 * This mirrors `ultralytics/data/augment.py::LetterBox` for the deployment case
 * (fixed square input, no centre-vs-corner ambiguity):
 *
 *     r      = min(dst / h, dst / w)
 *     resize = (round(w*r), round(h*r))          <- rounded to int, not floored
 *     pad    = (dst - resize) / 2
 *     top    = round(padY - 0.1), bottom = round(padY + 0.1)   <- the ±0.1 keeps
 *     left   = round(padX - 0.1), right  = round(padX + 0.1)      an odd leftover
 *                                                                 on the far side
 *     fill   = 114 (grey)
 *
 * Getting the rounding wrong shifts every box by up to half a pixel, which is
 * exactly the kind of silent error the deployment numbers in the paper must not
 * carry, so the arithmetic below is deliberately spelled out step by step.
 *
 * The tensor is RGB, CHW, float32, scaled to [0,1] -- matching
 * `BasePredictor.preprocess` (which flips BGR->RGB) followed by `/255`.
 */
class Preprocessor(
    val inputSize: Int = 640,
    /**
     * Channel layout the target runtime wants.
     *
     * ONNX Runtime takes the graph as exported: NCHW, planar. onnx2tf re-emits
     * the same network with an NHWC input ([1,640,640,3]), so TFLite needs the
     * channels interleaved. Feeding planar data to an NHWC graph does not
     * error -- it silently runs on scrambled channels and returns garbage
     * boxes, which is exactly the failure mode the numerical check in
     * scripts/verify_tflite.py exists to catch.
     */
    private val interleaved: Boolean = false,
) {

    private val padValue = Color.rgb(114, 114, 114)

    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isAntiAlias = false
        isDither = false
    }

    /** Scratch bitmap + pixel staging, allocated once and reused every frame. */
    private var canvasBitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var pixelBuf: IntArray = IntArray(0)
    private var floatBuf: FloatArray = FloatArray(0)

    private val tensor: FloatBuffer =
        ByteBuffer.allocateDirect(3 * inputSize * inputSize * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    /** Video frames arrive rotated; this is the upright source for one inference. */
    fun letterbox(src: Bitmap): Pair<Bitmap, LetterboxTransform> {
        val srcW = src.width
        val srcH = src.height
        val r = min(inputSize.toFloat() / srcH, inputSize.toFloat() / srcW)

        val newW = (srcW * r).roundToInt().coerceAtLeast(1)
        val newH = (srcH * r).roundToInt().coerceAtLeast(1)

        val padX = (inputSize - newW) / 2f
        val padY = (inputSize - newH) / 2f
        val left = (padX - 0.1f).roundToInt()
        val top = (padY - 0.1f).roundToInt()

        val dst = obtainCanvas()
        canvas!!.drawColor(padValue)
        canvas!!.drawBitmap(
            src,
            Rect(0, 0, srcW, srcH),
            Rect(left, top, left + newW, top + newH),
            scalePaint
        )

        return dst to LetterboxTransform(
            scale = r,
            // the pad actually applied (integer) rather than the unrounded
            // nominal value -- this is what the pixels really got
            padX = left.toFloat(),
            padY = top.toFloat(),
            srcWidth = srcW,
            srcHeight = srcH,
        )
    }

    /**
     * Packs the letterboxed bitmap into the reusable CHW float tensor.
     *
     * The channel values are staged in a plain FloatArray and copied to the
     * direct FloatBuffer in ONE bulk put. Writing element-by-element straight
     * into a direct buffer costs a bounds-checked put per value (3 x 409600 of
     * them per frame); array writes are far cheaper and the single bulk copy
     * goes through native memcpy.
     */
    fun toTensor(bitmap: Bitmap): FloatBuffer {
        val n = inputSize * inputSize
        if (pixelBuf.size != n) pixelBuf = IntArray(n)
        if (floatBuf.size != 3 * n) floatBuf = FloatArray(3 * n)

        bitmap.getPixels(pixelBuf, 0, inputSize, 0, 0, inputSize, inputSize)

        // Android bitmaps are already RGB; only the layout differs
        val inv = 1f / 255f
        if (interleaved) {
            // NHWC: pixel by pixel, R G B
            var o = 0
            for (i in 0 until n) {
                val p = pixelBuf[i]
                floatBuf[o++] = ((p shr 16) and 0xFF) * inv
                floatBuf[o++] = ((p shr 8) and 0xFF) * inv
                floatBuf[o++] = (p and 0xFF) * inv
            }
        } else {
            // NCHW: R plane, then G, then B
            for (i in 0 until n) {
                val p = pixelBuf[i]
                floatBuf[i] = ((p shr 16) and 0xFF) * inv
                floatBuf[n + i] = ((p shr 8) and 0xFF) * inv
                floatBuf[2 * n + i] = (p and 0xFF) * inv
            }
        }

        tensor.rewind()
        tensor.put(floatBuf)
        tensor.rewind()
        return tensor
    }

    /**
     * Debug helper: a private copy of the most recent letterboxed frame -- the
     * exact pixels the network was fed. Saved to storage on the first frame so
     * the preprocessing can be inspected directly instead of inferred from the
     * boxes that come back.
     */
    fun lastLetterboxedCopy(): Bitmap? =
        canvasBitmap?.copy(Bitmap.Config.ARGB_8888, false)

    private fun obtainCanvas(): Bitmap {
        var bmp = canvasBitmap
        if (bmp == null || bmp.width != inputSize || bmp.height != inputSize) {
            bmp = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            canvasBitmap = bmp
            canvas = Canvas(bmp)
        }
        return bmp
    }
}
