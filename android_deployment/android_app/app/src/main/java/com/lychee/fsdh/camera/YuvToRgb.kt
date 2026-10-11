package com.lychee.fsdh.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * YUV_420_888 -> ARGB_8888 without the usual detour through NV21 or JPEG.
 *
 * CameraX hands back three planes with independent row/pixel strides; the U and
 * V planes are frequently interleaved with pixelStride 2 even though the format
 * is nominally planar. Reading through the ByteBuffer with the reported strides
 * (rather than assuming 4:2:0 contiguity) is what makes this correct across
 * devices.
 *
 * Conversion is BT.601 limited-range, matching the camera's own JPEG pipeline.
 *
 * Both the read buffer and the rotation destination are allocated once and
 * reused. The earlier version called Bitmap.createBitmap per frame, which at
 * 720x1280 is 3.7 MB of garbage every frame -- ~18 MB/s at 5 FPS, enough to
 * keep the collector busy and show up as jitter in the benchmark.
 */
object YuvToRgb {

    private var pixels = IntArray(0)
    private var readBuffer: Bitmap? = null
    private var rotatedBuffer: Bitmap? = null
    private var rotateCanvas: Canvas? = null

    /**
     * @return a bitmap owned by this object and REUSED on the next call. Callers
     *         must copy it if they need to retain the frame (see BenchActivity).
     */
    fun convert(image: ImageProxy, rotationDegrees: Int): Bitmap {
        val width = image.width
        val height = image.height
        val n = width * height
        if (pixels.size != n) pixels = IntArray(n)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var idx = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = (row shr 1) * uvRowStride
            for (col in 0 until width) {
                val y = yBuf.get(yRow + col).toInt() and 0xFF
                val uvCol = (col shr 1) * uvPixelStride
                val u = (uBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128

                val y1192 = 1192 * (y - 16)
                var r = y1192 + 1634 * v
                var g = y1192 - 833 * v - 400 * u
                var b = y1192 + 2066 * u

                r = r.coerceIn(0, 262143) shr 10
                g = g.coerceIn(0, 262143) shr 10
                b = b.coerceIn(0, 262143) shr 10

                pixels[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val src = obtainRead(width, height)
        src.setPixels(pixels, 0, width, 0, 0, width, height)

        if (rotationDegrees == 0) return src
        return rotateInto(src, rotationDegrees)
    }

    private fun obtainRead(w: Int, h: Int): Bitmap {
        val cur = readBuffer
        if (cur == null || cur.width != w || cur.height != h) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            readBuffer = b
            return b
        }
        return cur
    }

    /** Rotates by drawing into a reused destination instead of allocating one. */
    private fun rotateInto(src: Bitmap, degrees: Int): Bitmap {
        val swap = degrees == 90 || degrees == 270
        val dstW = if (swap) src.height else src.width
        val dstH = if (swap) src.width else src.height

        var dst = rotatedBuffer
        if (dst == null || dst.width != dstW || dst.height != dstH) {
            dst = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            rotatedBuffer = dst
            rotateCanvas = Canvas(dst)
        }
        val canvas = rotateCanvas ?: Canvas(dst).also { rotateCanvas = it }

        canvas.save()
        when (degrees) {
            90 -> { canvas.translate(dstW.toFloat(), 0f); canvas.rotate(90f) }
            180 -> { canvas.translate(dstW.toFloat(), dstH.toFloat()); canvas.rotate(180f) }
            270 -> { canvas.translate(0f, dstH.toFloat()); canvas.rotate(270f) }
            else -> Unit
        }
        canvas.drawBitmap(src, 0f, 0f, null)
        canvas.restore()

        return dst
    }
}
