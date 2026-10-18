plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.kiora.companion"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kiora.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("dev.kiora:kiora-core:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-model-chat:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-a2a:0.3.0-SNAPSHOT")
    implementation("dev.kiora:kiora-android-attachments:0.3.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
