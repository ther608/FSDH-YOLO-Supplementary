package com.lychee.fsdh.infer

/**
 * Models bundled in `assets/` by the FULL build: the deployment model plus the
 * baseline it is compared against.
 *
 * Both files for a model must be static, batch-1, 640x640, with NMS omitted
 * (raw head output). The .onnx comes from scripts/export_onnx.py; the .tflite
 * from scripts/convert_to_tflite.py, which derives it from that same .onnx.
 *
 * TERMINOLOGY -- SHAB vs SHEB
 * The module was originally named SHAB. It was renamed SHEB because the
 * architecture is not a true attention mechanism, and SHEB is the name the
 * paper uses throughout. The rename did NOT reach the model config or the
 * Python class, so `yolo11n_FSDConv-SHAB.yaml` and the `SHAB` class still carry
 * the old name. A reader looking for SHEB in the released code will not find
 * it; a reader looking for SHAB will not find it in the paper. Both names
 * denote the same module.
 */
data class ModelSpec(
    val displayName: String,
    val onnxAsset: String,
    val tfliteAsset: String,
    val note: String,
)

object ModelRegistry {

    val MODELS = listOf(
        ModelSpec(
            displayName = "FSDH-YOLO-n (seed0)",
            onnxAsset = "fsdh_yolo_n_rerun_seed0_640_fp32.onnx",
            tfliteAsset = "fsdh_yolo_n_rerun_seed0_640_fp32.tflite",
            note = "SPOConv + SHAB, fp32",
        ),
        ModelSpec(
            displayName = "YOLO11n baseline",
            onnxAsset = "yolo11n_baseline_640_fp32.onnx",
            tfliteAsset = "yolo11n_baseline_640_fp32.tflite",
            note = "reference backbone at the same input size",
        ),
    )

    val DEFAULT: ModelSpec get() = MODELS.first()
}
