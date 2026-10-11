package com.lychee.fsdh.infer

/**
 * The single model the SLIM build ships: FSDH-YOLO-n, ONNX only.
 *
 * The YOLO11n baseline and both .tflite conversions are deliberately absent.
 * They exist to support the A/B comparison, and carrying them would inflate
 * exactly the two numbers this build is built to report -- APK size and memory
 * footprint -- while never being executed in the deployment path.
 *
 * The .onnx comes from scripts/export_onnx.py and is the file whose numerical
 * equivalence to the .pt was verified; see the repo README.
 *
 * TERMINOLOGY -- SHAB vs SHEB
 * The module was originally named SHAB, renamed SHEB in the paper because it is
 * not a true attention mechanism. The rename did not reach the model config or
 * the Python class: `yolo11n_FSDConv-SHAB.yaml` and the `SHAB` class keep the
 * old name. Both names denote the same module.
 */
data class ModelSpec(
    val displayName: String,
    val onnxAsset: String,
    val note: String,
)

object ModelRegistry {

    val MODELS = listOf(
        ModelSpec(
            displayName = "FSDH-YOLO-n (seed0)",
            onnxAsset = "fsdh_yolo_n_rerun_seed0_640_fp32.onnx",
            note = "SPOConv + SHAB, fp32",
        ),
    )

    val DEFAULT: ModelSpec get() = MODELS.first()
}
