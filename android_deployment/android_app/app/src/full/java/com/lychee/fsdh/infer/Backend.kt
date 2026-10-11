package com.lychee.fsdh.infer

import android.content.Context

/**
 * Every runnable (runtime x execution provider) combination this build can
 * construct.
 *
 * FULL build: both runtimes. This is the A/B build the comparison table comes
 * from -- it is why the APK carries TensorFlow Lite and the baseline model as
 * well as the ONNX Runtime deployment path. See the `slim` source set for the
 * build that reports deployment cost.
 *
 * One flat enum, rather than a runtime spinner plus a provider spinner, because
 * the two runtimes do not share a provider vocabulary: ONNX Runtime offers
 * CPU/XNNPACK/NNAPI, TFLite offers CPU/GPU delegate. A flat list makes the
 * benchmark comparison read directly as "same model, different backend".
 *
 * [threadsNote] is recorded verbatim in the measurement output. It is stated
 * here, next to the enum, so it cannot drift away from what the engines are
 * actually constructed with (see OrtEngine.create and TfliteEngine.create).
 */
enum class Backend(val label: String, val threadsNote: String) {
    ORT_CPU("ORT CPU", "ORT intraOp=4, interOp=1, optLevel=ALL_OPT"),
    ORT_XNNPACK("ORT XNNPACK", "ORT intraOp=4, interOp=1, XNNPACK intra_op_num_threads=4"),
    ORT_NNAPI("ORT NNAPI", "ORT NNAPI EP, intraOp=4, interOp=1"),
    TFLITE_CPU("TFLite CPU", "TFLite numThreads=4"),
    TFLITE_GPU("TFLite GPU", "TFLite GPU delegate, precisionLossAllowed=true"),
}

object EngineFactory {

    /**
     * Builds the engine for [backend]. Throws if the runtime, the asset or the
     * execution provider is unavailable -- callers treat that as "skip this
     * combination and record why", which is how the benchmark surfaces things
     * like a missing .tflite or a GPU delegate that refuses the graph.
     */
    fun create(
        context: Context,
        model: ModelSpec,
        backend: Backend,
        inputSize: Int = 640,
    ): InferenceEngine = when (backend) {
        Backend.ORT_CPU -> OrtEngine.create(
            context, model.onnxAsset, model.displayName, OrtProvider.CPU, inputSize
        )
        Backend.ORT_XNNPACK -> OrtEngine.create(
            context, model.onnxAsset, model.displayName, OrtProvider.XNNPACK, inputSize
        )
        Backend.ORT_NNAPI -> OrtEngine.create(
            context, model.onnxAsset, model.displayName, OrtProvider.NNAPI, inputSize
        )
        Backend.TFLITE_CPU -> TfliteEngine.create(
            context, model.tfliteAsset, model.displayName, preferGpu = false, inputSize = inputSize
        )
        Backend.TFLITE_GPU -> TfliteEngine.create(
            context, model.tfliteAsset, model.displayName, preferGpu = true, inputSize = inputSize
        )
    }
}
