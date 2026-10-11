package com.lychee.fsdh.infer

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite / LiteRT backend, used for the GPU comparison.
 *
 * Unlike the ONNX Runtime path this is NOT numerically identical to the .pt:
 * the graph is re-emitted by onnx2tf, so it deserves its own accuracy check
 * (scripts/verify_tflite.py) before any of its numbers go in the paper.
 *
 * The GPU delegate is applied only when TensorFlow's own CompatibilityList says
 * this device can run *this* graph on the GPU. That check matters here: the
 * network opens with a 6-D Transpose (PixelUnshuffle, perm 0,1,3,5,2,4), which
 * most mobile GPU backends cannot execute, and an unconditional delegate would
 * silently partition the graph between GPU and CPU -- slower than plain CPU,
 * with no indication that it happened. The resolved delegate is reported in
 * [name] so the benchmark record shows what actually ran.
 */
class TfliteEngine private constructor(
    private val interpreter: Interpreter,
    override val name: String,
    inputSize: Int,
    /** "GPU" or "CPU" -- what actually ended up running the graph. */
    val delegate: String,
    // onnx2tf emits an NHWC input ([1,640,640,3]); see Preprocessor.interleaved
) : KotlinSideEngine(inputSize, interleavedInput = true) {

    private val inBytes: ByteBuffer =
        ByteBuffer.allocateDirect(3 * inputSize * inputSize * 4)
            .order(ByteOrder.nativeOrder())
    private val inFloats: FloatBuffer = inBytes.asFloatBuffer()

    private val outBytes: ByteBuffer =
        ByteBuffer.allocateDirect(OUT_VALUES * 4).order(ByteOrder.nativeOrder())
    private var outFloats = FloatArray(0)

    override fun runModel(tensor: FloatBuffer): FloatArray {
        inFloats.rewind()
        inFloats.put(tensor)
        inBytes.rewind()

        outBytes.rewind()
        interpreter.run(inBytes, outBytes)

        if (outFloats.size != OUT_VALUES) outFloats = FloatArray(OUT_VALUES)
        outBytes.rewind()
        outBytes.asFloatBuffer().get(outFloats)
        return outFloats
    }

    override fun close() {
        try {
            interpreter.close()
        } catch (e: Exception) {
            Log.w(TAG, "interpreter close: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TfliteEngine"

        /** 4 box channels + 3 classes, over 8400 anchors. */
        private const val OUT_VALUES = 7 * 8400

        const val DELEGATE_GPU = "GPU"
        const val DELEGATE_CPU = "CPU"

        fun create(
            context: Context,
            assetPath: String,
            displayName: String,
            preferGpu: Boolean,
            inputSize: Int = 640,
            threads: Int = 4,
        ): TfliteEngine {
            val opts = Interpreter.Options()
            var delegate = DELEGATE_CPU

            if (preferGpu) {
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    val gpuOpts = compat.bestOptionsForThisDevice
                    // fp16 on the GPU path: halves weight traffic, and the
                    // accuracy cost is measured rather than assumed.
                    gpuOpts.setPrecisionLossAllowed(true)
                    opts.addDelegate(GpuDelegate(gpuOpts))
                    delegate = DELEGATE_GPU
                } else {
                    Log.w(TAG, "GPU delegate unavailable for this graph/device; using CPU")
                }
            }
            if (delegate == DELEGATE_CPU) {
                opts.setNumThreads(threads)
            }

            val model = mapAsset(context, assetPath)
            val interpreter = Interpreter(model, opts)

            val inShape = interpreter.getInputTensor(0).shape().joinToString(",")
            val outShape = interpreter.getOutputTensor(0).shape().joinToString(",")
            Log.i(TAG, "loaded $assetPath via $delegate; in=[$inShape] out=[$outShape]")

            return TfliteEngine(
                interpreter = interpreter,
                name = "$displayName · TFLite $delegate",
                inputSize = inputSize,
                delegate = delegate,
            )
        }

        /** Maps an uncompressed asset; falls back to a buffered read.
         *  Declared as ByteBuffer, not MappedByteBuffer: only the fast path
         *  mmaps, and the fallback returns a plain direct buffer that
         *  Interpreter accepts just as well. */
        private fun mapAsset(context: Context, assetPath: String): ByteBuffer {
            return try {
                context.assets.openFd(assetPath).use { fd ->
                    FileInputStream(fd.fileDescriptor).use { fis ->
                        fis.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            fd.startOffset,
                            fd.declaredLength
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "mmap failed ($e); buffered read")
                val bytes = context.assets.open(assetPath).use { it.readBytes() }
                val direct = ByteBuffer.allocateDirect(bytes.size)
                direct.put(bytes)
                direct.rewind()
                direct
            }
        }
    }
}
