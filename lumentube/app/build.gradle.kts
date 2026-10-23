import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Kotlin is built into AGP 9: no org.jetbrains.kotlin.android here.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing from the environment only (never from the repo). Without it the release APK is
// unsigned: CI builds it that way and lumen-ship signs it on the Mac (the key never reaches GitHub).
val releaseKeystore: String? = System.getenv("LUMENTUBE_KEYSTORE_PATH")

android {
    namespace = "dev.miraiijo.lumentube"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.miraiijo.lumentube"
        minSdk = 28
        targetSdk = 35
        versionCode = 70
        versionName = "1.0 bêta 9"
        // The Freebox Player POP runs a 32-bit userspace; arm64 kept for other boxes.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // Upstream modules have SmartTube's build flavors: use the "stable" one.
        missingDimensionStrategy("default", "ststable")
        // lumen-link v3: signing certificates of the Lumen apps (public SHA-256, see gradle.properties,
        // written by lumen-keys init). Empty = no link with that app (FeedProvider answers nobody).
        fun cert(app: String) = (project.findProperty("lumen.cert.$app") as String?).orEmpty().trim().lowercase().replace(":", "")
        buildConfigField("String", "CERT_LUMEN", "\"${cert("lumen")}\"")
        buildConfigField("String", "CERT_LUMENTUBE", "\"${cert("lumentube")}\"")
        buildConfigField("String", "CERT_LUMENIPTV", "\"${cert("lumeniptv")}\"")
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
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else null
        }
    }

    lint { checkReleaseBuilds = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
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
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.1.0")
    // Installs the Baseline Profiles (AndroidX/Compose libraries' + src/main/baseline-prof.txt) on a
    // sideloaded APK too (no Play Store): faster cold start and first scrolls. Same version as Lumen.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Thumbnails: Glide (no OkHttp 4, which Coil needs and the engine can't use).
    implementation("com.github.bumptech.glide:glide:5.0.9")
    implementation("com.github.bumptech.glide:compose:1.0.0-beta10")
    // QR code of the sign-in, drawn on the box (no web QR service sees the activation code).
    implementation("com.google.zxing:core:3.5.4")
}
