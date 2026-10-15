plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shortsmaker.app"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.shortsmaker.app"
        minSdk = 26          // needed for MediaCodec h264_mediacodec + modern camera/media APIs
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                // Enable NEON / multi-thread matmul in whisper.cpp for usable on-device speed.
                cppFlags += listOf("-O3")
                arguments += listOf("-DGGML_NATIVE=OFF", "-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            // arm64-v8a covers the vast majority of phones in active use; add others if you need them.
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }
    packaging {
        // Several native-code deps (whisper.cpp, ffmpeg-kit) ship overlapping license/readme files.
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }
}

dependencies {
    // --- UI (Jetpack Compose) ---
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // --- Background work (long pipeline runs must survive app backgrounding) ---
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Face detection / tracking (official Google library, on-device) ---
    implementation("com.google.mediapipe:tasks-vision:0.10.21")

    // --- Video render + burn-in captions: maintained FFmpegKit fork (original retired Apr 2025). ---
    // "full" (not "-gpl") build = AGPL-free codecs only; H.264 encode uses the device's hardware
    // encoder (h264_mediacodec) so you do NOT need the GPL libx264 build or its copyleft terms.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")

    // --- "Paste a YouTube link" download, on-device (bundles yt-dlp + a small Python runtime) ---
    implementation("dev.ffmpegkit-maintained:yt-dlp-android:1.0.1")

    // --- Networking, only used for: (a) the LLM step if the user supplies an API key,
    //     (b) yt-dlp's own network use. No other network calls exist in this app. ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
