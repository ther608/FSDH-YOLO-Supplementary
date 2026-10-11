package com.lychee.fsdh.infer

import kotlin.math.max
import kotlin.math.min

/**
 * Decodes the raw `(1, 7, 8400)` tensor and applies NMS.
 *
 * Row layout (verified against the exported graph, see scripts/op_inventory.py):
 *   rows 0..3  -> cx, cy, w, h   ALREADY in input-pixel space (0..640), because
 *                                 the Detect head multiplies DFL distances by
 *                                 the anchor points before export
 *   rows 4..6  -> class scores   ALREADY sigmoid-ed in the exported graph
 *
 * So there is no anchor decoding and no sigmoid to apply here -- doing either
 * would be double-counting. The only transform left is undoing the letterbox.
 *
 * NMS is class-aware, matching ultralytics' default `agnostic=False`. Boxes of
 * different classes never suppress each other.
 */
class Detector {

    private companion object {
        const val TAG = "Detector"
    }

    /** Matches ultralytics' `max_nms`, dialled down: on a phone the pathological
     *  case (thousands of candidates at conf=0.05) would cost O(n^2) IoU tests.
     *  3000 is far above any real frame for this task. */
    private val maxNms = 3000

    fun decode(
        raw: FloatArray,
        numAnchors: Int,
        numClasses: Int,
        params: DetectParams,
        transform: LetterboxTransform,
    ): List<Detection> {
        val s = transform.scale
        val padX = transform.padX
        val padY = transform.padY
        val maxX = (transform.srcWidth - 1).toFloat()
        val maxY = (transform.srcHeight - 1).toFloat()

        // --- pass 1: collect candidates above the confidence threshold ---------
        var idx = IntArray(256)
        var sc = FloatArray(256)
        var cl = IntArray(256)
        var n = 0

        for (i in 0 until numAnchors) {
            var best = -1
            var bestScore = params.confThreshold
            for (c in 0 until numClasses) {
                val v = raw[(4 + c) * numAnchors + i]
                if (v > bestScore) {
                    bestScore = v
                    best = c
                }
            }
            if (best < 0) continue

            if (n == idx.size) {
                idx = idx.copyOf(n * 2); sc = sc.copyOf(n * 2); cl = cl.copyOf(n * 2)
            }
            idx[n] = i; sc[n] = bestScore; cl[n] = best
            n++
        }
        if (n == 0) return emptyList()

        // --- pass 2: keep the top maxNms by score -----------------------------
        val order = (0 until n).sortedByDescending { sc[it] }.take(maxNms)

        // --- pass 3: greedy class-aware NMS, decoding boxes lazily -------------
        //
        // `order` holds indices into the CANDIDATE arrays, not anchor indices,
        // so every read of `raw` has to go through idx[]. Reading raw[order[oi]]
        // directly is a subtle and nasty bug: small candidate indices are valid
        // anchor indices too, and the first few hundred anchors belong to the
        // TOP ROWS of the P3 grid -- so every box gets plausible-but-wrong
        // coordinates pinned near the top edge, while score and class (which do
        // use the candidate index) stay correct. That is exactly the failure
        // observed on device.
        val kept = ArrayList<Detection>(min(order.size, 64))
        val suppressed = BooleanArray(order.size)

        for (oi in order.indices) {
            if (suppressed[oi]) continue
            val ci = order[oi]          // candidate index
            val ai = idx[ci]            // anchor index into `raw`

            // undo letterbox, then clamp to the frame
            var ax1 = cx1(raw, ai, numAnchors, padX, s)
            var ay1 = cy1(raw, ai, numAnchors, padY, s)
            var ax2 = cx2(raw, ai, numAnchors, padX, s)
            var ay2 = cy2(raw, ai, numAnchors, padY, s)
            ax1 = ax1.coerceIn(0f, maxX); ay1 = ay1.coerceIn(0f, maxY)
            ax2 = ax2.coerceIn(0f, maxX); ay2 = ay2.coerceIn(0f, maxY)

            if (ax2 <= ax1 || ay2 <= ay1) continue

            kept.add(Detection(ax1, ay1, ax2, ay2, sc[ci], cl[ci]))

            if (kept.size >= params.maxDetections) break

            val areaA = (ax2 - ax1) * (ay2 - ay1)

            for (oj in (oi + 1) until order.size) {
                if (suppressed[oj]) continue
                val cj = order[oj]
                if (params.classAwareNms && cl[cj] != cl[ci]) continue

                val aj = idx[cj]
                val bxx1 = cx1(raw, aj, numAnchors, padX, s)
                val byy1 = cy1(raw, aj, numAnchors, padY, s)
                val bxx2 = cx2(raw, aj, numAnchors, padX, s)
                val byy2 = cy2(raw, aj, numAnchors, padY, s)

                val ix1 = max(ax1, bxx1); val iy1 = max(ay1, byy1)
                val ix2 = min(ax2, bxx2); val iy2 = min(ay2, byy2)
                val iw = ix2 - ix1; val ih = iy2 - iy1
                if (iw <= 0f || ih <= 0f) continue

                val inter = iw * ih
                val areaB = (bxx2 - bxx1) * (byy2 - byy1)
                val iou = inter / (areaA + areaB - inter)
                if (iou > params.iouThreshold) suppressed[oj] = true
            }
        }

        // Canary for the class of bug described below: if a busy frame keeps
        // essentially every candidate, suppression is not running.
        if (n > 50 && kept.size > n * 0.9) {
            android.util.Log.w(
                TAG,
                "NMS suppressed almost nothing ($n candidates -> ${kept.size} kept); " +
                    "check the IoU box reconstruction and iouThreshold=" +
                    params.iouThreshold
            )
        }

        return kept
    }

    // ------------------------------------------------------------------------
    // Box reconstruction, in one place.
    //
    // These used to be written inline in the suppression loop with the raw
    // values named bx1/by1/bx2/by2 -- which they are NOT. Rows 0..3 hold
    // cx, cy, w, h, so `bx1 - (bx2 - bx1) * 0.5f` computed 1.5*cx - w/2 instead
    // of cx - w/2 and produced an INVERTED box (x1 > x2). The IoU then came out
    // non-positive and the `continue` skipped suppression entirely, so NMS
    // silently did nothing and every candidate above the confidence threshold
    // survived. Naming the reads after what they actually are is the fix.
    // ------------------------------------------------------------------------
    private fun cx1(raw: FloatArray, a: Int, n: Int, pad: Float, s: Float) =
        ((raw[a] - raw[2 * n + a] * 0.5f) - pad) / s

    private fun cy1(raw: FloatArray, a: Int, n: Int, pad: Float, s: Float) =
        ((raw[n + a] - raw[3 * n + a] * 0.5f) - pad) / s

    private fun cx2(raw: FloatArray, a: Int, n: Int, pad: Float, s: Float) =
        ((raw[a] + raw[2 * n + a] * 0.5f) - pad) / s

    private fun cy2(raw: FloatArray, a: Int, n: Int, pad: Float, s: Float) =
        ((raw[n + a] + raw[3 * n + a] * 0.5f) - pad) / s
}
