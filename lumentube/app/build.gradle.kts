plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing from the environment (CI secrets), never from the repo; else the debug key.
val releaseKeystore: String? = System.getenv("LUMENTUBE_KEYSTORE_PATH")

android {
    namespace = "dev.miraiijo.lumentube"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.miraiijo.lumentube"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        // The Freebox Player POP runs a 32-bit userspace; arm64 kept for other boxes.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // Upstream modules have SmartTube's build flavors: use the "stable" one.
        missingDimensionStrategy("default", "ststable")
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LUMENTUBE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LUMENTUBE_KEY_ALIAS")
                keyPassword = System.getenv("LUMENTUBE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Not minified, like SmartTube: its engine relies on reflection (Retrofit, Gson, JsonPath).
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    lint { checkReleaseBuilds = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    // SmartTube's engine (built from the fork's folders, see settings.gradle).
    implementation(project(":sharedutils"))
    implementation(project(":mediaserviceinterfaces"))
    implementation(project(":youtubeapi"))
    implementation(project(":exoplayer-library-core"))
    implementation(project(":exoplayer-library-dash"))
    implementation(project(":exoplayer-library-sabr"))
    implementation(project(":exoplayer-library-hls"))
    implementation(project(":exoplayer-library-smoothstreaming"))
    implementation(project(":exoplayer-library-ui"))
    implementation(project(":exoplayer-extension-okhttp"))
    // Cronet (QUIC): SmartTube's default data source for video segments.
    implementation(project(":exoplayer-extension-cronet"))
    // Same versions as SmartTube (its API returns RxJava 2 observables).
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("com.squareup.okhttp3:okhttp:3.12.13")

    // UI: same stack as Lumen.
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.0.0")
    // Thumbnails: Glide (no OkHttp 4, which Coil needs and the engine can't use).
    implementation("com.github.bumptech.glide:compose:1.0.0-beta01")
}
