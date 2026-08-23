plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Kotlin 1.9.24 -> Compose Compiler 1.5.14 per Google's official compatibility
        // map. The newer org.jetbrains.kotlin.plugin.compose Gradle plugin only exists
        // for Kotlin 2.0+, so this project (pinned to 1.9.24 to match what was actually
        // compiled/tested against in this audit) uses the classic explicit-version path.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Encrypted local storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking for DeepSeek cloud calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Real low-power local wake-word / offline STT engine (grammar-constrained Kaldi model).
    // ~40MB small model, runs fully on-device, no network.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Real on-device LLM runtime for offline intent classification + short local answers.
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    // Icons used in Settings (Delete icon for memory management). No explicit version —
    // governed by the compose-bom platform above, same as the other compose.* artifacts.
    implementation("androidx.compose.material:material-icons-core")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
