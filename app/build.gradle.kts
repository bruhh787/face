plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.facesort.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.facesort.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += "onnx" } // mapped straight from the APK
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.fragment:fragment:1.8.5") // satisfies ActivityResult lint check

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("com.google.mlkit:face-detection:16.1.7") // bundled model, works fully offline

    // ArcFace (InsightFace w600k_r50) runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")

    testImplementation("junit:junit:4.13.2")
}

// ---- Face model guard -------------------------------------------------------------------------------------
// The ArcFace model (~174 MB) is not in git. CI and scripts/fetch_model.sh download it from a pinned URL and
// verify its sha256. Fail early with a clear message if it is missing or obviously incomplete.
val faceModel = file("src/main/assets/arcface_w600k_r50.onnx")
val checkFaceModel by tasks.registering {
    description = "Fails the build if the ArcFace model asset is missing."
    doLast {
        if (!faceModel.isFile || faceModel.length() < 100_000_000L) {
            throw GradleException(
                "Missing face model: ${faceModel.relativeTo(rootDir)}\n" +
                "Run:  bash scripts/fetch_model.sh   (downloads InsightFace buffalo_l, checks sha256, extracts w600k_r50.onnx)\n" +
                "Note: InsightFace models are licensed for non-commercial research use only."
            )
        }
    }
}
tasks.configureEach {
    if (name.matches(Regex("merge\\w*Assets"))) dependsOn(checkFaceModel)
}
