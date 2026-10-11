package com.lychee.fsdh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.lychee.fsdh.infer.Classes
import com.lychee.fsdh.infer.Detection
import kotlin.math.min

/**
 * Draws detections over the preview.
 *
 * The mapping is CONTAIN (fit the whole frame inside the view, letterboxing the
 * remainder), matching the web app's `object-fit: contain` and the
 * PreviewView.ScaleType.FIT_CENTER set in MainActivity.
 *
 * An earlier version used FILL_CENTER here. That is only correct if the analysis
 * stream and the preview stream have identical aspect ratios -- they did not on
 * the test device, so the boxes were drawn with the wrong scale and offset. Both
 * sides are now pinned to the same aspect ratio in MainActivity and both use
 * contain, which makes the mapping correct by construction rather than by luck.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var detections: List<Detection> = emptyList()
    private var srcW = 1
    private var srcH = 1

    /** Label style. Short = just the class letter, matching the web app's
     *  "class only (U/S/R)" option; long adds the confidence. */
    var shortLabels: Boolean = false
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    private val density = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        isFakeBoldText = true
    }

    private val rect = RectF()

    fun setDetections(dets: List<Detection>, sourceWidth: Int, sourceHeight: Int) {
        detections = dets
        srcW = if (sourceWidth > 0) sourceWidth else 1
        srcH = if (sourceHeight > 0) sourceHeight else 1
        postInvalidateOnAnimation()
    }

    fun clear() {
        detections = emptyList()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty() || width == 0 || height == 0) return

        val scale = min(width.toFloat() / srcW, height.toFloat() / srcH)
        val offX = (width - srcW * scale) / 2f
        val offY = (height - srcH * scale) / 2f

        val padH = 4f * density
        val padV = 3f * density

        for (d in detections) {
            val color = colorFor(d.classId)
            boxPaint.color = color
            labelBg.color = color

            rect.set(
                d.x1 * scale + offX,
                d.y1 * scale + offY,
                d.x2 * scale + offX,
                d.y2 * scale + offY,
            )
            canvas.drawRect(rect, boxPaint)

            val label = if (shortLabels) {
                Classes.SHORT[d.classId]
            } else {
                "${Classes.SHORT[d.classId]} ${"%.2f".format(d.score)}"
            }

            val tw = textPaint.measureText(label)
            val th = textPaint.textSize
            val boxH = th + padV * 2
            val boxW = tw + padH * 2

            // label sits above the box; flips inside when there is no room
            val ly = if (rect.top - boxH >= 0) rect.top - boxH else rect.top
            val lx = rect.left.coerceAtMost(width - boxW)

            canvas.drawRect(lx, ly, lx + boxW, ly + boxH, labelBg)
            canvas.drawText(label, lx + padH, ly + padV + th * 0.82f, textPaint)
        }
    }

    private fun colorFor(classId: Int): Int = when (classId) {
        0 -> 0xFF22C55E.toInt()   // unripe  (web app --c0)
        1 -> 0xFFF59E0B.toInt()   // semi-ripe (--c1)
        else -> 0xFFEF4444.toInt() // ripe    (--c2)
    }
}
