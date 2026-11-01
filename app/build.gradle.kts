plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.leslie.tot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.leslie.tot"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    // The TFLite model of the Hand Reading screen must stay uncompressed in the APK.
    androidResources {
        noCompress += listOf("tflite")
    }
    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "/META-INF/{AL2.0,LGPL2.1}")
        }
        // ONNX Runtime and TensorFlow Lite may each ship libc++_shared.so
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // temi robot SDK (speech / movement / battery)
    implementation("com.robotemi:sdk:1.138.0")

    // CameraX: live preview of the tablet camera
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ONNX Runtime: runs the MoveNet pose model (assets/movenet_lightning.onnx)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")

    // Hand Reading screen: TensorFlow Lite runs assets/asl_alphabet.tflite (ASL fingerspelling classifier).
    // The hand is located with the MoveNet pose model above. No MediaPipe, so Android 6 (API 23) works.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
