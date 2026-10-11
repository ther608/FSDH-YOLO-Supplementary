package com.lychee.fsdh.infer

import android.content.Context

/**
 * Every runnable (runtime x execution provider) combination this build can
 * construct.
 *
 * SLIM build: ONNX Runtime only, one model. This is the build that stands for
 * the actual deployment, so the runtimes and models that exist only to produce
 * the comparison table are not linked into it or packaged with it. The numbers
 * that come out of it -- APK size, memory footprint -- are the ones that
 * describe shipping FSDH-YOLO to this device.
 *
 * The measurement from the FULL build is the performance result; the slim build
 * is not a second performance experiment and does not repeat it. See the
 * `full` source set.
 *
 * [threadsNote] is recorded verbatim in the measurement output, here next to
 * the enum so it cannot drift from what OrtEngine.create is actually called
 * with.
 */
enum class Backend(val label: String, val threadsNote: String) {
    ORT_CPU("ORT CPU", "ORT intraOp=4, interOp=1, optLevel=ALL_OPT"),
    ORT_XNNPACK("ORT XNNPACK", "ORT intraOp=4, interOp=1, XNNPACK intra_op_num_threads=4"),
    ORT_NNAPI("ORT NNAPI", "ORT NNAPI EP, intraOp=4, interOp=1"),
}

object EngineFactory {

    /**
     * Builds the engine for [backend]. Throws if the runtime, the asset or the
     * execution provider is unavailable -- callers treat that as "skip this
     * combination and record why".
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
    }
}
