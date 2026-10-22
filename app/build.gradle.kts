plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.kiora.companion"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kiora.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 82
        versionName = "0.1.81"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (providers.gradleProperty("ocrCompareArm64Only").isPresent) {
            ndk.abiFilters += "arm64-v8a"
        }
    }
    buildTypes {
        getByName("debug") {
            if (providers.gradleProperty("companionPreview").isPresent) {
                applicationIdSuffix = ".preview"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation("dev.kiora:kiora-core:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-model-chat:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-model-litert:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-embedding:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-embedding-mediapipe:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-model-mnn:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-a2a:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-android-attachments:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-android-storage-tools:0.3.0-SNAPSHOT")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1-kiora-coexist")
    implementation("dev.kiora:kiora-document-ocr-paddle:0.3.0-SNAPSHOT") {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
    implementation("dev.kiora:kiora-speech-sherpa-onnx:0.3.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.core:core:1.1.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
}
