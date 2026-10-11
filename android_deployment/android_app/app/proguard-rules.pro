# ONNX Runtime reaches into native code and reflection; keep its surface intact.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
