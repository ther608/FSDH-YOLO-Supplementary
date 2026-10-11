plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lychee.fsdh"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lychee.fsdh"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // The phones this is aimed at are all arm64. armeabi-v7a is kept so the
        // APK also installs on older/cheaper field devices used for comparison.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    /**
     * Two builds from one source tree.
     *
     *   full  the A/B build. Carries both runtimes (ONNX Runtime + TensorFlow
     *         Lite) and both models, because the backend comparison needs them.
     *   slim  the deployment build: ONNX Runtime and the single final model.
     *         This is the one whose APK size and memory footprint describe
     *         actually shipping FSDH-YOLO to this device. It is NOT a second
     *         performance experiment -- the reported latency comes from `full`.
     *
     * Flavors rather than a copy of the project, so the inference path cannot
     * silently diverge between what was measured and what would ship.
     * Everything is shared except `infer/Backend.kt`, `infer/ModelRegistry.kt`,
     * `infer/TfliteEngine.kt` and the assets under `src/full/assets`.
     */
    flavorDimensions += "runtime"
    productFlavors {
        create("full") {
            dimension = "runtime"
            isDefault = true
        }
        create("slim") {
            dimension = "runtime"
        }
    }

    // Keep both model formats uncompressed inside the APK so the runtimes can
    // mmap them straight out of the assets instead of inflating a copy into RAM.
    // A deflated asset cannot be mmapped at all, so this is not just an
    // optimisation for the TFLite side -- openFd() fails outright on it.
    androidResources {
        noCompress += listOf("onnx", "tflite")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Zero-conversion runtimes: this runs the exact ONNX master that was proven
    // numerically equivalent to the .pt, so the accuracy claim transfers as-is.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // Second runtime, for the GPU comparison. FULL ONLY: the slim build exists
    // precisely to report what ships without it, and linking it there would put
    // ~10 MB of native libraries and their classes into the deployment APK.
    //
    // Converted from the same ONNX via onnx2tf (scripts/convert_to_tflite.py).
    // The delegate-plugin artifact is what supplies CompatibilityList, so the
    // app can ask whether this device can actually run the graph on the GPU
    // instead of assuming it.
    "fullImplementation"("org.tensorflow:tensorflow-lite:2.16.1")
    "fullImplementation"("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    // GpuDelegateFactory.Options lives in the -api artifact, not in
    // tensorflow-lite-gpu. Without it GpuDelegate.Options resolves but its
    // supertype does not, and Kotlin fails with "Supertypes ... cannot be
    // resolved" on a class that otherwise looks present.
    "fullImplementation"("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
    "fullImplementation"("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")
}
