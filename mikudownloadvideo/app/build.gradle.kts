
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.mikudownloader.mikudownloadvideo.app"

    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.mikudownloader.mikudownloadvideo.app"

        minSdk = 26
        targetSdk = 37

        versionCode = 1
        versionName = "0.2.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(
                listOf(
                    "arm64-v8a",
                    "armeabi-v7a",
                    "x86_64",
                    "x86"
                )
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// ======================================
// MIKU DOWNLOADER - DEPENDENCIAS
// ======================================

dependencies {

    // Android básico
    implementation(
        "androidx.core:core-ktx:1.17.0"
    )

    implementation(
        "androidx.lifecycle:lifecycle-runtime-ktx:2.9.4"
    )

    implementation(
        "androidx.activity:activity-compose:1.13.0"
    )

    // ==================================
    // JETPACK COMPOSE
    // ==================================

    implementation(
        platform(
            "androidx.compose:compose-bom:2026.09.00"
        )
    )

    implementation(
        "androidx.compose.ui:ui"
    )

    implementation(
        "androidx.compose.ui:ui-graphics"
    )

    implementation(
        "androidx.compose.ui:ui-tooling-preview"
    )

    implementation(
        "androidx.compose.foundation:foundation"
    )

    implementation(
        "androidx.compose.material3:material3"
    )

    implementation(
        "androidx.compose.material:material-icons-core"
    )

    // ==================================
    // MOTOR DE DESCARGAS YT-DLP
    // ==================================

    implementation(
        "io.github.junkfood02.youtubedl-android:library:0.18.1"
    )

    // ==================================
    // FFmpeg - MP3 / MP4
    // ==================================

    implementation(
        "io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1"
    )

    // ==================================
    // COROUTINES
    // ==================================

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2"
    )

    // ==================================
    // PRUEBAS
    // ==================================

    testImplementation(
        "junit:junit:4.13.2"
    )

    androidTestImplementation(
        "androidx.test.ext:junit:1.3.0"
    )

    androidTestImplementation(
        "androidx.test.espresso:espresso-core:3.7.0"
    )

    androidTestImplementation(
        platform(
            "androidx.compose:compose-bom:2026.09.00"
        )
    )

    androidTestImplementation(
        "androidx.compose.ui:ui-test-junit4"
    )

    debugImplementation(
        "androidx.compose.ui:ui-tooling"
    )

    debugImplementation(
        "androidx.compose.ui:ui-test-manifest"
    )
}
