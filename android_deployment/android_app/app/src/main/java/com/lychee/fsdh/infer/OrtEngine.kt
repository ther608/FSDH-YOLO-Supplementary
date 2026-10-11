package com.lychee.fsdh.infer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/** Which execution provider ONNX Runtime should use. */
enum class OrtProvider(val label: String) {
    CPU("ORT CPU"),
    XNNPACK("ORT XNNPACK"),
    NNAPI("ORT NNAPI"),
}

/**
 * ONNX Runtime Android backend.
 *
 * Runs the exported ONNX master byte-for-byte -- no conversion step sits between
 * the file whose mAP was verified and the pixels on the phone, so the accuracy
 * result transfers without argument. Preprocessing and decoding happen in
 * Kotlin ([KotlinSideEngine]).
 */
class OrtEngine private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    override val name: String,
    inputSize: Int,
) : KotlinSideEngine(inputSize) {

    private var outBuf: FloatArray = FloatArray(0)
    private var diagCount = 0

    override fun runModel(tensor: FloatBuffer): FloatArray {
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        OnnxTensor.createTensor(env, tensor, shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val value = result[0].value as Array<Array<FloatArray>>
                val rows = value[0]
                val anchors = rows[0].size
                val need = rows.size * anchors
                if (outBuf.size != need) outBuf = FloatArray(need)
                var o = 0
                for (r in rows.indices) {
                    System.arraycopy(rows[r], 0, outBuf, o, anchors)
                    o += anchors
                }
                if (diagCount < DIAG_RUNS) {
                    diagCount++
                    logRowStats(rows.size, anchors)
                }
                return outBuf
            }
        }
    }

    /**
     * Prints per-row statistics of the raw head output.
     *
     * This exists because the app once drew every box along the top edge while
     * the classes and x-coordinates looked plausible. Per-row min/max/mean
     * separates the two possible causes in one shot: a head row that is
     * degenerate (all ~0) means the tensor layout is not what the decoder
     * assumes, whereas a healthy spread in every row means the bug is in the
     * decode/transform step instead.
     */
    private fun logRowStats(rowCount: Int, anchors: Int) {
        val sb = StringBuilder()
        for (r in 0 until rowCount) {
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            var sum = 0.0
            val base = r * anchors
            for (i in 0 until anchors) {
                val v = outBuf[base + i]
                if (v < mn) mn = v
                if (v > mx) mx = v
                sum += v
            }
            sb.append("r%d[%.3f..%.3f avg%.2f] ".format(r, mn, mx, sum / anchors))
        }
        Log.i(TAG, "raw rows ($rowCount x $anchors): $sb")

        // top anchor by class score, with all seven of its values
        var bestIdx = 0
        var bestScore = -1f
        for (i in 0 until anchors) {
            var m = -1f
            for (c in 4 until rowCount) {
                val v = outBuf[c * anchors + i]
                if (v > m) m = v
            }
            if (m > bestScore) {
                bestScore = m
                bestIdx = i
            }
        }
        val vals = (0 until rowCount).joinToString(", ") {
            "%.2f".format(outBuf[it * anchors + bestIdx])
        }
        Log.i(TAG, "top anchor #$bestIdx score=%.3f values=[%s]".format(bestScore, vals))
    }

    override fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "session close: ${e.message}")
        }
        // env is a process-wide singleton; do not close it here
    }

    companion object {
        private const val TAG = "OrtEngine"
        private const val DIAG_RUNS = 2

        /** Loads `assetPath` from the APK. Throws if the provider cannot be set up. */
        fun create(
            context: Context,
            assetPath: String,
            displayName: String,
            provider: OrtProvider,
            inputSize: Int = 640,
            threads: Int = 4,
        ): OrtEngine {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()

            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setIntraOpNumThreads(threads)
            opts.setInterOpNumThreads(1)

            when (provider) {
                OrtProvider.CPU -> Unit
                OrtProvider.XNNPACK -> {
                    // XNNPACK is the strongest general-purpose CPU path on ARM.
                    opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
                OrtProvider.NNAPI -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                        throw IllegalStateException("NNAPI needs API 27+ (device is ${Build.VERSION.SDK_INT})")
                    }
                    opts.addNnapi()
                }
            }

            val modelFile = stageModel(context, assetPath)
            val session = env.createSession(modelFile.absolutePath, opts)

            val inputName = session.inputNames.first()
            Log.i(TAG, "loaded ${modelFile.name} via ${provider.label}; inputs=${session.inputNames}")

            return OrtEngine(
                env = env,
                session = session,
                inputName = inputName,
                name = "$displayName · ${provider.label}",
                inputSize = inputSize,
            )
        }

        /**
         * Copies the asset into filesDir so ORT can mmap it.
         *
         * ORT Android 1.19's `createSession` accepts only a path or a byte[];
         * there is no ByteBuffer overload (verified against the compiler, which
         * listed exactly those two). Handing it a *path* lets ORT do the mmap
         * itself, which keeps the ~12 MB model off the Java heap -- the reason
         * `noCompress += "onnx"` is set in build.gradle.kts. Passing byte[]
         * instead would put the whole model on the heap, once per engine.
         *
         * The staged copy is reused when its length already matches the asset,
         * so this costs one 12 MB write on first launch and nothing after.
         */
        private fun stageModel(context: Context, assetPath: String): File {
            val out = File(context.filesDir, assetPath.substringAfterLast('/'))

            val expected = runCatching {
                context.assets.openFd(assetPath).use { it.declaredLength }
            }.getOrNull()

            if (out.exists() && out.length() > 0 && (expected == null || out.length() == expected)) {
                return out
            }

            Log.i(TAG, "staging $assetPath -> ${out.absolutePath}")
            context.assets.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
            return out
        }
    }
}
